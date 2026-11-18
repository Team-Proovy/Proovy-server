package com.proovy.domain.asset.service;

import static com.proovy.support.db.ProductRows.ALICE;
import static com.proovy.support.db.ProductRows.ALICE_NOTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.gcs.GcsService;
import com.proovy.global.response.ErrorCode;
import com.proovy.global.util.HashUtils;
import com.proovy.support.db.PostgresTestDatabase;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 Spring proxy + PlatformTransactionManager(REQUIRES_NEW) + Postgres에서
 * confirmUpload 검증 거부/일시적 오류/동시 확정 시 PENDING 행·GCS 정리 경계를 검증한다.
 */
@Tag("postgres")
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "gcs.project-id=proovy-test",
        "gcs.bucket=proovy-local-assets",
        "gcs.endpoint=http://127.0.0.1:1",
        "jwt.secret=test-jwt-secret-test-jwt-secret-0123456789",
        "oauth.kakao.client-id=test", "oauth.kakao.client-secret=test",
        "oauth.naver.client-id=test", "oauth.naver.client-secret=test",
        "openrouter.api.key=test",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.task.scheduling.pool.size=1",
        "proovy.subscription.plan-transition-fixed-delay-ms=3600000",
        "proovy.subscription.auto-renew-fixed-delay-ms=3600000"
})
class AssetsConfirmPostgresTest {

    private static PostgresTestDatabase db;

    @BeforeAll
    static void setUp() {
        db = PostgresTestDatabase.createMigrated();
        db.runScript("db/seed/product-seed.sql");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> db.jdbcUrl());
        registry.add("spring.datasource.username", PostgresTestDatabase::adminUser);
        registry.add("spring.datasource.password", PostgresTestDatabase::adminPassword);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Autowired AssetsService assetsService;
    @Autowired AssetRepository assetRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @MockitoBean GcsService gcsService;

    @Test
    void validationRejectionCommitsRequiresNewPendingDeletionEvenWhenOuterTxRollsBack() {
        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("spoof.png")
                .fileSize(128L)
                .mimeType("image/png")
                .objectKey("users/1001/notes/2001/assets/spoof.png")
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build());

