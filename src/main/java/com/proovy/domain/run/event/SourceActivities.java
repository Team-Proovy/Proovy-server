package com.proovy.domain.run.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.brain.SourceEventType;
import com.proovy.domain.run.contract.BrowserRunContract;
import com.proovy.domain.run.entity.AiRunEventType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Brain source Activity({@code activity.*})를 activity-v1 규칙으로 검사하고 durable browser payload로 바꾼다.
 * <p>
 * 원본 규칙은 proovy-brain {@code contracts/activity.py}·{@code contracts/source_progress.py}(pin된 commit)이고,
 * 값은 pin된 activity-v1 fixture로 대조한다.
 * <ul>
 *   <li>payload는 (type, kind)마다 닫혀 있다. 알 수 없는 필드(code·stdout·step 판정·percent 등)는 거부한다.</li>
 *   <li>문자열은 Python {@code str.strip()} 집합으로 양끝을 지운 값을 검사·저장한다(pydantic {@code str_strip_whitespace}).
 *       길이는 code point 수다. enum·kind는 정확히 일치해야 한다.</li>
 *   <li>정수는 JSON 정수만 받는다(StrictInt). 진행량은 측정값만 있고 추정 퍼센트 필드는 없다. Python과 달리 long 범위
 *       (2^63) 밖 정수는 거부한다(fail-closed, 브라우저도 2^53 밖을 안전하게 다루지 못한다).</li>
 *   <li>결과는 Brain {@code to_browser_event_payload}와 같은 camelCase payload다. null 필드는 빼고, note_retrieval 완료의
 *       {@code pages}는 없으면 빈 배열로 채운다.</li>
 *   <li>오류 message에는 필드 위치와 위반 종류만 쓰고 입력 값·알 수 없는 key 이름은 쓰지 않는다.</li>
 * </ul>
 * 의미 판단(답이 문장에 섞였는지)은 schema로 강제할 수 없고 Brain 공개 정책의 몫이다. 여기서는 구조와 안전한 문구 형식만 지킨다.
 */
public final class SourceActivities {

    /** Server가 맞춰 둔 activity fixture 소비 버전(pin {@code PIN-activity-v1.json}). pin을 올리면 함께 올린다. */
    public static final String CONSUMER_VERSION = "activity-fixtures-v1";
    public static final int MAX_IDENTIFIER_LENGTH = 128;
    public static final int MAX_MESSAGE_LENGTH = 500;
    public static final int MAX_METHOD_LABEL_LENGTH = 200;
    public static final int MAX_TARGETS = 50;
    public static final int MAX_PAGES = 200;
    /** {@code ck_ai_run_events_payload}: {@code octet_length(payload::TEXT) <= 16384}. */
    public static final int MAX_STORED_PAYLOAD_BYTES = 16_384;
    /** {@code ck_ai_run_events_source_event_id}는 128자다. brain-source-v1은 256자까지 허용한다. */
    public static final int MAX_STORED_SOURCE_EVENT_ID_LENGTH = 128;
    private static final String DIGEST_PREFIX = "sha256:";

