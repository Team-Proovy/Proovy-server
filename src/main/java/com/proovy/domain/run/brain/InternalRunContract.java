package com.proovy.domain.run.brain;

import com.proovy.domain.run.contract.BrowserRunContract;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * internal-run-v1 (Server↔Brain 내부 실행 wire) 상수와 검증 도우미.
 * <p>
 * 원본은 proovy-brain {@code contracts/internal_run.py}이고, 값은 pin된 fixture
 * ({@code internal-run-fixtures-v2})로 검사한다. 이 패키지의 record는 생성 시점에 Brain과 같은 규칙으로
 * 정규화·검증하므로, 잘못된 요청은 Brain에 보내기 전에 만들어지지 않는다.
 * <ul>
 *   <li>문자열은 Python {@code str.strip()} 집합으로 양끝을 지운 뒤 검사한다(pydantic {@code str_strip_whitespace}).</li>
 *   <li>길이는 code point 수다(Python {@code len}).</li>
 *   <li>오류 message에는 필드 이름만 쓰고 입력 값은 넣지 않는다.</li>
 * </ul>
 */
public final class InternalRunContract {

    public static final String CONTRACT_VERSION = "internal-run-v1";
    public static final String SOURCE_CONTRACT_VERSION = "brain-source-v1";
    public static final String FINGERPRINT_ALGORITHM = "sha256-canonical-json-v1";
    /** Server가 맞춰 둔 fixture 소비 버전. pin을 올리면 함께 올린다. */
    public static final String CONSUMER_VERSION = "internal-run-fixtures-v2";

    public static final int MAX_HISTORY_MESSAGES = 100;
    public static final int MAX_HISTORY_MESSAGE_CHARS = 50_000;
    public static final int MAX_HISTORY_TOTAL_CHARS = 480_000;
    public static final int MAX_VERIFIED_SOLUTION_REFS = 50;
    public static final int MAX_TURN_ATTACHMENT_REFS = 50;
    public static final int MAX_TURN_ATTACHMENTS = 10;
    public static final int MAX_PROBLEM_SUMMARY_CHARS = 4_000;
    public static final int MAX_CLAIM_STATEMENT_CHARS = 2_000;
    public static final int MAX_REF_CLAIMS = 50;
    public static final int MAX_DISPLAY_LABEL_CHARS = 200;
    public static final int MAX_READ_URL_CHARS = 4_096;
    public static final int MAX_GRANT_CHARS = 4_096;
    public static final long MAX_ASSET_BYTES = 209_715_200L;
    public static final int MAX_ASSET_PAGES = 1_000;
    public static final int MAX_ASSET_PIXEL_EDGE = 65_535;
    public static final long MAX_ASSET_PIXEL_COUNT = 500_000_000L;
    public static final int MAX_IDENTIFIER_LENGTH = 128;
    public static final int MAX_VERSION_LABEL_LENGTH = 64;
    public static final int MAX_FENCE_TOKEN_LENGTH = 128;
    public static final int MAX_REQUEST_TEXT_LENGTH = 100_000;
    public static final int MAX_REQUEST_LATEX_LENGTH = 100_000;
    public static final int MAX_REQUESTED_VALUES = 10;
    public static final int MAX_SOURCE_EVENT_ID_LENGTH = 256;

    static final Pattern VERSION_LABEL = Pattern.compile("[A-Za-z0-9._-]+");
    static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    static final Pattern LOCALE = Pattern.compile("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*");
    static final Pattern SOLUTION_ID = neutral("solution");
    static final Pattern CLAIM_ID = neutral("claim");
    static final Pattern SOURCE_EVENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}");

    private InternalRunContract() {
    }

    private static Pattern neutral(String prefix) {
        return Pattern.compile(prefix + "-(?:[1-9][0-9]*|[0-9a-f]{20}-[0-9]{3})");
    }

    /** null은 그대로, 문자열은 Python {@code str.strip()} 집합으로 양끝을 지운다. */
    static String strip(String value) {
        return value == null ? null : BrowserRunContract.strip(value);
    }

    static String require(String value, String field) {
        if (value == null) {
            throw violation(field, "is required");
        }
        return value;
    }

    static <T> T require(T value, String field) {
        if (value == null) {
            throw violation(field, "is required");
        }
        return value;
    }

    /** strip 후 code point 길이가 [min, max]인 필수 문자열. */
    static String text(String value, String field, int min, int max) {
        String stripped = strip(require(value, field));
        int length = stripped.codePointCount(0, stripped.length());
        if (length < min || length > max) {
            throw violation(field, "length is out of range");
        }
        return stripped;
    }

    /** null 허용 문자열. 값이 있으면 {@link #text}와 같다. */
    static String optionalText(String value, String field, int min, int max) {
        return value == null ? null : text(value, field, min, max);
    }

    static String opaqueId(String value, String field) {
        String stripped = strip(require(value, field));
        if (!BrowserRunContract.isOpaqueId(stripped)) {
            throw violation(field, "must be an opaque identifier");
        }
        return stripped;
    }

    static String fenceToken(String value, String field) {
        return opaqueId(text(value, field, 1, MAX_FENCE_TOKEN_LENGTH), field);
    }

    static String matching(String value, String field, Pattern pattern, int maxLength) {
        String stripped = text(value, field, 1, maxLength);
        if (!pattern.matcher(stripped).matches()) {
            throw violation(field, "has an invalid format");
        }
        return stripped;
    }

    static String versionLabel(String value, String field) {
        return matching(value, field, VERSION_LABEL, MAX_VERSION_LABEL_LENGTH);
    }

    static String sha256(String value, String field) {
        return matching(value, field, SHA256_HEX, 64);
    }

    static int atLeast(int value, int min, String field) {
        if (value < min) {
            throw violation(field, "is out of range");
        }
        return value;
    }

    static long atLeast(long value, long min, String field) {
        if (value < min) {
            throw violation(field, "is out of range");
        }
        return value;
    }

    static Integer optionalRange(Integer value, long min, long max, String field) {
        if (value != null && (value < min || value > max)) {
            throw violation(field, "is out of range");
        }
        return value;
    }

    static Long optionalRange(Long value, long min, long max, String field) {
        if (value != null && (value < min || value > max)) {
            throw violation(field, "is out of range");
        }
        return value;
    }

    /** null → 빈 목록, 상한 검사 후 불변 사본. */
    static <T> List<T> list(List<T> values, int max, String field) {
        List<T> copy = values == null ? List.of() : List.copyOf(values);
        if (copy.size() > max) {
            throw violation(field, "has too many entries");
        }
        return copy;
    }

    static <T> void unique(Collection<T> values, Function<T, ?> identity, String field) {
        Set<Object> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(identity.apply(value))) {
                throw violation(field, "must be unique");
            }
        }
    }

    static InternalRunContractViolation violation(String field, String problem) {
        return new InternalRunContractViolation(field + " " + problem);
    }
}
