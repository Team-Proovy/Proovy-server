package com.proovy.domain.run.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.ExecutionUsage;
import com.proovy.domain.run.brain.InternalRunContractViolation;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.InternalRunWire;
import java.util.List;

/**
 * {@code ai_runs.request_snapshot} 문서 ↔ {@link InternalRunRequest}.
 * <p>
 * 저장 문서는 internal-run-v1 wire(snake_case) 그대로에서 전달마다 바뀌는 control 값만 뺀 것이다:
 * {@code attempt}·{@code fence_token}·{@code asset_access_grant}·{@code viewer_context.read_url}·
 * {@code turn_attachments[].read_url}·{@code execution_options.usage}. 나머지(의미 snapshot, 고정 deadline·limits·
 * budget version)는 그대로 남는다. 복원은 이 문서에 그 전달의 control 값을 붙여 1.76 DTO 생성자로 다시 검증한다.
 */
public final class RunSnapshotCodec {

    static final List<String> CONTROL_FIELDS = List.of("attempt", "fence_token", "asset_access_grant");
    private static final String READ_URL = "read_url";
    private static final String USAGE = "usage";

    private RunSnapshotCodec() {
    }

    /** 저장할 문서. 자격증명·control 값이 없다. */
    public static ObjectNode strip(InternalRunRequest request) {
        ObjectNode document = (ObjectNode) InternalRunWire.tree(request);
        CONTROL_FIELDS.forEach(document::remove);
        if (document.get("viewer_context") instanceof ObjectNode viewer) {
            viewer.remove(READ_URL);
        }
        for (JsonNode attachment : document.withArray("turn_attachments")) {
            ((ObjectNode) attachment).remove(READ_URL);
        }
        ((ObjectNode) document.get("execution_options")).remove(USAGE);
        return document;
    }

    /**
     * 저장 문서 + 이번 전달의 control 값으로 요청을 다시 만든다.
     *
     * @throws RunSnapshotIntegrityException 저장 문서에 control·자격증명 필드가 있거나 계약에 맞지 않거나,
     *                                       현재 첨부의 read URL이 없을 때
     */
    public static InternalRunRequest restore(JsonNode stored, int attempt, String fenceToken, ExecutionUsage usage,
            RunAssetAccess access) {
        if (!(stored instanceof ObjectNode storedObject)) {
            throw new RunSnapshotIntegrityException("request snapshot is not an object");
        }
        ObjectNode document = storedObject.deepCopy();
        requireStripped(document);
        document.put("attempt", attempt);
        document.put("fence_token", fenceToken);
        if (access.assetAccessGrant() != null) {
            document.put("asset_access_grant", access.assetAccessGrant());
        }
        if (document.get("viewer_context") instanceof ObjectNode viewer && access.viewerReadUrl() != null) {
            viewer.put(READ_URL, access.viewerReadUrl());
        }
        for (JsonNode attachment : document.withArray("turn_attachments")) {
            String url = access.turnAttachmentReadUrls().get(attachment.path("attachment_id").asText());
            if (url == null) {
                throw new RunSnapshotIntegrityException("read URL is missing for a current turn attachment");
            }
            ((ObjectNode) attachment).put(READ_URL, url);
        }
        ((ObjectNode) document.get("execution_options")).set(USAGE, InternalRunWire.tree(usage));
        try {
            return InternalRunWire.read(document, InternalRunRequest.class);
        } catch (InternalRunContractViolation e) {
            throw new RunSnapshotIntegrityException("request snapshot does not satisfy internal-run-v1: "
                    + e.getMessage());
        }
    }

    private static void requireStripped(ObjectNode document) {
        boolean leaked = CONTROL_FIELDS.stream().anyMatch(document::has)
                || document.path("viewer_context").has(READ_URL)
                || !(document.get("turn_attachments") instanceof ArrayNode attachments)
                || containsReadUrl(attachments)
                || !(document.get("execution_options") instanceof ObjectNode options)
                || options.has(USAGE);
        if (leaked) {
            throw new RunSnapshotIntegrityException("request snapshot carries control or credential fields");
        }
    }

    private static boolean containsReadUrl(ArrayNode attachments) {
        for (JsonNode attachment : attachments) {
            if (!attachment.isObject() || attachment.has(READ_URL)) {
                return true;
            }
        }
        return false;
    }
}
