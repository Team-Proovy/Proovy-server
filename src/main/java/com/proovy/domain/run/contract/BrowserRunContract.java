package com.proovy.domain.run.contract;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * 브라우저 run API wire 계약(browser-run-v1, task 1.75)의 고정 값.
 * <p>
 * 계약 의미는 Server가 소유하고 schema·정상/거부 예제는 proovy-brain
 * {@code tests/fixtures/contracts/browser-run-v1}에 versioned artifact로 고정한다.
 * 테스트가 pin된 사본(src/test/resources/contracts/brain)과 이 값들을 대조한다.
 */
public final class BrowserRunContract {

    public static final String CONTRACT_VERSION = "browser-run-v1";
    /** 모든 durable frame의 contractVersion (D1). */
    public static final String FRAME_CONTRACT_VERSION = "brain-source-v1";
    public static final String FINGERPRINT_ALGORITHM = "browser-request-sha256-canonical-json-v1";

    /** text·latex 각각 strip 후 code point 상한 (D6). */
    public static final int TEXT_MAX_CODE_POINTS = 10_000;
    public static final int LATEX_MAX_CODE_POINTS = 10_000;
    /** turnAttachmentIds wire 상한. 실제 개수·용량 한도는 Server policy(RUN4221)다. */
    public static final int TURN_ATTACHMENT_IDS_WIRE_MAX = 10;
    public static final int REQUESTED_TASKS_MAX = 3;
    public static final int REQUESTED_OUTPUTS_MAX = 2;
    /** live 연결 heartbeat 주기 (D10). id·data·seq가 없고 저장·replay되지 않는다. */
    public static final int HEARTBEAT_SECONDS = 15;
    public static final String HEARTBEAT_COMMENT = ": heartbeat\n\n";
    /** seq 상한 2^53-1 (JS Number 안전 정수). */
    public static final long MAX_SEQ = 9_007_199_254_740_991L;

    public static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** noteId: 선행 0 없는 10진 문자열, 1..Long.MAX_VALUE (범위는 {@link #isNoteId}가 판정). */
    public static final Pattern NOTE_ID = Pattern.compile("[1-9][0-9]{0,18}");
    public static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    private BrowserRunContract() {
    }

    /** 패턴은 항상 입력 전체와 일치해야 한다(끝 줄바꿈 허용 금지). */
    public static boolean isUuid(String value) {
        return value != null && UUID.matcher(value).matches();
    }

    public static boolean isNoteId(String value) {
        if (value == null || !NOTE_ID.matcher(value).matches()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }

    public static boolean isOpaqueId(String value) {
        return value != null && OPAQUE_ID.matcher(value).matches();
    }

    /**
     * internal-run-v1과 같은 strip 집합(Python {@code str.strip()})에 속하는지.
     * Java {@link String#strip()}은 U+00A0·U+2007·U+202F를 남기므로 쓰지 않는다.
     */
    public static boolean isStripWhitespace(int codePoint) {
        return switch (codePoint) {
            case '\t', '\n', 0x0B, '\f', '\r', 0x1C, 0x1D, 0x1E, 0x1F, ' ',
                 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true;
            default -> codePoint >= 0x2000 && codePoint <= 0x200A;
        };
    }

    /** 양끝의 strip 집합 문자를 제거한다. */
    public static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isStripWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isStripWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    /** 길이는 UTF-16 단위가 아니라 code point로 센다. */
    public static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    /** wire 시각은 소수 6자리 이하({@code ...Z})다. 나노초 시각은 마이크로초로 절단해 쓴다. */
    public static Instant wireTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
