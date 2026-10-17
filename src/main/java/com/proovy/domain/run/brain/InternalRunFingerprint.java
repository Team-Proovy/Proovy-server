package com.proovy.domain.run.brain;

import com.proovy.domain.run.contract.CanonicalJson;
import com.proovy.domain.run.contract.RequestedOutput;
import com.proovy.domain.run.contract.RequestedTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * internal-run-v1 요청 fingerprint ({@value InternalRunContract#FINGERPRINT_ALGORITHM}).
 * Brain {@code request_fingerprint_document}와 같은 projection을 {@link CanonicalJson}으로 직렬화해 sha256한다.
 * <ul>
 *   <li>포함: contract_version·user/note/conversation id·input·history(message_id + sha256(role,text))·
 *       verified_solution_refs(solution_id + sha256(problem_summary, claims))·turn_attachment_refs·viewer_context·
 *       retrieval_scope·turn_attachments·execution(budget_policy_version·limits)</li>
 *   <li>제외: request_id·run_id·attempt·fence_token·asset_access_grant·read_url·request_deadline_at·usage,
 *       수명주기(status·revises_solution_id·참조의 source_message_id)와 표현 사실(mime·byte/page/pixel·
 *       upload/normalization version·display_label)</li>
 *   <li>비순서 컬렉션은 식별자로 정렬하고 시간 순서인 history는 순서를 지킨다.</li>
 * </ul>
 * 문자열은 record 생성 때 이미 strip되어 있다.
 *
 * @param canonical canonical JSON 문서
 * @param sha256    canonical UTF-8 바이트의 sha256 소문자 hex. Brain status 응답의 {@code request_fingerprint}와 비교한다.
 */
public record InternalRunFingerprint(String canonical, String sha256) {

    public static InternalRunFingerprint of(InternalRunRequest request) {
        String canonical = CanonicalJson.write(document(request));
        return new InternalRunFingerprint(canonical, sha256Hex(canonical));
    }

    static Map<String, Object> document(InternalRunRequest request) {
        RunInput input = request.input();
        Map<String, Object> inputDocument = new LinkedHashMap<>();
        inputDocument.put("text", input.text());
        inputDocument.put("latex", input.latex());
        inputDocument.put("requested_tasks", input.requestedTasks().stream().map(RequestedTask::wireValue).sorted()
                .toList());
        inputDocument.put("requested_outputs", input.requestedOutputs().stream().map(RequestedOutput::wireValue)
                .sorted().toList());
        inputDocument.put("locale", input.locale());

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("contract_version", request.contractVersion());
        document.put("user_id", request.userId());
        document.put("note_id", request.noteId());
        document.put("conversation_id", request.conversationId());
        document.put("input", inputDocument);
        document.put("history", request.history().stream()
                .map(message -> Map.of(
                        "message_id", message.messageId(),
                        "content_sha256", contentSha256(Map.of(
                                "role", message.role().wireValue(),
                                "text", message.text()))))
                .toList());
        document.put("verified_solution_refs", sorted(request.verifiedSolutionRefs(), VerifiedSolutionRef::solutionId)
                .stream()
                .map(ref -> Map.of(
                        "solution_id", ref.solutionId(),
                        "content_sha256", contentSha256(Map.of(
                                "problem_summary", ref.problemSummary(),
                                "claims", sorted(ref.claims(), VerifiedSolutionRefClaim::claimId).stream()
                                        .map(claim -> Map.of(
                                                "claim_id", claim.claimId(),
                                                "statement", claim.statement(),
                                                "coverage", claim.coverage().wireValue()))
                                        .toList()))))
                .toList());
        document.put("turn_attachment_refs", sorted(request.turnAttachmentRefs(), TurnAttachmentRef::attachmentId)
                .stream()
                .map(ref -> Map.of(
                        "attachment_id", ref.attachmentId(),
                        "source_message_id", ref.sourceMessageId(),
                        "sha256", ref.sha256(),
                        "page_understanding_version", ref.pageUnderstandingVersion()))
                .toList());
        document.put("turn_attachments", sorted(request.turnAttachments(), TurnAttachment::attachmentId).stream()
                .map(item -> Map.of(
                        "attachment_id", item.attachmentId(),
                        "sha256", item.sha256(),
                        "page_understanding_version", item.pageUnderstandingVersion()))
                .toList());
        RetrievalScope scope = request.retrievalScope();
        document.put("retrieval_scope", Map.of(
                "note_id", scope.noteId(),
                "corpus_version", scope.corpusVersion(),
                "search_extraction_version", scope.searchExtractionVersion(),
                "chunking_version", scope.chunkingVersion(),
                "embedding_version", scope.embeddingVersion()));
        ExecutionLimits limits = request.executionOptions().limits();
        Map<String, Object> limitsDocument = new LinkedHashMap<>();
        limitsDocument.put("max_llm_calls", limits.maxLlmCalls());
        limitsDocument.put("max_tool_calls", limits.maxToolCalls());
        limitsDocument.put("max_output_tokens", limits.maxOutputTokens());
        limitsDocument.put("max_cost_micros", limits.maxCostMicros());
        limitsDocument.put("max_page_understandings", limits.maxPageUnderstandings());
        limitsDocument.put("max_problems", limits.maxProblems());
        limitsDocument.put("max_claims", limits.maxClaims());
        document.put("execution", Map.of(
                "budget_policy_version", request.executionOptions().budgetPolicyVersion(),
                "limits", limitsDocument));
        ViewerContext viewer = request.viewerContext();
        if (viewer != null) {
            Map<String, Object> viewerDocument = new LinkedHashMap<>();
            viewerDocument.put("asset_id", viewer.assetId());
            viewerDocument.put("page", viewer.page());
            viewerDocument.put("sha256", viewer.sha256());
            viewerDocument.put("page_understanding_version", viewer.pageUnderstandingVersion());
            ViewerSelection selection = viewer.selection();
            if (selection != null) {
                viewerDocument.put("selection", Map.of(
                        "left", selection.left(),
                        "top", selection.top(),
                        "right", selection.right(),
                        "bottom", selection.bottom()));
            }
            document.put("viewer_context", viewerDocument);
        }
        return document;
    }

    private static <T> List<T> sorted(List<T> values, Function<T, String> identity) {
        List<T> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(identity));
        return copy;
    }

    private static String contentSha256(Object document) {
        return sha256Hex(CanonicalJson.write(document));
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
