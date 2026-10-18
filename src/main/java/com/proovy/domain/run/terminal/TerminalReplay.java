package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.entity.ClarificationReason;
import com.proovy.domain.run.entity.ResponseDisposition;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * commit된 terminal 결과를 다시 읽어 browser payload로 만든다(1.86 replay·대화 조회). 방송 신호가 유실되어도 저장 event와
 * canonical message만으로 같은 응답이 복원된다.
 * <p>
 * 저장 형태: {@code run.completed} event payload는 본문 없는 최소 요약
 * {@code {assistantMessageId, responseDisposition, clarificationReason?, model, tier}}이고, 본문은 assistant message의
 * {@code response_pages}·{@code source_references}·{@code plain_text}·{@code verification_summary}(snake_case canonical)에 있다.
 * page 경계·{@code run.failed} payload는 이미 browser frame 모양이라 그대로 쓴다.
 * <p>
 * {@code sourceReferences[].available}은 canonical 값이 아니라 read-time 제품 상태다 — 호출자가 asset 상태로 판정해 넘긴다
 * (DELETING·DELETED면 false). asset rename·삭제는 저장된 label snapshot·content hash를 바꾸지 않는다.
 */
@Component
public class TerminalReplay {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TerminalReplay(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 완료 assistant message의 canonical 본문(snake_case). {@code verificationSummary}는 없으면 빈 배열이다. */
    public record CompletedMessage(UUID messageId, JsonNode responsePages, JsonNode sourceReferences, String plainText,
                                   String contentSha256, JsonNode verificationSummary) {
    }

    /** run의 assistant message가 COMPLETED면 그 본문. 아직 placeholder·실패·취소면 empty(공개할 본문이 없다). */
    @Transactional(readOnly = true)
    public Optional<CompletedMessage> findCompleted(UUID runId) {
        return jdbc.query("""
                SELECT m.message_id, m.response_pages::text, m.source_references::text, m.plain_text, m.content_sha256,
                       m.verification_summary::text
                FROM ai_runs r JOIN chat_messages m ON m.message_id = r.assistant_message_id AND m.note_id = r.note_id
                WHERE r.run_id = ? AND m.status = 'COMPLETED'
                """, (rs, row) -> new CompletedMessage(rs.getObject(1, UUID.class), read(rs.getString(2)),
                read(rs.getString(3)), rs.getString(4), rs.getString(5),
                rs.getString(6) == null ? JsonNodeFactory.instance.arrayNode() : read(rs.getString(6))), runId)
                .stream().findFirst();
    }

    /**
     * 저장된 {@code run.completed} 최소 요약과 canonical message로 browser-run-v1 {@code run.completed} payload를 만든다
     * (terminal-v2 pair의 browser payload와 같은 모양).
     *
     * @param available Note Asset ID별 지금 사용 가능 여부(read-time)
     */
    public static ObjectNode completedPayload(JsonNode storedSummary, CompletedMessage message,
                                              LongPredicate available) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set("responseDisposition", storedSummary.get("responseDisposition"));
        if (storedSummary.hasNonNull("clarificationReason")) {
            payload.set("clarificationReason", storedSummary.get("clarificationReason"));
        }
        ObjectNode response = payload.putObject("assistantResponse");
        response.set("responsePages", camel(message.responsePages()));
        ArrayNode references = response.putArray("sourceReferences");
        for (JsonNode reference : message.sourceReferences()) {
            long assetId = reference.get("asset_id").asLong();
            ObjectNode browser = (ObjectNode) camel(reference);
            // browser noteAssetId는 opaque 문자열이다(browser-run-v1).
            browser.put("assetId", String.valueOf(assetId));
            browser.put("available", available.test(assetId));
            references.add(browser);
        }
        response.put("plainText", message.plainText());
        payload.set("model", storedSummary.get("model"));
        payload.set("tier", storedSummary.get("tier"));
        payload.set("verificationSummary", camel(message.verificationSummary()));
        payload.set("assistantMessageId", storedSummary.get("assistantMessageId"));
        return payload;
    }

    /** terminal transaction이 저장하는 {@code run.completed} 최소 요약. */
    static ObjectNode completedSummary(String assistantMessageId, ResponseDisposition disposition,
                                       ClarificationReason clarificationReason, String model, String tier) {
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("assistantMessageId", assistantMessageId);
        summary.put("responseDisposition", disposition.wireValue());
        if (clarificationReason != null) {
            summary.put("clarificationReason", clarificationReason.wireValue());
        }
        summary.put("model", model);
        summary.put("tier", tier);
        return summary;
    }

    /** snake_case key를 camelCase로(Brain browser alias와 같다). 값은 그대로 둔다. */
    static JsonNode camel(JsonNode node) {
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(camel(item)));
            return out;
        }
        if (!node.isObject()) {
            return node;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            out.set(camelKey(field.getKey()), camel(field.getValue()));
        }
        return out;
    }

    private static String camelKey(String key) {
        StringBuilder out = new StringBuilder(key.length());
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored assistant message is not JSON", e);
        }
    }
}
