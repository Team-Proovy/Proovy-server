package com.proovy.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.proovy.domain.credit.entity.CreditEventType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 신규 실행 경로에 구형 실행·과금 경로가 되살아나지 않게 막는 회귀 검사(task 1.87, spec 07).
 *
 * <p>ChatSession·thread ID·구형 stream/client·토큰 누적·기능별 사후 차감·브라우저 임의 차감·구형 내부 API token 진입점이
 * {@code src/main}에 다시 나타나면 실패한다. 사용량 차감은 {@code RequestUsageService}의 request당 한 번 차감·보상뿐이다.
 */
class LegacyExecutionPathGuardTest {

    private static final Path MAIN = Path.of("src/main");
    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*(--|//|\\*|/\\*)");

    /** 금지 패턴 → 이유. 대소문자 무시. */
    private static final Map<String, String> BANNED = Map.ofEntries(
            Map.entry("chat_?session", "ChatSession FK·호환 adapter는 신규 오픈에서 제거"),
            Map.entry("thread_?id", "Brain thread ID 대신 run_id·attempt·fence를 쓴다"),
            Map.entry("/stream/v2", "구형 AI stream 경로"),
            Map.entry("proovy[._]ai[._]host", "구형 proovy-agent client 설정"),
            Map.entry("ProovyAiRequest|ProovyAiClient|StreamEvent\\b", "구형 AI client·stream DTO"),
            Map.entry("X-Internal-Token|internal\\.api\\.token", "구형 agent → Server 내부 API token 진입점"),
            Map.entry("credits/use|credits/costs|credits/estimate", "브라우저 임의 차감·기능별 비용표"),
            Map.entry("totalTokens|promptTokens|completionTokens|total_tokens|prompt_tokens|completion_tokens",
                    "토큰 누적 과금"));

    @Test
    void mainSourcesCarryNoLegacyExecutionOrBillingPath() {
        List<String> violations = new ArrayList<>();
        for (Path file : mainFiles()) {
            // 제거 사실을 적은 주석(SQL --, Java //·javadoc)은 검사하지 않는다
            String text = read(file).lines()
                    .filter(line -> !COMMENT_LINE.matcher(line).find())
                    .collect(java.util.stream.Collectors.joining("\n"));
            BANNED.forEach((regex, reason) -> {
                if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                    violations.add(file + " ~ /" + regex + "/ (" + reason + ")");
                }
            });
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void onlyRequestUsageDeductsBalance() {
        List<String> callers = mainFiles().stream()
                .filter(file -> file.toString().endsWith(".java"))
                .filter(file -> read(file).contains("deductCredit("))
                .map(file -> file.getFileName().toString())
                .sorted()
                .toList();
        // 엔티티 정의와 request당 한 번 차감하는 RequestUsageService만 — 기능별 사후 차감 경로가 없다
        assertThat(callers).containsExactly("CreditBalance.java", "RequestUsageService.java");
    }

    @Test
    void creditEventsAreGrantsPlusOneRequestChargeAndItsCompensation() {
        assertThat(Arrays.stream(CreditEventType.values()).map(Enum::name))
                .containsExactlyInAnyOrder("DAILY_RESET", "MONTHLY_GRANT", "MONTHLY_EXPIRE", "SIGNUP_BONUS",
                        "LLM_QUERY", "LLM_QUERY_COMPENSATION");
    }

    @Test
    void brainCallPathNeverReadsTheBrowserIdentity() {
        // Brain 호출은 서비스 credential만 쓴다 — 사용자 JWT·요청 header·SecurityContext를 읽는 코드가 없다
        List<String> violations = new ArrayList<>();
        for (String dir : List.of("java/com/proovy/global/infra/brain", "java/com/proovy/domain/run/coordinator",
                "java/com/proovy/domain/run/brain")) {
            for (Path file : files(MAIN.resolve(dir))) {
                String text = read(file);
                for (String banned : List.of("SecurityContextHolder", "UserPrincipal", "HttpServletRequest",
                        "JwtTokenProvider", "\"Authorization\"")) {
                    if (text.contains(banned)) {
                        violations.add(file + " ~ " + banned);
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    private static List<Path> mainFiles() {
        return files(MAIN);
    }

    private static List<Path> files(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
