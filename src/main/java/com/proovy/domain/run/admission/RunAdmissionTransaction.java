package com.proovy.domain.run.admission;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.conversation.entity.ChatMessage;
import com.proovy.domain.conversation.repository.ChatMessageRepository;
import com.proovy.domain.credit.service.CreditBalanceService;
import com.proovy.domain.note.entity.Note;
import com.proovy.domain.note.entity.NoteStatus;
import com.proovy.domain.run.contract.BrowserRequestFingerprint;
import com.proovy.domain.run.contract.BrowserRunContract;
import com.proovy.domain.run.contract.ConversationRunRequest;
import com.proovy.domain.run.contract.PublicRunStatus;
import com.proovy.domain.run.contract.RunCreatedPayload;
import com.proovy.domain.run.contract.ServerFrameType;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.entity.RunAssetDependency;
import com.proovy.domain.run.entity.RunAssetDependencyKind;
import com.proovy.domain.run.event.RunEventStore;
import com.proovy.domain.run.repository.AiRunRepository;
import com.proovy.domain.run.repository.RunAssetDependencyRepository;
import com.proovy.domain.run.snapshot.RunSnapshot;
import com.proovy.domain.run.snapshot.RunSnapshotFactory;
import com.proovy.domain.run.snapshot.RunSnapshotRejectedException;
import com.proovy.domain.usage.exception.InsufficientUsageBalanceException;
import com.proovy.domain.usage.exception.RequestUsageConflictException;
import com.proovy.domain.usage.repository.RequestUsageChargeRepository;
import com.proovy.domain.usage.service.RequestUsageService;
import com.proovy.domain.user.entity.User;
import com.proovy.global.response.ErrorCode;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수 한 번(transaction 하나). {@link RunAdmissionService}가 요청 판정 뒤 호출하고, unique 경합·lock 실패면 같은 body로
 * 새 transaction을 다시 연다. 판정 순서는 browser-run-v1 {@code admission/decisions.json}의 precedence다:
 * <pre>
 * existing(같은 사용자·같은 의미 replay / 그 밖 RUN4092) → note(RUN4041) → references(RUN4221)
 *   → activeRun(RUN4091{activeRunId}) → rateLimited(RUN4291) → balance(RUN4021) → accept
 * </pre>
 * 승인되면 같은 transaction에서 (필요하면) Note → user message → assistant placeholder → PENDING run(불변 snapshot) →
 * asset 의존성 → 최초 차감 → requestId binding → seq 1 {@code run.created}를 저장한다. 어느 단계든 실패하면 전부
 * rollback되어 메시지·차감·event가 남지 않는다. Brain 호출·네트워크 대기는 이 transaction 안에 없다.
 *
 * <h2>잠금 순서</h2>
 * Note 행({@code FOR UPDATE}, asset 삭제와 같은 순서) → 사용자 잔액 행(rate limit·차감) → asset 행({@code FOR SHARE}) →
 * run 행(event seq). 같은 Note의 동시 접수는 Note 잠금으로 직렬화되어 뒤 transaction이 앞 run을 Active Run으로 보고,
 * 그래도 남는 경합(새 Note의 같은 requestId 등)은 {@code uq_ai_runs_request}·{@code ux_ai_runs_note_active}·binding PK가
 * 막는다(호출자가 재시도해 승자를 replay하거나 409로 답한다).
 */
@Slf4j
@Service
public class RunAdmissionTransaction {

    /** 자동 생성 Note 제목 상한(code point). 사용자 제목 규칙(NOTE4001, 30자)과 같다. */
    static final int AUTO_TITLE_MAX_CODE_POINTS = 30;
    static final String AUTO_TITLE_FALLBACK = "새 노트";

    private static final String LOCK_NOTE = "SELECT user_id, status FROM notes WHERE note_id = ? FOR UPDATE";
    private static final String NTH_RECENT_RUN = """
            SELECT created_at FROM ai_runs WHERE user_id = ? AND created_at > ?
            ORDER BY created_at DESC OFFSET ? LIMIT 1
            """;
    private static final String LOCK_ACTIVE_ASSETS = """
            SELECT id FROM assets WHERE note_id = ? AND status = 'ACTIVE' AND id IN (%s) FOR SHARE
            """;

