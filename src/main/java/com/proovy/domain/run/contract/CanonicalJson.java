package com.proovy.domain.run.contract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * internal-run-v1 canonical JSON (Brain {@code canonical_json_dumps}와 같은 규칙).
 * <ul>
 *   <li>공백 없음, object member는 key의 code point 순서, 값이 null인 member는 생략</li>
 *   <li>문자열은 {@code \" \\ \b \f \n \r \t}, 그 밖의 U+0020 미만과 짝 없는 surrogate만 소문자
 *       {@code \\uXXXX}로 escape하고 나머지(비ASCII 포함)는 그대로</li>
 *   <li>정수는 10진수, 실수는 round-trip 최단 10진 표기를 지수 없이 쓰고 정수값 실수는 정수로({@code 1.0 → 1})</li>
 * </ul>
 * 지원 값: null·Boolean·정수형·Double/Float·String·List·Map&lt;String, ?&gt;.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            out.append(number(((Number) value).doubleValue()));
        } else if (value instanceof String text) {
            quote(out, text);
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                append(out, list.get(i));
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            List<Map.Entry<String, Object>> members = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() != null) {
                    members.add(Map.entry((String) entry.getKey(), entry.getValue()));
                }
            }
            members.sort((a, b) -> compareCodePoints(a.getKey(), b.getKey()));
            out.append('{');
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                quote(out, members.get(i).getKey());
                out.append(':');
                append(out, members.get(i).getValue());
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("canonical JSON cannot represent " + value.getClass().getName());
        }
    }

    static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("canonical JSON cannot represent non-finite numbers");
        }
        double normalized = value + 0.0; // -0.0 → 0.0
        if (normalized == Math.rint(normalized)) {
            return new BigDecimal(normalized).toBigIntegerExact().toString();
        }
        // JDK 19+ Double.toString은 round-trip 최단 자릿수다. 지수 표기만 평문으로 편다.
        return new BigDecimal(Double.toString(normalized)).stripTrailingZeros().toPlainString();
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < text.length()
                            && Character.isLowSurrogate(text.charAt(i + 1))) {
                        out.append(c).append(text.charAt(++i));
                    } else if (c < 0x20 || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
