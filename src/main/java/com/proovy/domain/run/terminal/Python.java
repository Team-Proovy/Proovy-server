package com.proovy.domain.run.terminal;

/**
 * Brain(pydantic·Python str)과 같은 문자열 의미. {@code str.isspace()}는 Java {@link Character#isWhitespace}에
 * non-breaking space(U+00A0·U+2007·U+202F)와 U+0085를 더한 집합이고, 길이는 code point 수다.
 */
final class Python {

    private Python() {
    }

    static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x2007 || codePoint == 0x202F;
    }

    static String strip(String value) {
        return rstrip(lstrip(value));
    }

    static String lstrip(String value) {
        int start = 0;
        while (start < value.length()) {
            int codePoint = value.codePointAt(start);
            if (!isSpace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        return value.substring(start);
    }

    static String rstrip(String value) {
        int end = value.length();
        while (end > 0) {
            int codePoint = value.codePointBefore(end);
            if (!isSpace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    static int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