    private static final Pattern ACTIVITY_ID = neutral("activity");
    private static final Pattern PAGE_ID = neutral("page");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f]");
    // Python re.IGNORECASE(str 패턴)는 Unicode case folding을 쓴다. UNICODE_CASE 없이 두면 U+212A(Kelvin) 같은 값이 빠진다.
    private static final Pattern URL_LIKE = Pattern.compile("(?:[a-z][a-z0-9+.-]*://|www\\.)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CODE_FENCE = Pattern.compile("`{3,}|~{3,}");
    private static final String TRACEBACK_MARKER = "Traceback (most recent call last)";

    private static final Set<String> STATUSES = Set.of("in_progress", "waiting", "retrying");
    private static final Set<String> FAILURE_CODES = Set.of("provider_error", "timeout", "storage_error",
            "internal_error");
    private static final Set<String> UNDERSTANDING_REASONS = Set.of("viewer_focus", "turn_attachment",
            "retrieval_candidates", "page_range_summary");
    private static final Set<String> UNDERSTANDING_OUTCOMES = Set.of("scope_required", "unreadable_pages");
    private static final String LIMITED_PAGES_UNRESOLVED = "limited_pages_unresolved";
    private static final String NO_RELEVANT_CONTENT = "no_relevant_content";
    private static final Set<String> MATCH_STATUSES = Set.of("matched", NO_RELEVANT_CONTENT, "conflicting_content",
            LIMITED_PAGES_UNRESOLVED);

    private SourceActivities() {
    }

    private static Pattern neutral(String prefix) {
        return Pattern.compile(prefix + "-(?:[1-9][0-9]*|[0-9a-f]{20}-[0-9]{3})");
    }

    /**
     * source event 하나를 durable Activity로 바꾼다. envelope는 {@link SourceEvent} 생성자가 이미 검사했다.
     *
     * @throws ActivityContractViolation activity 타입이 아니거나 run_id가 Server run UUID가 아니거나 payload가 계약을 어겼다
     */
    public static DurableActivity toDurable(SourceEvent event) {
        if (!BrowserRunContract.isUuid(event.runId())) {
            throw violation("run_id", "is not a server run id");
        }
        AiRunEventType type = eventType(event.type());
        ObjectNode payload = browserPayload(event.type(), event.payload());
        if (storedLength(payload) > MAX_STORED_PAYLOAD_BYTES) {
            throw violation("payload", "is too large to store");
        }
        return new DurableActivity(UUID.fromString(event.runId()), event.attempt(), event.fenceToken(),
                storedSourceEventId(event.sourceEventId()), type, ActivityKind.of(payload.get("kind").textValue()).orElseThrow(),
                payload.get("activityId").textValue(), payload,
                BrowserRunContract.wireTime(event.occurredAt().toInstant()));
    }

    /**
     * dedup 키로 저장할 source_event_id. 128자 이하는 그대로, 더 길면 같은 입력에 항상 같은 {@code sha256:<hex>}(71자)로
     * 줄인다. Brain ID는 run_id(UUID)로 시작하므로 이 prefix와 겹치지 않는다.
     */
    static String storedSourceEventId(String sourceEventId) {
        if (sourceEventId.length() <= MAX_STORED_SOURCE_EVENT_ID_LENGTH) {
            return sourceEventId;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sourceEventId.getBytes(StandardCharsets.UTF_8));
            return DIGEST_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** source payload(snake_case)를 검사해 browser payload(camelCase)로 바꾼다. */
    public static ObjectNode browserPayload(SourceEventType type, JsonNode source) {
        AiRunEventType eventType = eventType(type);
        Reader in = new Reader(source, "payload");
        ActivityKind kind = in.kind();
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("activityId", in.identifier("activity_id", ACTIVITY_ID, MAX_IDENTIFIER_LENGTH, true));
        out.put("kind", kind.wireValue());
        switch (eventType) {
            case ACTIVITY_STARTED -> started(in, kind, out);
            case ACTIVITY_UPDATED -> updated(in, kind, out);
            case ACTIVITY_COMPLETED -> completed(in, kind, out);
            case ACTIVITY_FAILED -> failed(in, kind, out);
            case ACTIVITY_CANCELED -> canceled(in, kind, out);
            default -> throw new IllegalStateException();
        }
        in.closed();
        return out;
    }

    private static AiRunEventType eventType(SourceEventType type) {
        return switch (type) {
            case ACTIVITY_STARTED -> AiRunEventType.ACTIVITY_STARTED;
            case ACTIVITY_UPDATED -> AiRunEventType.ACTIVITY_UPDATED;
            case ACTIVITY_COMPLETED -> AiRunEventType.ACTIVITY_COMPLETED;
            case ACTIVITY_FAILED -> AiRunEventType.ACTIVITY_FAILED;
            case ACTIVITY_CANCELED -> AiRunEventType.ACTIVITY_CANCELED;
            // run.completed·run.failed는 terminal transaction(1.81·1.82)만 저장한다.
            case RUN_COMPLETED, RUN_FAILED -> throw violation("type", "is not an activity event");
        };
    }

    // ------------------------------------------------------------------
    // phase별 payload
    // ------------------------------------------------------------------

    private static void started(Reader in, ActivityKind kind, ObjectNode out) {
        putIfPresent(out, "responsePageId", in.identifier("response_page_id", PAGE_ID, Integer.MAX_VALUE, false));
        String label = in.string("label", true);
        if (!kind.allowsLabel(label)) {
            throw violation("payload.label", "is not allowed for this kind");
        }
        out.put("label", label);
        if (kind == ActivityKind.ASSET_UNDERSTANDING) {
            out.put("reason", in.member("reason", UNDERSTANDING_REASONS, true));
            out.set("targets", targets(in));
        }
        if (kind.hasMethodLabel()) {
            out.put("methodLabel", in.methodLabel());
        }
    }

    private static void updated(Reader in, ActivityKind kind, ObjectNode out) {
        putIfPresent(out, "status", in.member("status", STATUSES, false));
        putIfPresent(out, "message", in.message());
        switch (kind) {
            case ASSET_UNDERSTANDING -> pageCounts(in, out);
            case CODE_EXECUTION -> out.put("methodLabel", in.methodLabel());
            case VERIFICATION -> {
                out.put("methodLabel", in.methodLabel());
                out.set("progress", progress(in));
            }
            default -> {
            }
        }
    }

    private static void completed(Reader in, ActivityKind kind, ObjectNode out) {
        putIfPresent(out, "message", in.message());
        switch (kind) {
            case ASSET_UNDERSTANDING -> {
                pageCounts(in, out);
                putIfPresent(out, "outcome", in.member("outcome", UNDERSTANDING_OUTCOMES, false));
            }
            case NOTE_RETRIEVAL -> retrievalResult(in, out);
            case CODE_EXECUTION -> {
                out.put("methodLabel", in.methodLabel());
                Long duration = in.integer("duration_ms", 0, false);
                if (duration != null) {
                    out.set("durationMs", number(duration));
                }
            }
            case VERIFICATION -> {
                out.put("methodLabel", in.methodLabel());
                out.set("progress", progress(in));
            }
            default -> {
            }
        }
    }

    private static void failed(Reader in, ActivityKind kind, ObjectNode out) {
        out.put("code", in.member("code", FAILURE_CODES, true));
        putIfPresent(out, "message", in.message());
        if (kind.hasMethodLabel()) {
            out.put("methodLabel", in.methodLabel());
        }
    }

    private static void canceled(Reader in, ActivityKind kind, ObjectNode out) {
        putIfPresent(out, "message", in.message());
        if (kind.hasMethodLabel()) {
            out.put("methodLabel", in.methodLabel());
        }
    }

    // ------------------------------------------------------------------
    // kind별 구조
    // ------------------------------------------------------------------

    /** 1..50개, 같은 target 중복 금지, 같은 asset의 page 범위는 겹칠 수 없다. */
    private static ArrayNode targets(Reader in) {
        JsonNode raw = in.array("targets", true);
        if (raw.isEmpty() || raw.size() > MAX_TARGETS) {
            throw violation("payload.targets", "has an invalid number of entries");
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        Set<String> seen = new HashSet<>();
        Map<String, List<long[]>> rangesByAsset = new HashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            Reader target = new Reader(raw.get(i), "payload.targets[" + i + "]");
            String assetId = target.opaqueId("asset_id");
            long start = target.integer("page_start", 1, true);
            long end = target.integer("page_end", 1, true);
            target.closed();
            if (start > end) {
                throw violation(target.path, "is a reversed page range");
            }
            if (!seen.add(assetId + ":" + start + "-" + end)) {
                throw violation("payload.targets", "must be unique");
            }
            rangesByAsset.computeIfAbsent(assetId, key -> new ArrayList<>()).add(new long[]{start, end});
            ObjectNode item = out.addObject().put("assetId", assetId);
            item.set("pageStart", number(start));
            item.set("pageEnd", number(end));
        }
        for (List<long[]> ranges : rangesByAsset.values()) {
            ranges.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
            for (int i = 1; i < ranges.size(); i++) {
                if (ranges.get(i)[0] <= ranges.get(i - 1)[1]) {
                    throw violation("payload.targets", "must not overlap within an asset");
                }
            }
        }
        return out;
    }

    /** 측정된 processed_pages ≤ total_pages. */
    private static void pageCounts(Reader in, ObjectNode out) {
        long processed = in.integer("processed_pages", 0, true);
        long total = in.integer("total_pages", 1, true);
        if (processed > total) {
            throw violation("payload.processed_pages", "cannot exceed total_pages");
        }
        out.set("processedPages", number(processed));
        out.set("totalPages", number(total));
    }

    /** 검증 라운드의 측정 진행량: checked ≤ total. 단계 내용·판정 필드는 없다. */
    private static ObjectNode progress(Reader in) {
        Reader progress = new Reader(in.required("progress"), "payload.progress");
        long checked = progress.integer("checked", 0, true);
        long total = progress.integer("total", 1, true);
        progress.closed();
        if (checked > total) {
            throw violation("payload.progress.checked", "cannot exceed total");
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("checked", number(checked));
        out.set("total", number(total));
        return out;
    }

    /** limited_pages_unresolved는 page를 반드시, no_relevant_content는 page를 하나도 갖지 않는다. */
    private static void retrievalResult(Reader in, ObjectNode out) {
        String matchStatus = in.member("match_status", MATCH_STATUSES, true);
        JsonNode raw = in.array("pages", false);
        ArrayNode pages = JsonNodeFactory.instance.arrayNode();
        if (raw != null) {
            if (raw.size() > MAX_PAGES) {
                throw violation("payload.pages", "has too many entries");
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < raw.size(); i++) {
                Reader page = new Reader(raw.get(i), "payload.pages[" + i + "]");
                String assetId = page.opaqueId("asset_id");
                long pageNumber = page.integer("page", 1, true);
                page.closed();
                if (!seen.add(assetId + ":" + pageNumber)) {
                    throw violation("payload.pages", "must be unique");
                }
                pages.addObject().put("assetId", assetId).set("page", number(pageNumber));
            }
        }
        if (LIMITED_PAGES_UNRESOLVED.equals(matchStatus) && pages.isEmpty()) {
            throw violation("payload.pages", "must name the unresolved pages");
        }
        if (NO_RELEVANT_CONTENT.equals(matchStatus) && !pages.isEmpty()) {
            throw violation("payload.pages", "must be empty when nothing matched");
        }
        out.put("matchStatus", matchStatus);
        out.set("pages", pages);
    }

    /** 파싱한 JSON tree와 같은 node 종류(int 범위면 IntNode)로 둔다. */
    private static JsonNode number(long value) {
        return value == (int) value
                ? JsonNodeFactory.instance.numberNode((int) value)
                : JsonNodeFactory.instance.numberNode(value);
    }

    private static void putIfPresent(ObjectNode out, String field, String value) {
        if (value != null) {
            out.put(field, value);
        }
    }

    // ------------------------------------------------------------------
    // 저장 크기
    // ------------------------------------------------------------------

    /**
     * Postgres {@code jsonb::text} 표현의 UTF-8 byte 길이({@code ", "}·{@code ": "} 구분자, 문자열 escape).
     * DB CHECK에 걸리기 전에 같은 기준으로 거부해 seq를 배정하지 않는다.
     */
    static long storedLength(JsonNode node) {
        if (node.isObject()) {
            long length = 2;
            int index = 0;
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); index++) {
                Map.Entry<String, JsonNode> entry = it.next();
                length += (index > 0 ? 2 : 0) + quotedLength(entry.getKey()) + 2 + storedLength(entry.getValue());
            }
            return length;
        }
        if (node.isArray()) {
            long length = 2;
            for (int i = 0; i < node.size(); i++) {
                length += (i > 0 ? 2 : 0) + storedLength(node.get(i));
            }
            return length;
        }
        if (node.isTextual()) {
            return quotedLength(node.textValue());
        }
        return node.asText().getBytes(StandardCharsets.UTF_8).length;
    }

    private static long quotedLength(String value) {
        long length = 2;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '"' || codePoint == '\\' || codePoint == '\b' || codePoint == '\f'
                    || codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                length += 2;
            } else if (codePoint < 0x20) {
                length += 6;
            } else {
                length += new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return length;
    }

    static ActivityContractViolation violation(String field, String problem) {
        return new ActivityContractViolation(field + " " + problem);
    }

    // ------------------------------------------------------------------
    // 닫힌 object 읽기
    // ------------------------------------------------------------------

    /** 읽은 필드 이름을 기록하고 {@link #closed()}에서 나머지를 알 수 없는 필드로 거부한다. */
    private static final class Reader {

        private final JsonNode node;
        private final String path;
        private final Set<String> known = new HashSet<>();

        Reader(JsonNode node, String path) {
            if (node == null || !node.isObject()) {
                throw violation(path, "must be an object");
            }
            this.node = node;
            this.path = path;
        }

        private String at(String field) {
            return path + "." + field;
        }

        /** 없거나 null이면 null. required면 거부한다(Python {@code X | None = None}과 필수 필드의 차이). */
        private JsonNode value(String field, boolean required) {
            known.add(field);
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                if (required) {
                    throw violation(at(field), "is required");
                }
                return null;
            }
            return value;
        }

        JsonNode required(String field) {
            return value(field, true);
        }

        ActivityKind kind() {
            JsonNode value = required("kind");
            if (!value.isTextual()) {
                throw violation(at("kind"), "is not a known activity kind");
            }
            return ActivityKind.of(value.textValue())
                    .orElseThrow(() -> violation(at("kind"), "is not a known activity kind"));
        }

        /** strip 전 문자열 그대로(enum·label처럼 정확히 일치해야 하는 값). */
        String string(String field, boolean required) {
            JsonNode value = value(field, required);
            if (value == null) {
                return null;
            }
            if (!value.isTextual()) {
                throw violation(at(field), "must be a string");
            }
            return value.textValue();
        }

        String member(String field, Set<String> allowed, boolean required) {
            String value = string(field, required);
            if (value != null && !allowed.contains(value)) {
                throw violation(at(field), "is not an allowed value");
            }
            return value;
        }

        /** strip 후 code point 길이 [1, max]. */
        private String text(String field, int max, boolean required) {
            String value = string(field, required);
            if (value == null) {
                return null;
            }
            String stripped = BrowserRunContract.strip(value);
            int length = BrowserRunContract.codePointLength(stripped);
            if (length < 1 || length > max) {
                throw violation(at(field), "length is out of range");
            }
            return stripped;
        }

        /** activity_id는 128자 상한이 있고 response_page_id는 형식만 있다(Brain과 같다). */
        String identifier(String field, Pattern pattern, int maxLength, boolean required) {
            String value = text(field, maxLength, required);
            if (value != null && !pattern.matcher(value).matches()) {
                throw violation(at(field), "must use a neutral generated format");
            }
            return value;
        }

        String opaqueId(String field) {
            String value = text(field, MAX_IDENTIFIER_LENGTH, true);
            if (!BrowserRunContract.isOpaqueId(value)) {
                throw violation(at(field), "must be an opaque identifier");
            }
            return value;
        }

        String methodLabel() {
            String value = text("method_label", MAX_METHOD_LABEL_LENGTH, true);
            if (CONTROL.matcher(value).find()) {
                throw violation(at("method_label"), "cannot contain control characters");
            }
            return value;
        }

        /** 안전한 상태 문구: 제어 문자·URL·code fence·traceback 표식 금지. */
        String message() {
            String value = text("message", MAX_MESSAGE_LENGTH, false);
            if (value == null) {
                return null;
            }
            if (CONTROL.matcher(value).find()) {
                throw violation(at("message"), "cannot contain control characters");
            }
            if (URL_LIKE.matcher(value).find()) {
                throw violation(at("message"), "cannot contain a URL");
            }
            if (CODE_FENCE.matcher(value).find()) {
                throw violation(at("message"), "cannot contain a code fence");
            }
            if (value.contains(TRACEBACK_MARKER)) {
                throw violation(at("message"), "cannot contain a traceback marker");
            }
            return value;
        }

        /** JSON 정수만(bool·실수·문자열 거부), {@code >= min}. */
        Long integer(String field, long min, boolean required) {
            JsonNode value = value(field, required);
            if (value == null) {
                return null;
            }
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw violation(at(field), "must be an integer");
            }
            long number = value.longValue();
            if (number < min) {
                throw violation(at(field), "is out of range");
            }
            return number;
        }

        /** 있으면 배열이어야 한다. 없음(기본값)과 명시적 null을 구분한다: null은 거부. */
        JsonNode array(String field, boolean required) {
            known.add(field);
            JsonNode value = node.get(field);
            if (value == null) {
                if (required) {
                    throw violation(at(field), "is required");
                }
                return null;
            }
            if (!value.isArray()) {
                throw violation(at(field), "must be an array");
            }
            return value;
        }

        void closed() {
            for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                if (!known.contains(names.next())) {
                    throw violation(path, "has an unknown field");
                }
            }
        }
    }
}
