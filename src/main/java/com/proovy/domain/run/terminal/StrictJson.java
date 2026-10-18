package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.entity.WireValue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Brain closed model(pydantic {@code extra="forbid"}·{@code str_strip_whitespace}) 의미로 JSON object를 읽는다.
 * <ul>
 *   <li>알 수 없는 key는 거부한다.</li>
 *   <li>문자열은 Python {@code str.strip()}으로 정리한 뒤 code point 길이·형식을 검사한다.
 *       enum·Literal 값은 str 필드가 아니므로 strip하지 않고 원문으로 비교한다.</li>
 *   <li>정수·bool은 JSON 정수·bool만 받는다(문자열·실수 coercion 없음 — Brain보다 엄격한 쪽, fail-closed).</li>
 *   <li>선택 배열이 없으면 빈 목록이고, JSON {@code null} 배열은 거부한다.</li>
 * </ul>
 * 위반 message는 필드 경로만 담는다.
 */
final class StrictJson {

    static final int MAX_IDENTIFIER_LENGTH = 128;
    static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    private final JsonNode node;
    private final String path;

    private StrictJson(JsonNode node, String path) {
        this.node = node;
        this.path = path;
    }

    /** {@code node}가 object이고 key가 모두 {@code allowed} 안에 있어야 한다. */
    static StrictJson object(JsonNode node, String path, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw malformed(path, "must be an object");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                // 알 수 없는 key 이름도 payload 일부이므로 message에 넣지 않는다.
                throw malformed(path, "unknown field");
            }
        }
        return new StrictJson(node, path);
    }

    static Pattern neutral(String prefix) {
        return Pattern.compile(prefix + "-(?:[1-9][0-9]*|[0-9a-f]{20}-[0-9]{3})");
    }

    String path(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    boolean present(String key) {
        JsonNode value = node.get(key);
        return value != null && !value.isNull();
    }

    JsonNode raw(String key) {
        return node.get(key);
    }

    String string(String key, int min, int max) {
        String value = optionalString(key, min, max);
        if (value == null) {
            throw malformed(path(key), "is required");
        }
        return value;
    }

    String optionalString(String key, int min, int max) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        return checkString(value, path(key), min, max);
    }

    /** strip 하지 않는 문자열(앞뒤 공백을 거부해야 하는 block body). */
    String exactString(String key, int min, int max) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw malformed(path(key), "is required");
        }
        if (!value.isTextual()) {
            throw malformed(path(key), "must be a string");
        }
        String text = value.textValue();
        if (!text.equals(Python.strip(text))) {
            throw new TerminalRejection(TerminalRejectionReason.UNSAFE_MARKDOWN, path(key),
                    "leading or trailing whitespace");
        }
        return checkLength(text, path(key), min, max);
    }

    String id(String key, Pattern pattern) {
        return checkPattern(string(key, 1, MAX_IDENTIFIER_LENGTH), path(key), pattern);
    }

    String optionalId(String key, Pattern pattern) {
        String value = optionalString(key, 1, MAX_IDENTIFIER_LENGTH);
        return value == null ? null : checkPattern(value, path(key), pattern);
    }

    List<String> idList(String key, Pattern pattern, int max) {
        List<String> ids = new ArrayList<>();
        List<JsonNode> items = array(key, max);
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path(key) + "[" + i + "]";
            ids.add(checkPattern(checkString(items.get(i), itemPath, 1, MAX_IDENTIFIER_LENGTH), itemPath, pattern));
        }
        return ids;
    }

    int strictInt(String key, int min) {
        Integer value = optionalStrictInt(key, min);
        if (value == null) {
            throw malformed(path(key), "is required");
        }
        return value;
    }

    Integer optionalStrictInt(String key, int min) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw malformed(path(key), "must be an integer");
        }
        if (value.intValue() < min) {
            throw malformed(path(key), "is below the minimum");
        }
        return value.intValue();
    }

    boolean strictBool(String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isBoolean()) {
            throw malformed(path(key), "must be a boolean");
        }
        return value.booleanValue();
    }

    /** 선택 배열(없으면 빈 목록). {@code null}·비배열은 거부한다. */
    List<JsonNode> array(String key, int max) {
        JsonNode value = node.get(key);
        if (value == null) {
            return List.of();
        }
        return checkArray(value, path(key), 0, max);
    }

    List<JsonNode> requiredArray(String key, int min, int max) {
        JsonNode value = node.get(key);
        if (value == null) {
            throw malformed(path(key), "is required");
        }
        return checkArray(value, path(key), min, max);
    }

    /** {@code tuple | None = None}: 없거나 null이면 null. */
    List<JsonNode> nullableArray(String key, int max) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        return checkArray(value, path(key), 0, max);
    }

    JsonNode required(String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw malformed(path(key), "is required");
        }
        return value;
    }

    /** 기본값이 있는 literal(없으면 통과, 있으면 같은 값). */
    void literal(String key, String expected) {
        JsonNode value = node.get(key);
        // Literal·enum은 str 필드가 아니라 strip하지 않는다(pydantic str_strip_whitespace는 str에만 적용).
        if (value != null && !(value.isTextual() && expected.equals(value.textValue()))) {
            throw malformed(path(key), "unexpected literal");
        }
    }

    void literal(String key, int expected) {
        JsonNode value = node.get(key);
        if (value != null && !(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == expected)) {
            throw malformed(path(key), "unexpected literal");
        }
    }

    <E extends Enum<E>> E enumValue(String key, Class<E> type) {
        E value = optionalEnum(key, type);
        if (value == null) {
            throw malformed(path(key), "is required");
        }
        return value;
    }

    /** enum은 원문 그대로 비교한다(공백이 붙은 값은 Brain처럼 거부). */
    <E extends Enum<E>> E optionalEnum(String key, Class<E> type) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw malformed(path(key), "must be a string");
        }
        String wire = value.textValue();
        for (E candidate : type.getEnumConstants()) {
            if (wire.equals(wireValue(candidate))) {
                return candidate;
            }
        }
        throw malformed(path(key), "unknown enum value");
    }

    static String wireValue(Enum<?> value) {
        return value instanceof WireValue wire ? wire.wireValue() : value.name().toLowerCase(Locale.ROOT);
    }

    static String checkString(JsonNode value, String path, int min, int max) {
        if (!value.isTextual()) {
            throw malformed(path, "must be a string");
        }
        return checkLength(Python.strip(value.textValue()), path, min, max);
    }

    private static String checkLength(String text, String path, int min, int max) {
        int length = Python.length(text);
        if (length < min || length > max) {
            throw malformed(path, "has an invalid length");
        }
        return text;
    }

    private static String checkPattern(String value, String path, Pattern pattern) {
        if (!pattern.matcher(value).matches()) {
            throw malformed(path, "has an invalid format");
        }
        return value;
    }

    private static List<JsonNode> checkArray(JsonNode value, String path, int min, int max) {
        if (!value.isArray()) {
            throw malformed(path, "must be an array");
        }
        if (value.size() < min || value.size() > max) {
            throw malformed(path, "has an invalid number of items");
        }
        List<JsonNode> items = new ArrayList<>(value.size());
        value.forEach(items::add);
        return items;
    }

    static TerminalRejection malformed(String path, String problem) {
        return new TerminalRejection(TerminalRejectionReason.MALFORMED, path, problem);
    }
}