    private final RunRequestBindingRepository bindingRepository;
    private final RequestUsageChargeRepository chargeRepository;
    private final AiRunRepository aiRunRepository;
    private final RunAssetDependencyRepository dependencyRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RunSnapshotFactory snapshotFactory;
    private final RequestUsageService usageService;
    private final CreditBalanceService creditBalanceService;
    private final RunEventStore eventStore;
    private final RunAdmissionProperties properties;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public RunAdmissionTransaction(RunRequestBindingRepository bindingRepository,
                                   RequestUsageChargeRepository chargeRepository,
                                   AiRunRepository aiRunRepository,
                                   RunAssetDependencyRepository dependencyRepository,
                                   ChatMessageRepository chatMessageRepository,
                                   RunSnapshotFactory snapshotFactory,
                                   RequestUsageService usageService,
                                   CreditBalanceService creditBalanceService,
                                   RunEventStore eventStore,
                                   RunAdmissionProperties properties,
                                   JdbcTemplate jdbc,
                                   EntityManager entityManager) {
        this.bindingRepository = bindingRepository;
        this.chargeRepository = chargeRepository;
        this.aiRunRepository = aiRunRepository;
        this.dependencyRepository = dependencyRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.snapshotFactory = snapshotFactory;
        this.usageService = usageService;
        this.creditBalanceService = creditBalanceService;
        this.eventStore = eventStore;
        this.properties = properties;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    /**
     * @param request     {@code ConversationRunRequestValidator}를 통과한 body
     * @param fingerprint 그 body의 browser-run-v1 요청 fingerprint
     * @throws RunAdmissionRejectedException       계약 오류(RUN4092·RUN4041·RUN4221·RUN4091·RUN4291·RUN4021)
     * @throws org.springframework.dao.DataAccessException unique 경합·lock 실패(호출자가 새 transaction으로 재시도)
     */
    @Transactional
    public RunAdmission admit(long userId, ConversationRunRequest request, BrowserRequestFingerprint fingerprint) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Long requestedNoteId = request.noteId() == null ? null : Long.valueOf(request.noteId());
        // Note를 먼저 잠근다: 같은 Note의 동시 재전송이 잠금을 기다린 뒤 아래 requestId 조회에서 승자를 본다.
        Optional<LockedNote> lockedNote = requestedNoteId == null ? Optional.empty() : lockNote(requestedNoteId);

        Optional<RunAdmission> replay = existing(userId, request.requestId(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        Note note;
        if (requestedNoteId == null) {
            note = createNote(userId, request.text());
        } else if (lockedNote.filter(found -> found.isActiveFor(userId)).isPresent()) {
            note = entityManager.getReference(Note.class, requestedNoteId);
        } else {
            throw RunAdmissionRejectedException.of(ErrorCode.RUN4041);
        }
        long noteId = note.getId();

        UUID runId = UUID.randomUUID();
        RunSnapshot snapshot;
        try {
            snapshot = snapshotFactory.create(runId, userId, noteId, request, now);
        } catch (RunSnapshotRejectedException e) {
            // 판정표 note(RUN4041)·references(RUN4221) 단계
            throw RunAdmissionRejectedException.of(e.getErrorCode());
        }

        Optional<AiRun> active = aiRunRepository.findActiveByNoteId(noteId);
        if (active.isPresent()) {
            throw RunAdmissionRejectedException.activeRun(active.get().getId());
        }
        // 잔액 행을 여기서 잠가 같은 사용자의 접수를 직렬화한다(rate limit 판정이 경합으로 새지 않게). 차감도 같은 잠금을 쓴다.
        creditBalanceService.lockBalanceInCurrentTransaction(userId);
        requireWithinRateLimit(userId, now);

        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        chatMessageRepository.save(ChatMessage.user(userMessageId, note, userInput(request), now));
        // 같은 turn에서 assistant가 user보다 늦다(#227 R6). run 생성 순서 = user 메시지 순서(1.78 history 정렬 전제).
        chatMessageRepository.save(ChatMessage.assistantPlaceholder(assistantMessageId, note,
                now.plus(1, ChronoUnit.MICROS)));
        AiRun run = aiRunRepository.saveAndFlush(snapshot.toPendingRun(userMessageId, assistantMessageId, now));

        List<RunAssetDependency> dependencies = snapshot.assetDependencies(now);
        dependencyRepository.saveAllAndFlush(dependencies);
        requireDependenciesStillActive(noteId, dependencies);

        try {
            usageService.chargeRequest(userId, request.requestId(), runId);
        } catch (InsufficientUsageBalanceException e) {
            throw RunAdmissionRejectedException.of(ErrorCode.RUN4021);
        } catch (RequestUsageConflictException e) {
            // run이 지워진(Note 삭제) 옛 requestId의 차감이 남아 있다 — 다른 요청의 ID 재사용이다.
            throw RunAdmissionRejectedException.of(ErrorCode.RUN4092);
        }
        bindingRepository.saveAndFlush(new RunRequestBinding(request.requestId(), userId, runId,
                BrowserRunContract.FINGERPRINT_ALGORITHM, fingerprint.sha256(), now));

        eventStore.appendServerEvent(runId, ServerFrameType.RUN_CREATED, new RunCreatedPayload(
                request.requestId(), String.valueOf(noteId), userMessageId.toString(), assistantMessageId.toString(),
                PublicRunStatus.PENDING, orEmpty(request.requestedTasks()), orEmpty(request.requestedOutputs())), now);

        log.info("run admitted: runId={}, noteId={}, userId={}", runId, noteId, userId);
        return RunAdmission.of(RunAdmission.Outcome.CREATED, run);
    }

    /**
     * 같은 requestId의 기존 접수. 같은 사용자·같은 의미면 그 run을 돌려주고(부작용 없음), 다른 사용자·다른 의미·
     * binding 없이 차감만 남은 ID(run이 삭제됨)면 RUN4092다. RUN4092는 기존 run·Note의 상태를 드러내지 않는다.
     */
    private Optional<RunAdmission> existing(long userId, String requestId, BrowserRequestFingerprint fingerprint) {
        Optional<RunRequestBinding> binding = bindingRepository.findById(requestId);
        if (binding.isEmpty()) {
            if (aiRunRepository.findByRequestId(requestId).isPresent() || chargeRepository.existsById(requestId)) {
                throw RunAdmissionRejectedException.of(ErrorCode.RUN4092);
            }
            return Optional.empty();
        }
        if (!binding.get().matches(userId, BrowserRunContract.FINGERPRINT_ALGORITHM, fingerprint.sha256())) {
            throw RunAdmissionRejectedException.of(ErrorCode.RUN4092);
        }
        AiRun run = aiRunRepository.findById(binding.get().getRunId())
                .orElseThrow(() -> new IllegalStateException("request binding without run " + requestId));
        return Optional.of(RunAdmission.of(RunAdmission.Outcome.REPLAYED, run));
    }

    private Optional<LockedNote> lockNote(long noteId) {
        return jdbc.query(LOCK_NOTE, (rs, row) -> new LockedNote(rs.getLong(1),
                NoteStatus.valueOf(rs.getString(2))), noteId).stream().findFirst();
    }

    private Note createNote(long userId, String text) {
        Note note = Note.builder()
                .user(entityManager.getReference(User.class, userId))
                .title(autoTitle(text))
                .build();
        entityManager.persist(note);
        entityManager.flush();
        return note;
    }

    /** 첫 질문으로 만든 Note의 제목: strip·공백 정리한 질문 앞부분. Gemini 제목 생성은 접수 transaction 밖(후속)이다. */
    static String autoTitle(String text) {
        String normalized = BrowserRunContract.strip(text).replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return AUTO_TITLE_FALLBACK;
        }
        if (normalized.codePointCount(0, normalized.length()) <= AUTO_TITLE_MAX_CODE_POINTS) {
            return normalized;
        }
        return BrowserRunContract.strip(normalized.substring(0,
                normalized.offsetByCodePoints(0, AUTO_TITLE_MAX_CODE_POINTS)));
    }

    /**
     * 최근 window 안의 사용자 run이 상한 이상이면 RUN4291. Retry-After는 그중 상한번째로 최근 run이 window를 벗어날 때까지다.
     * 같은 사용자의 접수는 잔액 잠금으로 직렬화된 뒤 여기에 온다.
     */
    private void requireWithinRateLimit(long userId, Instant now) {
        RunAdmissionProperties.RateLimit limit = properties.rateLimit();
        Instant windowStart = now.minus(limit.window());
        List<Instant> boundary = jdbc.query(NTH_RECENT_RUN, (rs, row) -> rs.getTimestamp(1).toInstant(),
                userId, Timestamp.from(windowStart), limit.maxRequests() - 1);
        if (boundary.isEmpty()) {
            return;
        }
        Duration wait = Duration.between(now, boundary.get(0).plus(limit.window()));
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        throw RunAdmissionRejectedException.rateLimited(seconds);
    }

    /**
     * snapshot은 asset을 잠그지 않고 읽었다(1.78). 의존성을 넣은 뒤 그 asset들이 아직 같은 Note의 ACTIVE인지 행을
     * {@code FOR SHARE}로 잠가 확인한다 — 잠금은 commit까지 유지되어 그 사이 삭제·상태 변경이 끼지 못한다.
     * Viewer Focus asset은 corpus 구성원이어야 한다(둘을 따로 읽는 사이 삭제되면 어긋난다).
     */
    private void requireDependenciesStillActive(long noteId, List<RunAssetDependency> dependencies) {
        if (dependencies.isEmpty()) {
            return;
        }
        Set<Long> corpus = new HashSet<>();
        Set<Long> required = new HashSet<>();
        for (RunAssetDependency dependency : dependencies) {
            required.add(dependency.getAssetId());
            if (dependency.getDependencyKind() == RunAssetDependencyKind.CORPUS_SNAPSHOT) {
                corpus.add(dependency.getAssetId());
            }
        }
        if (!corpus.containsAll(required)) {
            throw RunAdmissionRejectedException.of(ErrorCode.RUN4221);
        }
        List<Object> args = new ArrayList<>();
        args.add(noteId);
        args.addAll(required);
        String placeholders = String.join(", ", Collections.nCopies(required.size(), "?"));
        Set<Long> active = new HashSet<>(jdbc.queryForList(LOCK_ACTIVE_ASSETS.formatted(placeholders), Long.class,
                args.toArray()));
        if (!active.equals(required)) {
            throw RunAdmissionRejectedException.of(ErrorCode.RUN4221);
        }
    }

    private static ObjectNode userInput(ConversationRunRequest request) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("text", request.text());
        if (request.latex() != null) {
            input.put("latex", request.latex());
        }
        return input;
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record LockedNote(long userId, NoteStatus status) {

        boolean isActiveFor(long requester) {
            return userId == requester && status == NoteStatus.ACTIVE;
        }
    }
}
