package com.proovy.domain.run.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.global.response.ErrorCode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * POST /api/conversations body의 wire 판정(browser-run-v1 schema + 계약 검사).
 * <p>
 * Jackson 바인딩 전에 JSON 트리를 직접 검사한다 — 요청은 closed이고 타입 강제 변환(숫자 noteId 등)을
 * 허용하지 않는다. 구조 위반은 모두 RUN4001이고, 구조가 맞을 때만 video 출력이 RUN4002가 된다.
 * Note·참조·Active Run·rate limit·잔액은 이 판정 뒤 Server 상태로 판정한다.
 */
public final class ConversationRunRequestValidator {

    private static final Set<String> FIELDS = Set.of(
            "requestId", "noteId", "text", "latex",
            "requestedTasks", "requestedOutputs", "turnAttachmentIds", "viewerFocus");
    private static final Set<String> VIEWER_FOCUS_FIELDS = Set.of("noteAssetId", "page", "selection");
    private static final Set<String> SELECTION_FIELDS = Set.of("left", "top", "right", "bottom");
    private static final Set<String> TASKS = Set.of("solve", "check", "explain");
    private static final Set<String> OUTPUTS = Set.of("text", "video");

    private ConversationRunRequestValidator() {
    }

    /** 접수 가능한 body면 empty, 아니면 RUN4001 또는 RUN4002. */
    public static Optional<ErrorCode> validate(JsonNode body) {
        if (!isWellFormed(body)) {
            return Optional.of(ErrorCode.RUN4001);
        }
        JsonNode outputs = body.get("requestedOutputs");
        if (outputs != null) {
            for (JsonNode output : outputs) {
                if (RequestedOutput.VIDEO.wireValue().equals(output.textValue())) {
                    return Optional.of(ErrorCode.RUN4002);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isWellFormed(JsonNode body) {
        if (body == null || !body.isObject() || !hasOnly(body, FIELDS)) {
            return false;
        }
        JsonNode requestId = body.get("requestId");
        if (requestId == null || !requestId.isTextual() || !BrowserRunContract.isUuid(requestId.textValue())) {
            return false;
        }
        JsonNode noteId = body.get("noteId");
        if (noteId != null && !(noteId.isTextual() && BrowserRunContract.isNoteId(noteId.textValue()))) {
            return false;
        }
        JsonNode text = body.get("text");
        if (text == null || !isText(text, BrowserRunContract.TEXT_MAX_CODE_POINTS)) {
            return false;
        }
        JsonNode latex = body.get("latex");
        if (latex != null && !latex.isNull() && !isText(latex, BrowserRunContract.LATEX_MAX_CODE_POINTS)) {
            return false;
        }
        return isUniqueArray(body.get("requestedTasks"), BrowserRunContract.REQUESTED_TASKS_MAX,
                item -> item.isTextual() && TASKS.contains(item.textValue()))
                && isUniqueArray(body.get("requestedOutputs"), BrowserRunContract.REQUESTED_OUTPUTS_MAX,
                item -> item.isTextual() && OUTPUTS.contains(item.textValue()))
                && isUniqueArray(body.get("turnAttachmentIds"), BrowserRunContract.TURN_ATTACHMENT_IDS_WIRE_MAX,
                item -> item.isTextual() && BrowserRunContract.isUuid(item.textValue()))
                && isViewerFocus(body.get("viewerFocus"));
    }

    /** 비어 있지 않고, strip 집합 공백만이 아니며, strip 후 code point 수가 상한 이하. */
    private static boolean isText(JsonNode node, int maxCodePoints) {
        if (!node.isTextual()) {
            return false;
        }
        String stripped = BrowserRunContract.strip(node.textValue());
        return !stripped.isEmpty() && BrowserRunContract.codePointLength(stripped) <= maxCodePoints;
    }

    private static boolean isUniqueArray(JsonNode node, int maxItems, Predicate<JsonNode> item) {
        if (node == null) {
            return true;
        }
        if (!node.isArray() || node.size() > maxItems) {
            return false;
        }
        Set<JsonNode> seen = new HashSet<>();
        for (JsonNode element : node) {
            if (!item.test(element) || !seen.add(element)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isViewerFocus(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (!node.isObject() || !hasOnly(node, VIEWER_FOCUS_FIELDS)) {
            return false;
        }
        JsonNode assetId = node.get("noteAssetId");
        if (assetId == null || !assetId.isTextual() || !BrowserRunContract.isOpaqueId(assetId.textValue())) {
            return false;
        }
        JsonNode page = node.get("page");
        if (page == null || !page.isIntegralNumber() || !page.canConvertToInt() || page.intValue() < 1) {
            return false;
        }
        return isSelection(node.get("selection"));
    }

    private static boolean isSelection(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (!node.isObject() || !hasOnly(node, SELECTION_FIELDS) || node.size() != SELECTION_FIELDS.size()) {
            return false;
        }
        for (String field : SELECTION_FIELDS) {
            if (!node.get(field).isNumber()) {
                return false;
            }
        }
        double left = node.get("left").doubleValue();
        double top = node.get("top").doubleValue();
        double right = node.get("right").doubleValue();
        double bottom = node.get("bottom").doubleValue();
        boolean inRange = left >= 0 && left < 1 && top >= 0 && top < 1
                && right > 0 && right <= 1 && bottom > 0 && bottom <= 1;
        // left<right·top<bottom은 schema 밖의 계약 검사지만 같은 RUN4001이다.
        return inRange && left < right && top < bottom;
    }

    private static boolean hasOnly(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }
}
