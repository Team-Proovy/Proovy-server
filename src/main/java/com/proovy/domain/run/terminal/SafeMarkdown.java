package com.proovy.domain.run.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SafeMarkdown v1 (Brain {@code contracts/safe_markdown.py})의 구조 허용 목록·source marker·plain text 규칙.
 * <p>
 * 문자열 위치는 Java UTF-16 index로 계산하고, 공개 좌표(anchor·길이)는 Brain과 같은 Unicode code point로 바꿔 쓴다.
 * 정규식은 Python {@code re}의 str 의미에 맞춘다({@code .}·{@code ^}는 {@code \n}만 줄 끝, {@code \s}·{@code \b}는 Unicode).
 * 이 검사는 구조만 본다 — 수학 정확성·자연어 secret·정답 누출은 판정하지 않는다.
 */
final class SafeMarkdown {

    static final int MAX_LENGTH = 20_000;

    private static final int FLAGS = Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern SOURCE_MARKER =
            Pattern.compile("\\[\\[source:([A-Za-z0-9][A-Za-z0-9_.:-]{0,127})\\]\\]", FLAGS);
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f-\\x9f]");
    private static final Pattern FENCED_CODE = Pattern.compile("(?m)^[ \\t]{0,3}(?:`{3,}|~{3,})", FLAGS);
    private static final Pattern HTML =
            Pattern.compile("<!--|<!|<\\?|</?[A-Za-z][A-Za-z0-9:-]*(?:\\s[^>]*)?/?>", FLAGS);
    private static final Pattern LINK_DEFINITION = Pattern.compile("(?m)^[ \\t]*\\[[^\\]\\n]+\\][ \\t]*:", FLAGS);
    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^\\]\\n]*)\\]\\(([^)\\n]*)\\)", FLAGS);
    private static final Pattern LINK_DESTINATION = Pattern.compile("https?://[^\\s<>'\"()]+",
            FLAGS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DANGEROUS_SCHEME = Pattern.compile("\\b(?:javascript|vbscript|data|file|ftp)[ \\t]*:",
            FLAGS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern HEADING_PREFIX = Pattern.compile("^[ \\t]{0,3}#{1,6}[ \\t]+", FLAGS);
    private static final Pattern QUOTE_PREFIX = Pattern.compile("^[ \\t]{0,3}>[ \\t]?", FLAGS);
    private static final Pattern BOLD_PAIRS = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__", FLAGS);
    private static final Pattern PROSE_ESCAPE = Pattern.compile("\\\\([\\\\$\\[\\](){}*_<>`])", FLAGS);
    private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}", FLAGS);

    private SafeMarkdown() {
    }

    /** {@code [[source:<id>]]} 하나. start·end는 code point offset이다. */
    record Marker(String refId, int start, int end) {
    }

    /** 허용 목록 위반이면 {@link TerminalRejection}(UNSAFE_MARKDOWN). 본문 값은 메시지에 넣지 않는다. */
    static void validate(String text, String field) {
        if (CONTROL.matcher(text).find()) {
            throw unsafe(field, "control characters");
        }
        List<int[]> spans = mathSpans(text, field);
        for (int[] span : spans) {
            String math = text.substring(span[0], span[1]);
            if (math.contains("[[") || math.contains("]]")) {
                throw unsafe(field, "source marker inside math");
            }
        }
        checkIndentedCode(text, spans, field);
        String masked = mask(text, spans);
        if (FENCED_CODE.matcher(masked).find() || masked.indexOf('`') >= 0) {
            throw unsafe(field, "executable code");
        }
        if (HTML.matcher(masked).find()) {
            throw unsafe(field, "raw HTML");
        }
        if (masked.contains("![")) {
            throw unsafe(field, "image embed");
        }
        if (LINK_DEFINITION.matcher(masked).find()) {
            throw unsafe(field, "link reference definition");
        }
        Matcher link = INLINE_LINK.matcher(masked);
        while (link.find()) {
            String destination = Python.strip(link.group(2));
            if (destination.startsWith("<") || destination.endsWith(">")
                    || !LINK_DESTINATION.matcher(destination).matches()) {
                throw unsafe(field, "link destination");
            }
        }
        if (DANGEROUS_SCHEME.matcher(masked).find()) {
            throw unsafe(field, "URL scheme");
        }
        char[] stray = masked.toCharArray();
        for (int[] marker : markerSpans(text, spans)) {
            for (int i = marker[0]; i < marker[1]; i++) {
                stray[i] = ' ';
            }
        }
        String remainder = new String(stray);
        if (remainder.contains("[[") || remainder.contains("]]")) {
            throw unsafe(field, "malformed source marker");
        }
    }

    /** math 밖의 source marker(code point offset). math 안 marker는 citation이 아니다. */
    static List<Marker> markers(String text) {
        List<int[]> spans = mathSpans(text, "body");
        List<Marker> markers = new ArrayList<>();
        for (int[] span : markerSpans(text, spans)) {
            Matcher matcher = SOURCE_MARKER.matcher(text).region(span[0], span[1]);
            matcher.matches();
            markers.add(new Marker(matcher.group(1), text.codePointCount(0, span[0]), text.codePointCount(0, span[1])));
        }
        return markers;
    }

    /** Brain {@code render_plain_text}: marker 제거, math 내부 원문 유지, escape·link·bold·heading·quote 정리. */
    static String renderPlainText(String text) {
        List<int[]> spans = mathSpans(text, "body");
        List<String> mathParts = new ArrayList<>();
        List<Object[]> excisions = new ArrayList<>();
        for (int[] span : spans) {
            excisions.add(new Object[]{span[0], span[1], "\u0000" + mathParts.size() + "\u0000"});
            mathParts.add(unwrapMath(text.substring(span[0], span[1])));
        }
        for (int[] marker : markerSpans(text, spans)) {
            excisions.add(new Object[]{marker[0], marker[1], ""});
        }
        excisions.sort((a, b) -> Integer.compare((int) a[0], (int) b[0]));
        StringBuilder parts = new StringBuilder();
        int cursor = 0;
        for (Object[] excision : excisions) {
            parts.append(text, cursor, (int) excision[0]).append((String) excision[2]);
            cursor = (int) excision[1];
        }
        parts.append(text.substring(cursor));
        String rendered = INLINE_LINK.matcher(parts).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        rendered = BOLD_PAIRS.matcher(rendered).replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) != null ? match.group(1) : match.group(2)));
        rendered = PROSE_ESCAPE.matcher(rendered).replaceAll("$1");
        List<String> lines = new ArrayList<>();
        for (String line : rendered.split("\n", -1)) {
            line = HEADING_PREFIX.matcher(line).replaceFirst("");
            line = QUOTE_PREFIX.matcher(line).replaceFirst("");
            lines.add(Python.rstrip(line));
        }
        rendered = BLANK_RUN.matcher(String.join("\n", lines)).replaceAll("\n\n");
        for (int index = 0; index < mathParts.size(); index++) {
            rendered = rendered.replace("\u0000" + index + "\u0000", mathParts.get(index));
        }
        return Python.strip(rendered);
    }

    /** {@code $$…$$}·{@code \[…\]}·{@code \(…\)}·{@code $…$} span(UTF-16, delimiter 포함). 닫히지 않거나 빈 span은 거부한다. */
    private static List<int[]> mathSpans(String text, String field) {
        List<int[]> spans = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            String delimiter;
            int open;
            if (text.startsWith("$$", index)) {
                delimiter = "$$";
                open = 2;
            } else if (text.startsWith("\\[", index)) {
                delimiter = "\\]";
                open = 2;
            } else if (text.startsWith("\\(", index)) {
                delimiter = "\\)";
                open = 2;
            } else if (text.charAt(index) == '$' && !isEscaped(text, index)) {
                delimiter = "$";
                open = 1;
            } else {
                index++;
                continue;
            }
            int close = findClose(text, index + open, delimiter);
            if (close == -1) {
                throw unsafe(field, "unclosed math");
            }
            if (Python.strip(text.substring(index + open, close)).isEmpty()) {
                throw unsafe(field, "empty math");
            }
            spans.add(new int[]{index, close + delimiter.length()});
            index = close + delimiter.length();
        }
        return spans;
    }

    private static int findClose(String text, int start, String delimiter) {
        int index = start;
        while (true) {
            index = text.indexOf(delimiter, index);
            if (index == -1 || !isEscaped(text, index)) {
                return index;
            }
            index += delimiter.length();
        }
    }

    private static boolean isEscaped(String text, int index) {
        int backslashes = 0;
        for (int position = index - 1; position >= 0 && text.charAt(position) == '\\'; position--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static List<int[]> markerSpans(String text, List<int[]> mathSpans) {
        List<int[]> markers = new ArrayList<>();
        int cursor = 0;
        List<int[]> segments = new ArrayList<>();
        for (int[] span : mathSpans) {
            if (cursor < span[0]) {
                segments.add(new int[]{cursor, span[0]});
            }
            cursor = span[1];
        }
        if (cursor < text.length()) {
            segments.add(new int[]{cursor, text.length()});
        }
        for (int[] segment : segments) {
            Matcher matcher = SOURCE_MARKER.matcher(text).region(segment[0], segment[1]);
            while (matcher.find()) {
                markers.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        return markers;
    }

    private static String mask(String text, List<int[]> spans) {
        char[] characters = text.toCharArray();
        for (int[] span : spans) {
            for (int i = span[0]; i < span[1]; i++) {
                characters[i] = ' ';
            }
        }
        return new String(characters);
    }

    /** 줄 시작 4-column(tab stop 4) 들여쓰기 코드. 들여쓰기가 math span 안이면 math 내용이다. */
    private static void checkIndentedCode(String text, List<int[]> spans, String field) {
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            if (indent < line.length() && indent > 0 && expandedWidth(line.substring(0, indent)) >= 4) {
                int lineStart = offset;
                int indentEnd = offset + indent;
                boolean inMath = spans.stream().anyMatch(span -> span[0] < indentEnd && span[1] > lineStart);
                if (!inMath) {
                    throw unsafe(field, "indented code");
                }
            }
            offset += line.length() + 1;
        }
    }

    private static int expandedWidth(String whitespace) {
        int column = 0;
        for (int i = 0; i < whitespace.length(); i++) {
            column = whitespace.charAt(i) == '\t' ? (column / 4 + 1) * 4 : column + 1;
        }
        return column;
    }

    private static String unwrapMath(String span) {
        return span.startsWith("$$") || span.startsWith("\\[") || span.startsWith("\\(")
                ? span.substring(2, span.length() - 2)
                : span.substring(1, span.length() - 1);
    }

    private static TerminalRejection unsafe(String field, String problem) {
        return new TerminalRejection(TerminalRejectionReason.UNSAFE_MARKDOWN, field, problem);
    }
}
