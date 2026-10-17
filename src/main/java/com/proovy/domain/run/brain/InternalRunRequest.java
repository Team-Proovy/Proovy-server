package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunContract.CONTRACT_VERSION;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_GRANT_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_HISTORY_MESSAGES;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_HISTORY_TOTAL_CHARS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_TURN_ATTACHMENTS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_TURN_ATTACHMENT_REFS;
import static com.proovy.domain.run.brain.InternalRunContract.MAX_VERIFIED_SOLUTION_REFS;
import static com.proovy.domain.run.brain.InternalRunContract.atLeast;
import static com.proovy.domain.run.brain.InternalRunContract.list;
import static com.proovy.domain.run.brain.InternalRunContract.opaqueId;
import static com.proovy.domain.run.brain.InternalRunContract.optionalText;
import static com.proovy.domain.run.brain.InternalRunContract.require;
import static com.proovy.domain.run.brain.InternalRunContract.unique;
import static com.proovy.domain.run.brain.InternalRunContract.violation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code POST /internal/v1/runs/{run_id}/stream} 본문(Brain {@code InternalRunRequestV1}).
 * <p>
 * 의미 snapshot(input·history·참조·viewer·scope·첨부·실행 상한)과 전달마다 바뀌는 control stamp
 * (attempt·fence_token·asset_access_grant·read_url·deadline·usage)를 함께 담는다. fingerprint는 의미 쪽만 해시한다
 * ({@link InternalRunFingerprint}).
 * <ul>
 *   <li>사용자 JWT·credit·metadata는 표현할 필드가 없다(Brain도 closed model로 거부).</li>
 *   <li>run_id·request_id·attempt·fence는 Server가 발급한다. 이 타입은 새 run을 만들지 않는다.</li>
 *   <li>재전달은 {@link #forRetry}로만 만든다: 같은 run·request, 더 큰 attempt, 고정 deadline, 줄지 않는 usage.</li>
 *   <li>{@link #toString()}은 grant·URL을 쓰지 않는다.</li>
 * </ul>
 */
public record InternalRunRequest(
        String contractVersion,
        String requestId,
        String runId,
        String userId,
        String noteId,
        String conversationId,
        @JsonProperty(required = true) int attempt,
        String fenceToken,
        RunInput input,
        @JsonSetter(nulls = Nulls.FAIL) @JsonProperty(required = true) List<HistoryMessage> history,
        @JsonSetter(nulls = Nulls.FAIL) @JsonProperty(required = true) List<VerifiedSolutionRef> verifiedSolutionRefs,
        @JsonSetter(nulls = Nulls.FAIL) @JsonProperty(required = true) List<TurnAttachmentRef> turnAttachmentRefs,
        ViewerContext viewerContext,
        RetrievalScope retrievalScope,
        @JsonSetter(nulls = Nulls.FAIL) @JsonProperty(required = true) List<TurnAttachment> turnAttachments,
        String assetAccessGrant,
        ExecutionOptions executionOptions) {

    public InternalRunRequest {
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw violation("contract_version", "must be " + CONTRACT_VERSION);
        }
        requestId = opaqueId(requestId, "request_id");
        runId = opaqueId(runId, "run_id");
        userId = opaqueId(userId, "user_id");
        noteId = opaqueId(noteId, "note_id");
        conversationId = opaqueId(conversationId, "conversation_id");
        atLeast(attempt, 1, "attempt");
        fenceToken = InternalRunContract.fenceToken(fenceToken, "fence_token");
        require(input, "input");
        history = list(history, MAX_HISTORY_MESSAGES, "history");
        verifiedSolutionRefs = list(verifiedSolutionRefs, MAX_VERIFIED_SOLUTION_REFS, "verified_solution_refs");
        turnAttachmentRefs = list(turnAttachmentRefs, MAX_TURN_ATTACHMENT_REFS, "turn_attachment_refs");
        require(retrievalScope, "retrieval_scope");
        turnAttachments = list(turnAttachments, MAX_TURN_ATTACHMENTS, "turn_attachments");
        assetAccessGrant = optionalText(assetAccessGrant, "asset_access_grant", 1, MAX_GRANT_CHARS);
        require(executionOptions, "execution_options");

        if (!conversationId.equals(noteId)) {
            throw violation("conversation_id", "must equal note_id in v1");
        }
        if (!retrievalScope.noteId().equals(noteId)) {
            throw violation("retrieval_scope.note_id", "must equal note_id");
        }
        long historyChars = 0;
        for (HistoryMessage message : history) {
            if (message.role() == HistoryRole.USER && message.text().equals(input.text())) {
                throw violation("history", "must not duplicate the current input");
            }
            historyChars += message.text().codePointCount(0, message.text().length());
        }
        if (historyChars > MAX_HISTORY_TOTAL_CHARS) {
            throw violation("history", "exceeds the total character bound");
        }
        unique(history, HistoryMessage::messageId, "history.message_id");
        unique(verifiedSolutionRefs, VerifiedSolutionRef::solutionId, "verified_solution_refs.solution_id");
        unique(turnAttachmentRefs, TurnAttachmentRef::attachmentId, "turn_attachment_refs.attachment_id");
        unique(turnAttachments, TurnAttachment::attachmentId, "turn_attachments.attachment_id");
        Set<String> historyIds = new HashSet<>();
        history.forEach(message -> historyIds.add(message.messageId()));
        for (VerifiedSolutionRef ref : verifiedSolutionRefs) {
            if (!historyIds.contains(ref.sourceMessageId())) {
                throw violation("verified_solution_refs.source_message_id", "must point inside the history");
            }
        }
        for (TurnAttachmentRef ref : turnAttachmentRefs) {
            if (!historyIds.contains(ref.sourceMessageId())) {
                throw violation("turn_attachment_refs.source_message_id", "must point inside the history");
            }
        }
    }

    /** 의미 fingerprint. 같은 run의 모든 재전달에서 같아야 한다. */
    public InternalRunFingerprint fingerprint() {
        return InternalRunFingerprint.of(this);
    }

    /**
     * 같은 run의 다음 attempt 전달. request_id·run_id·의미 snapshot·{@code request_deadline_at}은 그대로이고,
     * attempt는 커져야 하며 누적 usage는 줄 수 없다. grant·read URL은 {@link #withAssetAccess}로 따로 갱신한다.
     */
    public InternalRunRequest forRetry(int nextAttempt, String nextFenceToken, ExecutionUsage accumulatedUsage) {
        if (nextAttempt <= attempt) {
            throw violation("attempt", "must increase across deliveries");
        }
        require(accumulatedUsage, "execution_options.usage").requireNotBelow(executionOptions.usage());
        ExecutionOptions options = new ExecutionOptions(executionOptions.budgetPolicyVersion(),
                executionOptions.requestDeadlineAt(), executionOptions.limits(), accumulatedUsage);
        return new InternalRunRequest(contractVersion, requestId, runId, userId, noteId, conversationId, nextAttempt,
                nextFenceToken, input, history, verifiedSolutionRefs, turnAttachmentRefs, viewerContext,
                retrievalScope, turnAttachments, assetAccessGrant, options);
    }

    /**
     * 실행 자격증명만 바꾼 사본(grant와 짧은 read URL 재발급). 의미 필드가 그대로라 fingerprint가 같다.
     * 모든 인자는 "주어진 것만 바꾸고 나머지는 유지"다. 자격증명을 지우는 경로는 없다.
     *
     * @param grant                  새 Asset Access Grant. null이면 기존 값을 유지한다
     * @param viewerReadUrl          viewer_context의 새 read URL. null이면 기존 값을 유지한다(viewer_context가 없으면 무시)
     * @param turnAttachmentReadUrls attachment_id → 새 read URL. null이거나 없는 id는 기존 URL을 유지한다
     */
    public InternalRunRequest withAssetAccess(String grant, String viewerReadUrl,
            Map<String, String> turnAttachmentReadUrls) {
        ViewerContext viewer = viewerContext == null || viewerReadUrl == null
                ? viewerContext : viewerContext.withReadUrl(viewerReadUrl);
        Map<String, String> urls = turnAttachmentReadUrls == null ? Map.of() : turnAttachmentReadUrls;
        List<TurnAttachment> attachments = turnAttachments.stream()
                .map(item -> item.withReadUrl(urls.getOrDefault(item.attachmentId(), item.readUrl())))
                .toList();
        return new InternalRunRequest(contractVersion, requestId, runId, userId, noteId, conversationId, attempt,
                fenceToken, input, history, verifiedSolutionRefs, turnAttachmentRefs, viewer, retrievalScope,
                attachments, grant == null ? assetAccessGrant : grant, executionOptions);
    }

    @Override
    public String toString() {
        return "InternalRunRequest[requestId=" + requestId + ", runId=" + runId + ", attempt=" + attempt
                + ", assetAccessGrant=" + (assetAccessGrant == null ? "absent" : "<redacted>") + "]";
    }
}
