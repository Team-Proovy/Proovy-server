package com.proovy.domain.run.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.contract.ActiveRunSummary;
import com.proovy.domain.run.contract.AssistantCancel;
import com.proovy.domain.run.contract.AssistantFailure;
import com.proovy.domain.run.contract.AssistantMessageContent;
import com.proovy.domain.run.contract.BrowserRunContract;
import com.proovy.domain.run.contract.CancelCause;
import com.proovy.domain.run.contract.ConversationMessage;
import com.proovy.domain.run.contract.ConversationMessageStatus;
import com.proovy.domain.run.contract.ConversationPage;
import com.proovy.domain.run.contract.MessageRole;
import com.proovy.domain.run.contract.PageSelection;
import com.proovy.domain.run.contract.PublicRunStatus;
import com.proovy.domain.run.contract.RequestedOutput;
import com.proovy.domain.run.contract.RequestedTask;
import com.proovy.domain.run.contract.RunFailureSummary;
import com.proovy.domain.run.contract.RunStatus;
import com.proovy.domain.run.contract.TurnAttachmentMime;
import com.proovy.domain.run.contract.TurnAttachmentView;
import com.proovy.domain.run.contract.UserMessageContent;
import com.proovy.domain.run.contract.ViewerFocus;
import com.proovy.domain.run.entity.AiRunStatus;
import com.proovy.domain.run.terminal.TerminalReplay;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * run 상태({@code GET /api/ai-runs/{runId}})와 대화 조회({@code GET /api/conversations/{noteId}/messages}).
 * <p>
 * 대화 조회의 완료 assistant 본문은 SSE {@code run.completed}와 같은 함수({@link TerminalReplay#completedPayload})로 만든다.
 * 그래서 조회·replay·live가 같은 page/block/source ID·순서·plain text를 주고, 차이는 read-time {@code available}뿐이다.
 * 진행 중 placeholder에는 본문이 없다(Candidate 비공개). 실패 정보는 그 run의 {@code run.failed} payload다.
 * <p>
 * 접근: run·Note 소유자이고 Note가 ACTIVE일 때만. 없음·타인·삭제 중은 같은 RUN4041이다(존재 oracle 방지).
 */
@Service
public class RunReadService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]{0,2}");
    private static final List<String> ACTIVE_STATUSES = AiRunStatus.ACTIVE.stream().map(Enum::name).toList();

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SourceAvailability availability;

    public RunReadService(JdbcTemplate jdbc, ObjectMapper objectMapper, SourceAvailability availability) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.availability = availability;
    }

    /** 공개 경로에서 접근 가능한 run. {@code runId}가 소문자 canonical UUID가 아니면 없는 run과 같다. */
    record RunRow(UUID runId, String requestId, long noteId, UUID userMessageId, UUID assistantMessageId,
                  AiRunStatus status, long lastSeq, String responseDisposition, Instant createdAt,
                  Instant completedAt) {
    }

    @Transactional(readOnly = true)
    public Optional<RunRow> accessibleRun(long userId, String runId) {
        if (!BrowserRunContract.isUuid(runId)) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT r.run_id, r.request_id, r.note_id, r.user_message_id, r.assistant_message_id, r.status,
                       r.last_event_seq, r.response_disposition, r.created_at, r.completed_at
                FROM ai_runs r JOIN notes n ON n.note_id = r.note_id AND n.user_id = r.user_id
                WHERE r.run_id = ? AND r.user_id = ? AND n.status = 'ACTIVE'
                """, (rs, row) -> new RunRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), AiRunStatus.valueOf(rs.getString(6)),
                rs.getLong(7), rs.getString(8), instant(rs, 9), instant(rs, 10)),
                UUID.fromString(runId), userId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public RunStatus status(long userId, String runId) {
        RunRow run = accessibleRun(userId, runId).orElseThrow(() -> new BusinessException(ErrorCode.RUN4041));
        PublicRunStatus status = PublicRunStatus.from(run.status());
        RunFailureSummary failure = null;
        if (run.status() == AiRunStatus.FAILED) {
            JsonNode failed = failedPayloads(List.of(run.runId())).get(run.runId());
            failure = new RunFailureSummary(failed.get("code").asText(), textOrNull(failed, "reason"),
                    failed.get("recoverable").asBoolean());
        }
        return new RunStatus(run.runId().toString(), run.requestId(), String.valueOf(run.noteId()),
                run.userMessageId().toString(), run.assistantMessageId().toString(), status, status.isCancelable(),
                run.lastSeq(), run.status() == AiRunStatus.COMPLETED ? run.responseDisposition() : null, failure,
                run.createdAt(), run.completedAt());
    }

    /**
     * {@code before}(그 Note의 messageId)보다 오래된 메시지 중 최신 {@code limit}개를 createdAt 오름차순으로.
     * 한 turn의 user·assistant가 페이지 경계로 갈리지 않게, 더 오래된 메시지가 있고 페이지 맨 앞이 assistant면 그 assistant는
     * 다음 페이지로 넘긴다. {@code nextBefore}는 더 오래된 메시지가 있을 때 이 페이지 맨 앞 messageId다.
     *
     * @param before 없으면 최신부터. UUID가 아니면 RUN4001, 이 Note의 메시지가 아니면 RUN4041
     * @param limit  1..100, 없으면 50. 그 밖은 RUN4001
     */
    @Transactional(readOnly = true)
    public ConversationPage conversation(long userId, String noteIdValue, String before, String limit) {
        if (!BrowserRunContract.isNoteId(noteIdValue)) {
            throw new BusinessException(ErrorCode.RUN4041);
        }
        long noteId = Long.parseLong(noteIdValue);
        int pageSize = pageSize(limit);
        if (before != null && !BrowserRunContract.isUuid(before)) {
            throw new BusinessException(ErrorCode.RUN4001);
        }
        Integer owned = jdbc.query("SELECT 1 FROM notes WHERE note_id = ? AND user_id = ? AND status = 'ACTIVE'",
                (rs, row) -> 1, noteId, userId).stream().findFirst().orElse(null);
        if (owned == null) {
            throw new BusinessException(ErrorCode.RUN4041);
        }

        List<MessageRow> newestFirst = messages(noteId, before == null ? null : UUID.fromString(before),
                pageSize + 1);
        boolean older = newestFirst.size() > pageSize;
        List<MessageRow> page = new ArrayList<>(newestFirst.subList(0, Math.min(pageSize, newestFirst.size())));
        if (older && page.size() > 1 && page.get(page.size() - 1).role() == MessageRole.ASSISTANT) {
            page.remove(page.size() - 1);
        }
        Collections.reverse(page);

        List<ConversationMessage> messages = render(noteId, page);
        String nextBefore = older ? page.get(0).messageId().toString() : null;
        return new ConversationPage(String.valueOf(noteId), activeRun(noteId), messages, nextBefore);
    }

    // ------------------------------------------------------------------

    private record MessageRow(UUID messageId, MessageRole role, ConversationMessageStatus status, Instant createdAt,
                              JsonNode userInput, JsonNode responsePages, JsonNode sourceReferences, String plainText,
                              String contentSha256, JsonNode verificationSummary, UUID runId, JsonNode viewerContext,
                              String clarificationReason, String cancelCause) {
    }

    private List<MessageRow> messages(long noteId, UUID before, int limit) {
        String select = """
                SELECT m.message_id, m.role, m.status, m.created_at, m.user_input::text, m.response_pages::text,
                       m.source_references::text, m.plain_text, m.content_sha256, m.verification_summary::text,
                       r.run_id, (r.request_snapshot -> 'viewer_context')::text, r.clarification_reason, r.cancel_cause
                FROM chat_messages m
                JOIN ai_runs r ON r.note_id = m.note_id
                     AND (r.user_message_id = m.message_id OR r.assistant_message_id = m.message_id)
                WHERE m.note_id = ?
                """;
        String order = " ORDER BY m.created_at DESC, m.message_id DESC LIMIT ?";
        if (before == null) {
            return jdbc.query(select + order, this::messageRow, noteId, limit);
        }
        List<OffsetDateTime> anchor = jdbc.queryForList(
                "SELECT created_at FROM chat_messages WHERE note_id = ? AND message_id = ?", OffsetDateTime.class,
                noteId, before);
        if (anchor.isEmpty()) {
            throw new BusinessException(ErrorCode.RUN4041);
        }
        return jdbc.query(select + " AND (m.created_at, m.message_id) < (?, ?)" + order, this::messageRow, noteId,
                anchor.get(0), before, limit);
    }

    private MessageRow messageRow(ResultSet rs, int row) throws SQLException {
        return new MessageRow(rs.getObject(1, UUID.class), MessageRole.valueOf(rs.getString(2)),
                ConversationMessageStatus.valueOf(rs.getString(3)), instant(rs, 4), json(rs.getString(5)),
                json(rs.getString(6)), json(rs.getString(7)), rs.getString(8), rs.getString(9),
                json(rs.getString(10)), rs.getObject(11, UUID.class), json(rs.getString(12)), rs.getString(13),
                rs.getString(14));
    }

    private List<ConversationMessage> render(long noteId, List<MessageRow> page) {
        List<UUID> runIds = page.stream().map(MessageRow::runId).distinct().toList();
        Map<UUID, Map<String, JsonNode>> events = terminalAndCreated(runIds);
        Map<UUID, List<TurnAttachmentView>> attachments = attachments(noteId, page.stream()
                .filter(m -> m.role() == MessageRole.USER).map(MessageRow::messageId).toList());
        LongPredicate available = availability.of(noteId, page.stream()
                .filter(m -> m.status() == ConversationMessageStatus.COMPLETED && m.sourceReferences() != null)
                .map(MessageRow::sourceReferences).toList());

        List<ConversationMessage> messages = new ArrayList<>(page.size());
        for (MessageRow message : page) {
            Map<String, JsonNode> runEvents = events.getOrDefault(message.runId(), Map.of());
            if (message.role() == MessageRole.USER) {
                messages.add(new ConversationMessage(message.messageId().toString(), MessageRole.USER,
                        ConversationMessageStatus.COMPLETED, message.runId().toString(), message.createdAt(),
                        userContent(message, runEvents.get("run.created"),
                                attachments.getOrDefault(message.messageId(), List.of())), null));
            } else {
                messages.add(new ConversationMessage(message.messageId().toString(), MessageRole.ASSISTANT,
                        message.status(), message.runId().toString(), message.createdAt(), null,
                        assistantContent(message, runEvents, available)));
            }
        }
        return messages;
    }

    private UserMessageContent userContent(MessageRow message, JsonNode created,
                                           List<TurnAttachmentView> attachments) {
        // 요청 작업·출력은 run.created payload(접수 값 그대로)에서 읽어 stream과 같은 값을 준다.
        List<RequestedTask> tasks = created == null ? List.of()
                : objectMapper.convertValue(created.get("requestedTasks"), new TypeReference<>() { });
        List<RequestedOutput> outputs = created == null ? List.of()
                : objectMapper.convertValue(created.get("requestedOutputs"), new TypeReference<>() { });
        return new UserMessageContent(message.userInput().get("text").asText(), textOrNull(message.userInput(), "latex"),
                tasks, outputs, attachments, viewerFocus(message.viewerContext()));
    }

    private static ViewerFocus viewerFocus(JsonNode viewer) {
        if (viewer == null || !viewer.isObject()) {
            return null;
        }
        JsonNode selection = viewer.get("selection");
        PageSelection pageSelection = selection == null || selection.isNull() ? null
                : new PageSelection(selection.get("left").asDouble(), selection.get("top").asDouble(),
                selection.get("right").asDouble(), selection.get("bottom").asDouble());
        return new ViewerFocus(viewer.get("asset_id").asText(), viewer.get("page").asInt(), pageSelection);
    }

    private AssistantMessageContent assistantContent(MessageRow message, Map<String, JsonNode> runEvents,
                                                     LongPredicate available) {
        return switch (message.status()) {
            case COMPLETED -> {
                ObjectNode payload = TerminalReplay.completedPayload(runEvents.get("run.completed"),
                        new TerminalReplay.CompletedMessage(message.messageId(), message.responsePages(),
                                message.sourceReferences(), message.plainText(), message.contentSha256(),
                                message.verificationSummary() == null ? JsonNodeFactory.instance.arrayNode()
                                        : message.verificationSummary()), available);
                List<JsonNode> summary = new ArrayList<>();
                payload.get("verificationSummary").forEach(summary::add);
                yield new AssistantMessageContent(payload.get("responseDisposition").asText(),
                        textOrNull(payload, "clarificationReason"), payload.get("assistantResponse"), summary, null,
                        null);
            }
            case FAILED -> {
                JsonNode failed = runEvents.get("run.failed");
                yield new AssistantMessageContent(null, null, null, List.of(),
                        new AssistantFailure(failed.get("code").asText(), textOrNull(failed, "reason"),
                                failed.get("message").asText(), failed.get("recoverable").asBoolean()), null);
            }
            case CANCELED -> new AssistantMessageContent(null, null, null, List.of(), null,
                    AssistantCancel.of(cancelCause(message.cancelCause())));
            case PENDING, STREAMING -> AssistantMessageContent.placeholder();
        };
    }

    private static CancelCause cancelCause(String wire) {
        return Arrays.stream(CancelCause.values()).filter(cause -> cause.wireValue().equals(wire)).findFirst()
                .orElseThrow(() -> new IllegalStateException("canceled run without a known cancel cause"));
    }

    private ActiveRunSummary activeRun(long noteId) {
        return jdbc.query(connection -> {
            var ps = connection.prepareStatement(
                    "SELECT run_id, status, last_event_seq FROM ai_runs WHERE note_id = ? AND status = ANY(?)");
            ps.setLong(1, noteId);
            ps.setArray(2, connection.createArrayOf("varchar", ACTIVE_STATUSES.toArray()));
            return ps;
        }, (rs, row) -> new ActiveRunSummary(rs.getObject(1, UUID.class).toString(),
                PublicRunStatus.from(AiRunStatus.valueOf(rs.getString(2))), rs.getLong(3)))
                .stream().findFirst().orElse(null);
    }

    private Map<UUID, JsonNode> failedPayloads(List<UUID> runIds) {
        Map<UUID, JsonNode> failed = new HashMap<>();
        terminalAndCreated(runIds).forEach((runId, events) -> {
            if (events.containsKey("run.failed")) {
                failed.put(runId, events.get("run.failed"));
            }
        });
        return failed;
    }

    /** run별 {@code run.created}·{@code run.completed}·{@code run.failed} 저장 payload. 각 type은 run당 최대 하나다. */
    private Map<UUID, Map<String, JsonNode>> terminalAndCreated(List<UUID> runIds) {
        Map<UUID, Map<String, JsonNode>> events = new HashMap<>();
        if (runIds.isEmpty()) {
            return events;
        }
        jdbc.query(connection -> {
            var ps = connection.prepareStatement("""
                    SELECT run_id, event_type, payload::text FROM ai_run_events
                    WHERE run_id = ANY(?) AND event_type IN ('run.created', 'run.completed', 'run.failed')
                    """);
            ps.setArray(1, connection.createArrayOf("uuid", runIds.toArray()));
            return ps;
        }, rs -> {
            events.computeIfAbsent(rs.getObject(1, UUID.class), key -> new HashMap<>())
                    .put(rs.getString(2), json(rs.getString(3)));
        });
        return events;
    }

    private Map<UUID, List<TurnAttachmentView>> attachments(long noteId, List<UUID> userMessageIds) {
        Map<UUID, List<TurnAttachmentView>> attachments = new HashMap<>();
        if (userMessageIds.isEmpty()) {
            return attachments;
        }
        Set<UUID> ids = new LinkedHashSet<>(userMessageIds);
        jdbc.query(connection -> {
            var ps = connection.prepareStatement("""
                    SELECT source_message_id, attachment_id, mime_type, display_label FROM turn_attachments
                    WHERE note_id = ? AND source_message_id = ANY(?)
                    ORDER BY source_message_id, attachment_index
                    """);
            ps.setLong(1, noteId);
            ps.setArray(2, connection.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, rs -> {
            attachments.computeIfAbsent(rs.getObject(1, UUID.class), key -> new ArrayList<>())
                    .add(new TurnAttachmentView(rs.getObject(2, UUID.class).toString(), mime(rs.getString(3)),
                            rs.getString(4)));
        });
        return attachments;
    }

    private static TurnAttachmentMime mime(String wire) {
        return Arrays.stream(TurnAttachmentMime.values()).filter(mime -> mime.wireValue().equals(wire)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown turn attachment mime"));
    }

    private static int pageSize(String limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.RUN4001);
        }
        return Integer.parseInt(limit);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored run data is not JSON", e);
        }
    }
}
