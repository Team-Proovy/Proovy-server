package com.proovy.domain.run.coordinator;

import static com.proovy.support.db.ProductRows.ALICE;
import static com.proovy.support.db.ProductRows.ALICE_NOTE;
import static com.proovy.support.db.ProductRows.ALICE_PDF;
import static com.proovy.support.db.ProductRows.ALICE_SECOND_NOTE;
import static com.proovy.support.db.ProductRows.BOB;
import static com.proovy.support.db.ProductRows.BOB_NOTE;
import static com.proovy.support.db.ProductRows.FINGERPRINT;
import static org.assertj.core.api.Assertions.assertThat;

import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.repository.AiRunRepository;
import com.proovy.domain.run.snapshot.RunAssetAccess;
import com.proovy.support.db.PostgresTestDatabase;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Postgres + RunCoordinatorConfig + GcsConfig(local-emulator opt-in) 통합 wiring 및
 * run/asset/turn_attachments 승인 경계 검증.
 */
@Tag("postgres")
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "gcs.project-id=proovy-test",
        "gcs.bucket=proovy-local-assets",
        "gcs.endpoint=http://127.0.0.1:14443",
        "gcs.local-emulator.enabled=true",
        "gcs.local-emulator.public-base-url=https://macmini.tailnet.ts.net",
        "jwt.secret=test-jwt-secret-test-jwt-secret-0123456789",
        "oauth.kakao.client-id=test", "oauth.kakao.client-secret=test",
        "oauth.naver.client-id=test", "oauth.naver.client-secret=test",
        "openrouter.api.key=test",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.task.scheduling.pool.size=1",
        "proovy.subscription.plan-transition-fixed-delay-ms=3600000",
        "proovy.subscription.auto-renew-fixed-delay-ms=3600000",
        "proovy.brain.auth.mode=shared-token",
        "proovy.brain.auth.shared-token=t218-shared-token",
        "proovy.run.coordinator.enabled=true",
        "proovy.run.coordinator.instance-id=t218-coordinator",
        "proovy.run.coordinator.poll-interval=60s",
        "proovy.run.coordinator.heartbeat-interval=10s",
        "proovy.run.coordinator.lease-ttl=30s",
        "proovy.run.coordinator.deadline-sweep-interval=60s",
        "proovy.run.coordinator.shutdown-timeout=2s"
})
class StorageBackedRunAssetAccessPostgresTest {

    private static PostgresTestDatabase db;
    private static FakeBrain brain;
    private static String ephemeralPem;

    @BeforeAll
    static void setUp() throws Exception {
        db = PostgresTestDatabase.createMigrated();
        db.runScript("db/seed/product-seed.sql");
        brain = new FakeBrain();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ephemeralPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> db.jdbcUrl());
        registry.add("spring.datasource.username", PostgresTestDatabase::adminUser);
        registry.add("spring.datasource.password", PostgresTestDatabase::adminPassword);
        registry.add("proovy.brain.base-url", () -> brain.baseUrl());
        registry.add("gcs.local-emulator.signer-private-key-pem", () -> ephemeralPem);
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

