package com.proovy.domain.run.coordinator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.auth.service.AccessTokenBlacklistService;
import com.proovy.domain.auth.service.JwtTokenProvider;
import com.proovy.support.db.PostgresTestDatabase;
import com.proovy.support.http.BrowserRunHttp;
import com.proovy.support.http.SseClient;
import com.proovy.support.http.SseClient.Block;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 빈 제품 DB에서 실제 Java 경로 전체를 한 번 완주한다(task 1.87 단계 3): 실제 HTTP POST /api/conversations(JWT) → 접수
 * transaction → coordinator claim → 실제 {@code BrainRunClient} → loopback {@link FakeBrain} → terminal transaction
 * → POST SSE 종료 → GET replay. Brain만 가짜이고 나머지는 production bean이다. 같은 경로를 실제 Python Brain으로 도는
 * 검사는 {@code com.proovy.integration.brain}(단계 4)에 있다.
 */
@Tag("postgres")
@ActiveProfiles("test")
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gcs.project-id=proovy-test",
        "gcs.endpoint=http://127.0.0.1:1",
        "jwt.secret=test-jwt-secret-test-jwt-secret-0123456789",
        "oauth.kakao.client-id=test", "oauth.kakao.client-secret=test",
        "oauth.naver.client-id=test", "oauth.naver.client-secret=test",
        "openrouter.api.key=test",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.task.scheduling.pool.size=1",
        "proovy.subscription.plan-transition-fixed-delay-ms=3600000",
        "proovy.subscription.auto-renew-fixed-delay-ms=3600000",
        "proovy.brain.auth.mode=shared-token",
        "proovy.brain.auth.shared-token=t187-path-token",
        "proovy.brain.stream-idle-timeout=5s",
        "proovy.run.coordinator.enabled=true",
        "proovy.run.coordinator.instance-id=t187-path",
        "proovy.run.coordinator.poll-interval=100ms",
        "proovy.run.coordinator.heartbeat-interval=300ms",
        "proovy.run.coordinator.lease-ttl=2s",
        "proovy.run.coordinator.min-dispatch-remaining=0s",
        "proovy.run.sse.heartbeat=500ms"
})
class RunPathEndToEndPostgresTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long DAILY_CREDITS = 100;

    private static PostgresTestDatabase db;
    private static FakeBrain brain;

    @BeforeAll
    static void setUp() {
        // 빈 baseline(V1~)만 — seed 없이 시작한다
        db = PostgresTestDatabase.createMigrated();
        brain = new FakeBrain();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> db.jdbcUrl());
        registry.add("spring.datasource.username", PostgresTestDatabase::adminUser);
        registry.add("spring.datasource.password", PostgresTestDatabase::adminPassword);
        registry.add("proovy.brain.base-url", () -> brain.baseUrl());
    }

    @AfterAll
    static void tearDown() {
        if (brain != null) {
            brain.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @LocalServerPort int port;
    @MockitoBean AccessTokenBlacklistService blacklist;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private BrowserRunHttp browser;

    @BeforeEach
    void client() {
        browser = new BrowserRunHttp(port, WAIT);
    }

    @Test
    void firstTextOnAnEmptyBaselineRunsTheWholeJavaPathAndReplaysIdentically() throws Exception {
        long user = newUser();
        String token = tokens.generateTokens(user).accessToken();
        String requestId = UUID.randomUUID().toString();
        assertThat(count("SELECT count(*) FROM notes WHERE user_id = ?", user)).isZero();

        List<Block> live;
        UUID runId;
        long noteId;
        try (SseClient post = browser.post(token, body(requestId))) {
            assertThat(post.status).as(post.errorBody).isEqualTo(200);
            live = new ArrayList<>(post.framesUntilEnd());
        }
        Block created = live.get(0);
        assertThat(created.event()).isEqualTo("run.created");
        runId = UUID.fromString(created.json().get("runId").asText());
        noteId = created.json().at("/payload/noteId").asLong();
        assertThat(live.get(live.size() - 1).event()).isEqualTo("run.completed");
        assertThat(live.stream().map(Block::seq).toList())
                .isEqualTo(java.util.stream.LongStream.rangeClosed(1, live.size()).boxed().toList());

        // 브라우저와 독립적인 coordinator가 snapshot 그대로 Brain을 한 번 불렀다
        List<FakeBrain.Delivery> sent = brain.deliveries(requestId);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).runId()).isEqualTo(runId.toString());
        assertThat(sent.get(0).attempt()).isEqualTo(1);

        // 완료 뒤 GET replay는 같은 바이트
        try (SseClient replay = browser.events(token, runId, null)) {
            assertThat(replay.framesUntilEnd().stream().map(Block::raw).toList())
                    .containsExactlyElementsOf(live.stream().map(Block::raw).toList());
        }

        // 하나의 자동 생성 Note·run·메시지 2개, clarification은 보상되어 순사용량 0
        assertThat(count("SELECT count(*) FROM notes WHERE user_id = ?", user)).isOne();
        assertThat(count("SELECT count(*) FROM ai_runs WHERE note_id = ?", noteId)).isOne();
        assertThat(count("SELECT count(*) FROM chat_messages WHERE note_id = ?", noteId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_runs WHERE run_id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM request_usage_charges WHERE request_id = ?", requestId)).isOne();
        assertThat(count("SELECT count(*) FROM request_usage_compensations WHERE request_id = ?", requestId))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT daily_free_credit FROM credit_balance WHERE user_id = ?",
                Long.class, user)).isEqualTo(DAILY_CREDITS);

        JsonNode page = browser.conversation(token, noteId).body();
        assertThat(page.at("/result/messages")).hasSize(2);
    }

    @Test
    void baselineHasNoLegacyConversationTablesOrColumns() {
        // ChatSession·thread ID는 신규 baseline에 없다
        assertThat(jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (table_name ILIKE '%session%' OR column_name ILIKE '%session%' OR column_name ILIKE '%thread%')
                """, String.class)).isEmpty();
    }

    @Test
    void onlyTheNewRunRoutesExistForExecutionAndUsage() {
        Map<String, String> routes = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()
                        .map(pattern -> Map.entry(pattern, info.getMethodsCondition().toString())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a + b));
        assertThat(routes.keySet()).noneMatch(path -> path.startsWith("/internal"))
                .noneMatch(path -> path.contains("/stream"))
                .noneMatch(path -> path.startsWith("/api/credits/") && !path.equals("/api/credits/balance")
                        && !path.equals("/api/credits/history"));
        assertThat(routes.keySet()).contains("/api/conversations", "/api/ai-runs/{runId}/events",
                "/api/ai-runs/{runId}", "/api/ai-runs/{runId}/cancel", "/api/conversations/{noteId}/messages");
    }

    private static String body(String requestId) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("requestId", requestId);
        body.put("text", "x^2-5x+6=0을 풀어줘");
        return body.toString();
    }

    private long newUser() {
        String key = UUID.randomUUID().toString();
        long user = jdbc.queryForObject("""
                INSERT INTO users (nickname, provider, provider_user_id, email, created_at, updated_at)
                VALUES ('t187', 'KAKAO', ?, ?, now(), now()) RETURNING user_id
                """, Long.class, key, key + "@example.test");
        jdbc.update("""
                INSERT INTO credit_balance (user_id, daily_free_credit, daily_free_limit, daily_expires_at, free_credit,
                                            paid_credit, created_at, updated_at)
                VALUES (?, ?, ?, now() + interval '1 day', 0, 0, now(), now())
                """, user, DAILY_CREDITS, DAILY_CREDITS);
        return user;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