        String stagingKey = pending.getObjectKey();
        when(gcsService.readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(new GcsService.PinnedBlobBytes("not-a-png".getBytes(StandardCharsets.UTF_8), 31L));

        assertThatThrownBy(() -> assetsService.confirmUpload(ALICE, pending.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4001);

        assertThat(assetRepository.findById(pending.getId())).isEmpty();
        InOrder order = inOrder(gcsService);
        order.verify(gcsService).readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002));
        order.verify(gcsService).deleteFile(stagingKey, 31L);
    }

    @Test
    void validationRejectionKeepsFailedRowUntilScheduledCleanupDeletesStorageObject() {
        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("orphan-retry.png")
                .fileSize(128L)
                .mimeType("image/png")
                .objectKey("users/1001/notes/2001/assets/uuid-orphan_retry.png")
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build());

        String stagingKey = pending.getObjectKey();
        when(gcsService.readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(new GcsService.PinnedBlobBytes("not-a-png".getBytes(StandardCharsets.UTF_8), 55L));
        doThrow(new BusinessException(ErrorCode.COMMON500))
                .doThrow(new BusinessException(ErrorCode.COMMON500))
                .doNothing()
                .when(gcsService).deleteFile(stagingKey, 55L);

        assertThatThrownBy(() -> assetsService.confirmUpload(ALICE, pending.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4001);

        verify(gcsService, times(2)).deleteFile(stagingKey, 55L);
        assertThat(assetRepository.findById(pending.getId()))
                .get()
                .extracting(Asset::getStatus)
                .isEqualTo(AssetStatus.FAILED);

        // 재시도 스케줄러가 pinned generation 삭제 + 키 정리 후 FAILED 추적 행을 제거한다
        assetsService.cleanupFailedUploadAssets();

        verify(gcsService, times(3)).deleteFile(stagingKey, 55L);
        verify(gcsService).deleteFile(stagingKey);
        assertThat(assetRepository.findById(pending.getId())).isEmpty();
    }

    @Test
    void validationRejectionRetainsFailedRowUntilWriteWindowExpiryAndSweepsAfterExpiry() {
        String stagingKey = "users/1001/notes/2001/assets/uuid-unexpired_rej.png";
        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("unexpired-rej.png")
                .fileSize(128L)
                .mimeType("image/png")
                .objectKey(stagingKey)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .uploadExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build());

        when(gcsService.readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(new GcsService.PinnedBlobBytes("not-a-png".getBytes(StandardCharsets.UTF_8), 61L));

        assertThatThrownBy(() -> assetsService.confirmUpload(ALICE, pending.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4001);

        verify(gcsService).deleteFile(stagingKey, 61L);
        verify(gcsService, never()).deleteFile(stagingKey);
        Asset retained = assetRepository.findById(pending.getId()).orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(AssetStatus.FAILED);
        assertThat(retained.getStagingObjectKey()).isEqualTo(stagingKey);
        assertThat(retained.getStagingGeneration()).isNull();

        // 만료 전에는 signed PUT 재업로드 가능 구간이므로 키 추적 행을 유지한다
        assetsService.cleanupFailedUploadAssets();
        verify(gcsService, never()).deleteFile(stagingKey);
        assertThat(assetRepository.findById(pending.getId())).isPresent();

        // 서명 만료 후에는 키 전체를 sweep하고 FAILED 행을 삭제한다
        jdbcTemplate.update(
                "UPDATE assets SET upload_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                pending.getId());
        assetsService.cleanupFailedUploadAssets();

        verify(gcsService).deleteFile(stagingKey);
        assertThat(assetRepository.findById(pending.getId())).isEmpty();
    }

    @Test
    void transientReadFailurePreservesPendingRowAndStorageObject() {
        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("transient.png")
                .fileSize(128L)
                .mimeType("image/png")
                .objectKey("users/1001/notes/2001/assets/transient.png")
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build());

        when(gcsService.readFileBytesBoundedPinned(eq(pending.getObjectKey()), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenThrow(new BusinessException(ErrorCode.COMMON500));

        assertThatThrownBy(() -> assetsService.confirmUpload(ALICE, pending.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COMMON500);

        assertThat(assetRepository.findById(pending.getId()))
                .get()
                .extracting(Asset::getStatus)
                .isEqualTo(AssetStatus.PENDING);
        verify(gcsService, never()).deleteFile(any());
        verify(gcsService, never()).deleteFile(any(), any());
    }

    @Test
    void concurrentActiveAssetIsPreservedWhenRejectedUploadCleanupRuns() {
        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("concurrent-active.png")
                .fileSize(128L)
                .mimeType("image/png")
                .objectKey("users/1001/notes/2001/assets/concurrent-active.png")
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build());

        when(gcsService.readFileBytesBoundedPinned(eq(pending.getObjectKey()), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenAnswer(invocation -> {
                    TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
                    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    requiresNew.executeWithoutResult(status -> {
                        Asset locked = assetRepository.findById(pending.getId()).orElseThrow();
                        locked.confirmMetadata("a".repeat(64), 128L, null, 4, 4, 16L);
                        assetRepository.saveAndFlush(locked);
                    });
                    return new GcsService.PinnedBlobBytes("not-a-png".getBytes(StandardCharsets.UTF_8), 66L);
                });

        assertThatThrownBy(() -> assetsService.confirmUpload(ALICE, pending.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4001);

        assertThat(assetRepository.findById(pending.getId()))
                .get()
                .extracting(Asset::getStatus)
                .isEqualTo(AssetStatus.ACTIVE);
        verify(gcsService, never()).deleteFile(pending.getObjectKey());
        verify(gcsService, never()).deleteFile(pending.getObjectKey(), 66L);
    }

    @Test
    void confirmUploadPromotesImmutableKeyAndDeletesStagingGenerationAfterCommit() throws Exception {
        BufferedImage img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 32, 24);
        g.dispose();
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        ImageIO.write(img, "png", pngOut);
        byte[] png = pngOut.toByteArray();

        String stagingKey = "users/1001/notes/2001/assets/uuid-imm_ok.png";
        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("ok.png")
                .fileSize((long) png.length)
                .mimeType("image/png")
                .objectKey(stagingKey)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build());

        when(gcsService.readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(new GcsService.PinnedBlobBytes(png, 88L));

        assetsService.confirmUpload(ALICE, pending.getId());

        Asset confirmed = assetRepository.findById(pending.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(AssetStatus.ACTIVE);
        assertThat(confirmed.getObjectKey()).startsWith("users/1001/notes/2001/confirmed/uuid-imm-");
        assertThat(confirmed.getThumbnailObjectKey()).startsWith("users/1001/notes/2001/thumbnails/uuid-imm-");
        verify(gcsService).uploadFile(eq(confirmed.getObjectKey()), any(), eq((long) png.length), eq("image/png"));
        verify(gcsService).uploadFile(eq(confirmed.getThumbnailObjectKey()), any(), anyLong(), eq("image/jpeg"));
        verify(gcsService).deleteFile(stagingKey, 88L);
    }

    @Test
    void rollbackAfterConfirmedCopyDeletesUnreferencedConfirmedObjectAndClearsIntent() throws Exception {
        byte[] png = encodeTestPng(24, 24, Color.GREEN);
        String stagingKey = "users/1001/notes/2001/assets/uuid-rb_ok.png";
        String expectedConfirmed = AssetsServiceImpl.buildConfirmedS3Key(stagingKey, HashUtils.sha256(png));

        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("rb.png")
                .fileSize((long) png.length)
                .mimeType("image/png")
                .objectKey(stagingKey)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build());

        when(gcsService.readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(new GcsService.PinnedBlobBytes(png, 91L));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assetsService.confirmUpload(ALICE, pending.getId());
            status.setRollbackOnly();
        });

        verify(gcsService).uploadFile(eq(expectedConfirmed), any(), eq((long) png.length), eq("image/png"));
        verify(gcsService).deleteFile(expectedConfirmed);

        Asset afterRollback = assetRepository.findById(pending.getId()).orElseThrow();
        assertThat(afterRollback.getStatus()).isEqualTo(AssetStatus.PENDING);
        assertThat(afterRollback.getObjectKey()).isEqualTo(stagingKey);
        assertThat(afterRollback.getPendingConfirmedObjectKey()).isNull();
    }

    @Test
    void competingGenerationRaceRejectsSecondConfirmationBeforeUploadingSecondCopy() throws Exception {
        byte[] png1 = encodeTestPng(30, 20, Color.WHITE);
        byte[] png2 = encodeTestPng(30, 20, Color.BLACK);
        String stagingKey = "users/1001/notes/2001/assets/uuid-race_ok.png";
        String confirmed1 = AssetsServiceImpl.buildConfirmedS3Key(stagingKey, HashUtils.sha256(png1));
        String confirmed2 = AssetsServiceImpl.buildConfirmedS3Key(stagingKey, HashUtils.sha256(png2));

        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("race.png")
                .fileSize((long) png1.length)
                .mimeType("image/png")
                .objectKey(stagingKey)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build());

        when(gcsService.readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(
                        new GcsService.PinnedBlobBytes(png1, 101L),
                        new GcsService.PinnedBlobBytes(png2, 102L));

        doAnswer(invocation -> {
            // 첫 번째 요청이 durable intent를 선점한 직후, 별도 요청 트랜잭션에서 두 번째 세대가 confirmUpload에 진입한 경쟁 재현
            TransactionTemplate competingRequestTx = new TransactionTemplate(transactionManager);
            competingRequestTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThatThrownBy(() -> competingRequestTx.executeWithoutResult(
                            status -> assetsService.confirmUpload(ALICE, pending.getId())))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ASSET4091);
            return "https://example.test/" + confirmed1;
        }).when(gcsService).uploadFile(eq(confirmed1), any(), eq((long) png1.length), eq("image/png"));

        assetsService.confirmUpload(ALICE, pending.getId());

        verify(gcsService, never()).uploadFile(eq(confirmed2), any(), anyLong(), anyString());
        Asset finalized = assetRepository.findById(pending.getId()).orElseThrow();
        assertThat(finalized.getStatus()).isEqualTo(AssetStatus.ACTIVE);
        assertThat(finalized.getObjectKey()).isEqualTo(confirmed1);
        assertThat(finalized.getPendingConfirmedObjectKey()).isNull();
    }

    @Test
    void successPathRetainsStagingThroughWriteWindowExpiryAndRecoversFailedGenerationDelete() throws Exception {
        byte[] png = encodeTestPng(28, 22, Color.BLUE);
        String stagingKey = "users/1001/notes/2001/assets/uuid-succ-cleanup_ok.png";

        Asset pending = assetRepository.saveAndFlush(Asset.builder()
                .userId(ALICE)
                .noteId(ALICE_NOTE)
                .fileName("succ.png")
                .fileSize((long) png.length)
                .mimeType("image/png")
                .objectKey(stagingKey)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .uploadExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build());

        when(gcsService.readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(new GcsService.PinnedBlobBytes(png, 111L));
        doThrow(new BusinessException(ErrorCode.COMMON500))
                .doNothing()
                .when(gcsService).deleteFile(stagingKey, 111L);

        assetsService.confirmUpload(ALICE, pending.getId());

        Asset confirmed = assetRepository.findById(pending.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(AssetStatus.ACTIVE);
        assertThat(confirmed.getStagingObjectKey()).isEqualTo(stagingKey);
        assertThat(confirmed.getStagingGeneration()).isEqualTo(111L);

        // 첫 scheduled 실행: 실패했던 pinned generation 111L을 재시도 삭제하되, 미만료 key 추적은 유지
        assetsService.cleanupFailedUploadAssets();
        verify(gcsService, times(2)).deleteFile(stagingKey, 111L);
        verify(gcsService, never()).deleteFile(stagingKey);

        Asset afterGenRetry = assetRepository.findById(pending.getId()).orElseThrow();
        assertThat(afterGenRetry.getStagingGeneration()).isNull();
        assertThat(afterGenRetry.getStagingObjectKey()).isEqualTo(stagingKey);

        // signed URL 만료 후 scheduled 실행: replacement 가능성까지 포함해 staging key 전체 sweep
        jdbcTemplate.update(
                "UPDATE assets SET upload_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                pending.getId());
        assetsService.cleanupFailedUploadAssets();

        verify(gcsService).deleteFile(stagingKey);
        Asset afterExpirySweep = assetRepository.findById(pending.getId()).orElseThrow();
        assertThat(afterExpirySweep.getStagingObjectKey()).isNull();
        assertThat(afterExpirySweep.getStagingGeneration()).isNull();
    }

    private static byte[] encodeTestPng(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