    @Autowired RunAssetAccessProvider accessProvider;
    @Autowired AiRunRepository aiRunRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void issuesSignedUrlsOnlyForApprovedAssetsAndAttachmentsInPostgres() {
        assertThat(accessProvider).isInstanceOf(StorageBackedRunAssetAccessProvider.class);

        UUID aliceUserMsg = UUID.randomUUID();
        UUID aliceAssistantMsg = UUID.randomUUID();
        UUID aliceAttachmentId = UUID.randomUUID();
        UUID aliceRunId = UUID.randomUUID();

        insertTurnMessages(ALICE_NOTE, aliceUserMsg, aliceAssistantMsg);
        jdbc.update("""
                INSERT INTO turn_attachments (
                    attachment_id, note_id, source_message_id, source_message_role, attachment_index,
                    object_key, sha256, mime_type, byte_size, pixel_width, pixel_height, pixel_count,
                    upload_policy_version, page_understanding_version, image_normalization_version
                ) VALUES (?, ?, ?, 'USER', 0, ?, ?, 'image/jpeg', 1024, 800, 600, 480000, 'v1', 'v1', 'v1')
                """,
                aliceAttachmentId, ALICE_NOTE, aliceUserMsg,
                "users/1001/notes/2001/attachments/photo.jpg", "a".repeat(64));

        String aliceSnapshot = """
                {
                  "input": {"text": "이 사진 풀어줘"},
                  "viewer_context": {"asset_id": "%d", "page": 1},
                  "turn_attachments": [{"attachment_id": "%s"}]
                }
                """.formatted(ALICE_PDF, aliceAttachmentId);
        insertPendingRun(aliceRunId, "req-218-alice", ALICE, ALICE_NOTE, aliceUserMsg, aliceAssistantMsg, aliceSnapshot);

        // VIEWER_FOCUS dependency가 없을 때는 viewerReadUrl 발급 거부, 소유 turn_attachment는 발급
        AiRun aliceRun = aiRunRepository.findById(aliceRunId).orElseThrow();
        RunAssetAccess withoutDep = accessProvider.accessFor(aliceRun, null);
        assertThat(withoutDep.viewerReadUrl()).isNull();
        assertThat(withoutDep.turnAttachmentReadUrls())
                .containsOnlyKeys(aliceAttachmentId.toString());
        assertThat(withoutDep.turnAttachmentReadUrls().get(aliceAttachmentId.toString()))
                .startsWith("https://macmini.tailnet.ts.net/proovy-local-assets/users/1001/notes/2001/attachments/photo.jpg?")
                .contains("X-Goog-Algorithm=GOOG4-RSA-SHA256")
                .contains("X-Goog-Signature=");

        // VIEWER_FOCUS dependency 추가 -> 승인된 viewerReadUrl 발급
        jdbc.update("""
                INSERT INTO run_asset_dependencies (run_id, note_id, asset_id, dependency_kind, content_version)
                VALUES (?, ?, ?, 'VIEWER_FOCUS', 1)
                """, aliceRunId, ALICE_NOTE, ALICE_PDF);

        RunAssetAccess approved = accessProvider.accessFor(aliceRun, null);
        assertThat(approved.viewerReadUrl())
                .startsWith("https://macmini.tailnet.ts.net/proovy-local-assets/users/1001/notes/2001/calculus.pdf?")
                .contains("X-Goog-Algorithm=GOOG4-RSA-SHA256")
                .contains("X-Goog-Signature=");

        // 다른 사용자/노트(BOB)가 ALICE의 asset·attachment를 snapshot에 넣어도 발급 거부
        UUID bobUserMsg = UUID.randomUUID();
        UUID bobAssistantMsg = UUID.randomUUID();
        UUID bobRunId = UUID.randomUUID();
        insertTurnMessages(BOB_NOTE, bobUserMsg, bobAssistantMsg);
        insertPendingRun(bobRunId, "req-218-bob-cross", BOB, BOB_NOTE, bobUserMsg, bobAssistantMsg, aliceSnapshot);
        AiRun bobRun = aiRunRepository.findById(bobRunId).orElseThrow();
        assertThat(accessProvider.accessFor(bobRun, null)).isEqualTo(RunAssetAccess.NONE);

        // DELETING 상태 asset은 dependency가 있어도 read_url 제외
        jdbc.update("""
                UPDATE assets
                SET status = 'DELETING', deletion_operation_id = ?, deletion_requested_at = now()
                WHERE id = ?
                """, UUID.randomUUID(), ALICE_PDF);
        assertThat(accessProvider.accessFor(aliceRun, null).viewerReadUrl()).isNull();

        // snapshot sha256/byte_size 검증: 일치할 때만 서명 URL 발급, DB 변경(불일치) 시 거부
        jdbc.update(
                "UPDATE ai_runs SET status = 'FAILED', completed_at = now(), "
                        + "failure_code = 'internal_error', failure_class = 'infrastructure' WHERE run_id = ?",
                aliceRunId);
        UUID aliceShaRunId = UUID.randomUUID();
        UUID shaUserMsg = UUID.randomUUID();
        UUID shaAssistantMsg = UUID.randomUUID();
        insertTurnMessages(ALICE_NOTE, shaUserMsg, shaAssistantMsg);
        String alicePdfSha = "e".repeat(64);
        long alicePdfSize = 54_321L;
        jdbc.update("UPDATE assets SET sha256 = ?, file_size = ? WHERE id = ?", alicePdfSha, alicePdfSize, ALICE_PDF);
        jdbc.update("UPDATE assets SET status = 'ACTIVE', deletion_operation_id = NULL, deletion_requested_at = NULL WHERE id = ?", ALICE_PDF);
        String shaSnapshot = """
                {
                  "input": {"text": "이 문서 풀어줘"},
                  "viewer_context": {
                    "asset_id": "%d",
                    "page": 1,
                    "sha256": "%s",
                    "byte_size": %d
                  }
                }
                """.formatted(ALICE_PDF, alicePdfSha, alicePdfSize);
        insertPendingRun(aliceShaRunId, "req-218-sha-check", ALICE, ALICE_NOTE, shaUserMsg, shaAssistantMsg, shaSnapshot);
        jdbc.update("""
                INSERT INTO run_asset_dependencies (run_id, note_id, asset_id, dependency_kind, content_version)
                VALUES (?, ?, ?, 'VIEWER_FOCUS', 1)
                """, aliceShaRunId, ALICE_NOTE, ALICE_PDF);
        AiRun aliceShaRun = aiRunRepository.findById(aliceShaRunId).orElseThrow();
        assertThat(accessProvider.accessFor(aliceShaRun, null).viewerReadUrl()).isNotNull();

        jdbc.update("UPDATE assets SET sha256 = ? WHERE id = ?", "f".repeat(64), ALICE_PDF);
        assertThat(accessProvider.accessFor(aliceShaRun, null).viewerReadUrl()).isNull();

        // 텍스트 전용 run은 RunAssetAccess.NONE 반환
        UUID textUserMsg = UUID.randomUUID();
        UUID textAssistantMsg = UUID.randomUUID();
        UUID textRunId = UUID.randomUUID();
        insertTurnMessages(ALICE_SECOND_NOTE, textUserMsg, textAssistantMsg);
        insertPendingRun(textRunId, "req-218-text", ALICE, ALICE_SECOND_NOTE, textUserMsg, textAssistantMsg,
                "{\"input\":{\"text\":\"2+2\"}}");
        AiRun textRun = aiRunRepository.findById(textRunId).orElseThrow();
        assertThat(accessProvider.accessFor(textRun, null)).isEqualTo(RunAssetAccess.NONE);
    }

    private void insertTurnMessages(long noteId, UUID userMsg, UUID assistantMsg) {
        jdbc.update("""
                INSERT INTO chat_messages (message_id, note_id, role, status, user_input, plain_text)
                VALUES (?, ?, 'USER', 'COMPLETED', '{"text":"q"}', 'q'),
                       (?, ?, 'ASSISTANT', 'PENDING', NULL, NULL)
                """, userMsg, noteId, assistantMsg, noteId);
    }

    private void insertPendingRun(
            UUID runId, String reqId, long userId, long noteId, UUID userMsg, UUID assistantMsg, String snapshotJson) {
        jdbc.update("""
                INSERT INTO ai_runs (
                    run_id, request_id, user_id, note_id, user_message_id, assistant_message_id,
                    request_fingerprint, request_snapshot, policy_snapshot, budget_policy_version, request_deadline_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, '{"limits":{}}', 'budget-v2', now() + interval '10 minutes')
                """,
                runId, reqId, userId, noteId, userMsg, assistantMsg, FINGERPRINT, snapshotJson);
    }
}
