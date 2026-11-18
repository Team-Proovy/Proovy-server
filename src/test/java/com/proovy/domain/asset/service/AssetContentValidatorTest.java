package com.proovy.domain.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.domain.note.repository.NoteRepository;
import com.proovy.domain.run.admission.RunAssetDeletionGuard;
import com.proovy.domain.user.entity.PlanType;
import com.proovy.domain.user.repository.UserPlanRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.gcs.GcsLocalSigning;
import com.proovy.global.infra.gcs.GcsService;
import com.proovy.global.infra.gcs.GcsServiceImpl;
import com.proovy.global.infra.thumbnail.ThumbnailService;
import com.proovy.global.response.ErrorCode;
import com.proovy.global.util.HashUtils;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class AssetContentValidatorTest {

    // Real Pillow-encoded 32x24 WebP fixtures covering VP8, VP8L, VP8X+ICCP+VP8L, and VP8X+ICCP+ALPH+VP8
    private static final byte[] REAL_WEBP_VP8 = Base64.getDecoder().decode(
            "UklGRj4AAABXRUJQVlA4IDIAAADwAgCdASogABgAPm0ylUekIyIhKAgAgA2JaQAAPjFjAAD++iGXzse4P+g+Gn/kFwAAAA==");
    private static final byte[] REAL_WEBP_VP8L = Base64.getDecoder().decode(
            "UklGRh4AAABXRUJQVlA4TBEAAAAvH8AFAAdQiirUo/+BiOh/AAA=");
    private static final byte[] REAL_WEBP_VP8X_VP8L = Base64.getDecoder().decode(
            "UklGRoQCAABXRUJQVlA4WAoAAAAgAAAAHwAAFwAASUNDUEwCAAAAAAJMbGNtcwRAAABtbnRyUkdCIFhZWiAH6gALABIAEAAsABphY3NwQVBQTAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9tYAAQAAAADTLWxjbXMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAtkZXNjAAABCAAAADZjcHJ0AAABQAAAAEx3dHB0AAABjAAAABRjaGFkAAABoAAAACxyWFlaAAABzAAAABRiWFlaAAAB4AAAABRnWFlaAAAB9AAAABRyVFJDAAACCAAAACBnVFJDAAACCAAAACBiVFJDAAACCAAAACBjaHJtAAACKAAAACRtbHVjAAAAAAAAAAEAAAAMZW5VUwAAABoAAAAcAHMAUgBHAEIAIABiAHUAaQBsAHQALQBpAG4AAG1sdWMAAAAAAAAAAQAAAAxlblVTAAAAMAAAABwATgBvACAAYwBvAHAAeQByAGkAZwBoAHQALAAgAHUAcwBlACAAZgByAGUAZQBsAHlYWVogAAAAAAAA9tYAAQAAAADTLXNmMzIAAAAAAAEMQgAABd7///MlAAAHkwAA/ZD///uh///9ogAAA9wAAMBuWFlaIAAAAAAAAG+gAAA49QAAA5BYWVogAAAAAAAAJJ8AAA+EAAC2w1hZWiAAAAAAAABilwAAt4cAABjZcGFyYQAAAAAAAwAAAAJmZgAA8qcAAA1ZAAAT0AAACltjaHJtAAAAAAADAAAAAKPXAABUewAATM0AAJmaAAAmZgAAD1xWUDhMEQAAAC8fwAUAB1CKKtSj/4GI6H8AAA==");
    private static final byte[] REAL_WEBP_VP8X_ALPH_VP8 = Base64.getDecoder().decode(
            "UklGRrYCAABXRUJQVlA4WAoAAAAwAAAAHwAAFwAASUNDUEwCAAAAAAJMbGNtcwRAAABtbnRyUkdCIFhZWiAH6gALABIAEAAsABphY3NwQVBQTAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA9tYAAQAAAADTLWxjbXMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAtkZXNjAAABCAAAADZjcHJ0AAABQAAAAEx3dHB0AAABjAAAABRjaGFkAAABoAAAACxyWFlaAAABzAAAABRiWFlaAAAB4AAAABRnWFlaAAAB9AAAABRyVFJDAAACCAAAACBnVFJDAAACCAAAACBiVFJDAAACCAAAACBjaHJtAAACKAAAACRtbHVjAAAAAAAAAAEAAAAMZW5VUwAAABoAAAAcAHMAUgBHAEIAIABiAHUAaQBsAHQALQBpAG4AAG1sdWMAAAAAAAAAAQAAAAxlblVTAAAAMAAAABwATgBvACAAYwBvAHAAeQByAGkAZwBoAHQALAAgAHUAcwBlACAAZgByAGUAZQBsAHlYWVogAAAAAAAA9tYAAQAAAADTLXNmMzIAAAAAAAEMQgAABd7///MlAAAHkwAA/ZD///uh///9ogAAA9wAAMBuWFlaIAAAAAAAAG+gAAA49QAAA5BYWVogAAAAAAAAJJ8AAA+EAAC2w1hZWiAAAAAAAABilwAAt4cAABjZcGFyYQAAAAAAAwAAAAJmZgAA8qcAAA1ZAAAT0AAACltjaHJtAAAAAAADAAAAAKPXAABUewAATM0AAJmaAAAmZgAAD1xBTFBICgAAAAEHUMCICERE/wNWUDggMgAAAPACAJ0BKiAAGAA+bTKVR6QjIiEoCACADYlpAAA+MWMAAP76IZfOx7g/6D4af+QXAAAA");

    private final AssetContentValidator validator = new AssetContentValidator();

    @Test
    void validatesPngJpegRealWebpAndPdfMetadata() throws Exception {
        byte[] png = encodeImage("png", 120, 80);
        var pngMeta = validator.validate(png, "image/png");
        assertThat(pngMeta.sha256()).isEqualTo(HashUtils.sha256(png));
        assertThat(pngMeta.byteSize()).isEqualTo(png.length);
        assertThat(pngMeta.pageCount()).isNull();
        assertThat(pngMeta.pixelWidth()).isEqualTo(120);
        assertThat(pngMeta.pixelHeight()).isEqualTo(80);
        assertThat(pngMeta.pixelCount()).isEqualTo(9_600L);

        byte[] jpeg = encodeImage("jpeg", 64, 48);
        var jpegMeta = validator.validate(jpeg, "image/jpeg");
        assertThat(jpegMeta.pixelWidth()).isEqualTo(64);
        assertThat(jpegMeta.pixelHeight()).isEqualTo(48);
        assertThat(jpegMeta.pixelCount()).isEqualTo(3_072L);

        for (byte[] webp : new byte[][] {REAL_WEBP_VP8, REAL_WEBP_VP8L, REAL_WEBP_VP8X_VP8L, REAL_WEBP_VP8X_ALPH_VP8}) {
            var webpMeta = validator.validate(webp, "image/webp");
            assertThat(webpMeta.pixelWidth()).isEqualTo(32);
            assertThat(webpMeta.pixelHeight()).isEqualTo(24);
            assertThat(webpMeta.pixelCount()).isEqualTo(768L);
        }

        byte[] pdf = createPdf(3);
        var pdfMeta = validator.validate(pdf, "application/pdf");
        assertThat(pdfMeta.sha256()).isEqualTo(HashUtils.sha256(pdf));
        assertThat(pdfMeta.pageCount()).isEqualTo(3);
        assertThat(pdfMeta.pixelWidth()).isNull();
        assertThat(pdfMeta.pixelHeight()).isNull();
        assertThat(pdfMeta.pixelCount()).isNull();
    }

    @Test
    void rejectsSpoofedCorruptEncryptedPdfAndMalformedWebp() throws Exception {
        byte[] png = encodeImage("png", 32, 32);
        assertRejected(() -> validator.validate(png, "image/jpeg"), ErrorCode.ASSET4001);
        assertRejected(() -> validator.validate(png, "application/pdf"), ErrorCode.ASSET4001);
        assertRejected(() -> validator.validate("not-a-pdf".getBytes(StandardCharsets.UTF_8), "application/pdf"),
                ErrorCode.ASSET4001);

        byte[] encryptedPdf = createEncryptedPdf();
        assertRejected(() -> validator.validate(encryptedPdf, "application/pdf"), ErrorCode.ASSET4001);

        byte[] zeroPageBoxPdf = createPdfWithMediaBox(0f, 0f);
        assertRejected(() -> validator.validate(zeroPageBoxPdf, "application/pdf"), ErrorCode.ASSET4001);

        byte[] pdf101 = createPdf(101);
        assertRejected(() -> validator.validate(pdf101, "application/pdf"), ErrorCode.ASSET4002);

        // Header-only VP8X WebP without an image frame chunk must be rejected
        assertRejected(() -> validator.validate(headerOnlyWebpVp8x(1024, 768), "image/webp"), ErrorCode.ASSET4001);

        // Truncated VP8L chunk must be rejected
        byte[] truncatedVp8l = new byte[REAL_WEBP_VP8L.length - 4];
        System.arraycopy(REAL_WEBP_VP8L, 0, truncatedVp8l, 0, truncatedVp8l.length);
        assertRejected(() -> validator.validate(truncatedVp8l, "image/webp"), ErrorCode.ASSET4001);

        // VP8X canvas dimension mismatch with enclosed VP8L frame must be rejected
        byte[] mismatchedWebp = validWebpVp8xWithVp8l(64, 48, 32, 24);
        assertRejected(() -> validator.validate(mismatchedWebp, "image/webp"), ErrorCode.ASSET4001);

        // Valid VP8X + VP8L headers, valid standalone VP8 header, and truncated VP8 bitstream payload must be rejected
        byte[] headerOnlyPayloadWebp = validWebpVp8xWithVp8l(32, 24, 32, 24);
        assertRejected(() -> validator.validate(headerOnlyPayloadWebp, "image/webp"), ErrorCode.ASSET4001);
        assertRejected(() -> validator.validate(headerOnlyWebpVp8(32, 24), "image/webp"), ErrorCode.ASSET4001);
        assertRejected(() -> validator.validate(truncatedRealWebpVp8(), "image/webp"), ErrorCode.ASSET4001);

        // Animated WebP must be rejected explicitly
        byte[] animatedWebp = animatedWebpVp8x();
        assertRejected(() -> validator.validate(animatedWebp, "image/webp"), ErrorCode.ASSET4001);

        // PDF with ordinary page geometry but an oversized embedded XObject or SMask (>40MP) must be rejected
        byte[] pdfHugeXObject = createPdfWithEmbeddedImageXObject(8_000, 6_000, false);
        assertRejected(() -> validator.validate(pdfHugeXObject, "application/pdf"), ErrorCode.ASSET4002);

        byte[] pdfHugeSmask = createPdfWithEmbeddedImageXObject(8_000, 6_000, true);
        assertRejected(() -> validator.validate(pdfHugeSmask, "application/pdf"), ErrorCode.ASSET4002);

        // Inline images in page content streams (oversized geometry and Flate expansion bomb) must be rejected
        byte[] pdfOversizedInline = createPdfWithOversizedInlineImage(8_000, 6_000);
        assertRejected(() -> validator.validate(pdfOversizedInline, "application/pdf"), ErrorCode.ASSET4002);

        byte[] pdfInlineBomb = createPdfWithFlateBombInlineImage(65_536);
        assertRejected(() -> validator.validate(pdfInlineBomb, "application/pdf"), ErrorCode.ASSET4002);

        // 15MB 초과 이미지 거부
        byte[] oversized = new byte[(int) AssetContentValidator.IMAGE_MAX_BYTES + 1];
        oversized[0] = (byte) 0x89;
        oversized[1] = 'P';
        oversized[2] = 'N';
        oversized[3] = 'G';
        assertRejected(() -> validator.validate(oversized, "image/png"), ErrorCode.ASSET4002);

        // 40MP 초과 WebP (7000 x 6000 = 42,000,000) 거부
        byte[] highResWebp = validWebpVp8xWithVp8l(7_000, 6_000, 7_000, 6_000);
        assertRejected(() -> validator.validate(highResWebp, "image/webp"), ErrorCode.ASSET4002);
    }

    @Test
    void readFileBytesBoundedStopsBeforeOpeningChannelWhenMetadataExceedsLimit() {
        Storage storage = mock(Storage.class);
        Blob blob = mock(Blob.class);
        BlobId pinnedId = BlobId.of("bucket", "k", 99L);
        when(storage.get(BlobId.of("bucket", "k"))).thenReturn(blob);
        when(blob.exists()).thenReturn(true);
        when(blob.getSize()).thenReturn(20L * 1024 * 1024);

        GcsServiceImpl gcs = new GcsServiceImpl(storage, "bucket", null, GcsLocalSigning.disabled());

        assertRejected(() -> gcs.readFileBytesBounded("k", 10L * 1024 * 1024, ErrorCode.ASSET4002),
                ErrorCode.ASSET4002);
        verify(storage, never()).reader(any(BlobId.class));
        verify(storage, never()).readAllBytes(any(), any());
    }

    @Test
    void readFileBytesBoundedAbortsStreamingWhenUnboundedStreamExceedsLimit() throws Exception {
        Storage storage = mock(Storage.class);
        Blob blob = mock(Blob.class);
        BlobId pinnedId = BlobId.of("bucket", "k", 77L);
        when(storage.get(BlobId.of("bucket", "k"))).thenReturn(blob);
        when(blob.exists()).thenReturn(true);
        when(blob.getSize()).thenReturn(null);
        when(blob.getBlobId()).thenReturn(pinnedId);

        ReadChannel channel = mock(ReadChannel.class);
        when(storage.reader(pinnedId)).thenReturn(channel);
        when(channel.read(any(ByteBuffer.class))).thenAnswer(inv -> {
            ByteBuffer bb = inv.getArgument(0);
            int n = Math.min(bb.remaining(), 4096);
            bb.position(bb.position() + n);
            return n;
        });

        GcsServiceImpl gcs = new GcsServiceImpl(storage, "bucket", null, GcsLocalSigning.disabled());

        assertRejected(() -> gcs.readFileBytesBounded("k", 1024L, ErrorCode.ASSET4002),
                ErrorCode.ASSET4002);
        verify(channel).close();
    }

    @Test
    void confirmUploadRejectsOversizedOrPlanBypassedUploadBeforeDecode() {
        AssetRepository assetRepo = mock(AssetRepository.class);
        NoteRepository noteRepo = mock(NoteRepository.class);
        GcsService gcsService = mock(GcsService.class);
        UserPlanRepository planRepo = mock(UserPlanRepository.class);
        ThumbnailService thumbnailService = mock(ThumbnailService.class);
        RunAssetDeletionGuard deletionGuard = mock(RunAssetDeletionGuard.class);
        ApplicationContext appCtx = mock(ApplicationContext.class);
        AssetContentValidator validatorSpy = spy(validator);

        AssetsServiceImpl service = new AssetsServiceImpl(
                assetRepo, noteRepo, gcsService, planRepo, thumbnailService, deletionGuard, validatorSpy, null, appCtx);

        // Client declared 1MB on FREE plan (10MB cap), PDF global max is 20MB -> effective cap must be 10MB
        Asset pending = Asset.builder()
                .userId(1L)
                .noteId(10L)
                .fileName("huge.pdf")
                .fileSize(1024L * 1024L)
                .mimeType("application/pdf")
                .objectKey("users/1/notes/10/assets/uuid_huge.pdf")
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", 75L);

        when(assetRepo.findById(75L)).thenReturn(Optional.of(pending));
        when(planRepo.findActivePlanTypeByUserId(1L)).thenReturn(Optional.of(PlanType.FREE));
        when(gcsService.readFileBytesBoundedPinned(pending.getObjectKey(), 10L * 1024 * 1024, ErrorCode.ASSET4002))
                .thenThrow(new BusinessException(ErrorCode.ASSET4002));

        assertThatThrownBy(() -> service.confirmUpload(1L, 75L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4002);

        verify(validatorSpy, never()).validate(any(), any());
        verify(assetRepo).saveAndFlush(pending);
        verify(gcsService).deleteFile(pending.getObjectKey());
        verify(assetRepo).delete(pending);
    }

    @Test
    void confirmUploadPreservesPendingAndObjectOnTransientGcsFailure() {
        AssetRepository assetRepo = mock(AssetRepository.class);
        NoteRepository noteRepo = mock(NoteRepository.class);
        GcsService gcsService = mock(GcsService.class);
        UserPlanRepository planRepo = mock(UserPlanRepository.class);
        ThumbnailService thumbnailService = mock(ThumbnailService.class);
        RunAssetDeletionGuard deletionGuard = mock(RunAssetDeletionGuard.class);
        ApplicationContext appCtx = mock(ApplicationContext.class);

        AssetsServiceImpl service = new AssetsServiceImpl(
                assetRepo, noteRepo, gcsService, planRepo, thumbnailService, deletionGuard, validator, null, appCtx);

        Asset pending = Asset.builder()
                .userId(1L)
                .noteId(10L)
                .fileName("doc.pdf")
                .fileSize(1000L)
                .mimeType("application/pdf")
                .objectKey("users/1/notes/10/assets/uuid_doc.pdf")
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", 76L);

        when(assetRepo.findById(76L)).thenReturn(Optional.of(pending));
        when(planRepo.findActivePlanTypeByUserId(1L)).thenReturn(Optional.of(PlanType.PRO));
        when(gcsService.readFileBytesBoundedPinned(eq(pending.getObjectKey()), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenThrow(new BusinessException(ErrorCode.COMMON500));

        assertThatThrownBy(() -> service.confirmUpload(1L, 76L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COMMON500);

        verify(assetRepo, never()).delete(any());
        verify(gcsService, never()).deleteFile(any());
        verify(gcsService, never()).deleteFile(any(), any());
    }

    @Test
    void confirmUploadMarksFailedDeletesPinnedStorageGenerationThenRemovesRowOnValidationRejection() throws Exception {
        AssetRepository assetRepo = mock(AssetRepository.class);
        NoteRepository noteRepo = mock(NoteRepository.class);
        GcsService gcsService = mock(GcsService.class);
        UserPlanRepository planRepo = mock(UserPlanRepository.class);
        ThumbnailService thumbnailService = mock(ThumbnailService.class);
        RunAssetDeletionGuard deletionGuard = mock(RunAssetDeletionGuard.class);
        ApplicationContext appCtx = mock(ApplicationContext.class);

        AssetsServiceImpl service = new AssetsServiceImpl(
                assetRepo, noteRepo, gcsService, planRepo, thumbnailService, deletionGuard, validator, null, appCtx);

        Asset pending = Asset.builder()
                .userId(1L)
                .noteId(10L)
                .fileName("fake.png")
                .fileSize(100L)
                .mimeType("image/png")
                .objectKey("users/1/notes/10/assets/uuid_fake.png")
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", 77L);

        when(assetRepo.findById(77L)).thenReturn(Optional.of(pending));
        when(planRepo.findActivePlanTypeByUserId(1L)).thenReturn(Optional.of(PlanType.FREE));
        when(gcsService.readFileBytesBoundedPinned(eq(pending.getObjectKey()), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(new GcsService.PinnedBlobBytes(createPdf(1), 42L));

        assertThatThrownBy(() -> service.confirmUpload(1L, 77L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSET4001);

        InOrder order = Mockito.inOrder(assetRepo, gcsService);
        order.verify(assetRepo).saveAndFlush(pending);
        order.verify(gcsService).deleteFile(pending.getObjectKey(), 42L);
        order.verify(assetRepo).delete(pending);
        order.verify(assetRepo).flush();
        verify(thumbnailService, never()).generateThumbnailSync(any(), any(), any());
    }

    @Test
    void confirmUploadPromotesVerifiedBytesToImmutableKeyAndUsesVerifiedBytesForThumbnail() throws Exception {
        AssetRepository assetRepo = mock(AssetRepository.class);
        NoteRepository noteRepo = mock(NoteRepository.class);
        GcsService gcsService = mock(GcsService.class);
        UserPlanRepository planRepo = mock(UserPlanRepository.class);
        ThumbnailService thumbnailService = mock(ThumbnailService.class);
        RunAssetDeletionGuard deletionGuard = mock(RunAssetDeletionGuard.class);
        ApplicationContext appCtx = mock(ApplicationContext.class);

        AssetsServiceImpl service = new AssetsServiceImpl(
                assetRepo, noteRepo, gcsService, planRepo, thumbnailService, deletionGuard, validator, null, appCtx);

        byte[] png = encodeImage("png", 320, 240);
        String stagingKey = "users/1/notes/10/assets/uuid_ok.png";
        String expectedSha = HashUtils.sha256(png);
        String confirmedKey = AssetsServiceImpl.buildConfirmedS3Key(stagingKey, expectedSha);
        String thumbKey = "users/1/notes/10/thumbnails/uuid-" + expectedSha.substring(0, 16) + "_thumb.jpg";

        Asset pending = Asset.builder()
                .userId(1L)
                .noteId(10L)
                .fileName("ok.png")
                .fileSize((long) png.length)
                .mimeType("image/png")
                .objectKey(stagingKey)
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(pending, "id", 88L);

        when(assetRepo.findById(88L)).thenReturn(Optional.of(pending));
        when(planRepo.findActivePlanTypeByUserId(1L)).thenReturn(Optional.of(PlanType.FREE));
        when(gcsService.readFileBytesBoundedPinned(eq(stagingKey), anyLong(), eq(ErrorCode.ASSET4002)))
                .thenReturn(new GcsService.PinnedBlobBytes(png, 77L));
        when(thumbnailService.generateThumbnailSync(png, confirmedKey, "image/png"))
                .thenReturn(thumbKey);
        when(gcsService.getThumbnailUrl(thumbKey))
                .thenReturn("https://example.test/thumb.jpg");

        var response = service.confirmUpload(1L, 88L);

        assertThat(response.getAssetId()).isEqualTo(88L);
        assertThat(pending.getStatus()).isEqualTo(AssetStatus.ACTIVE);
        assertThat(pending.getObjectKey()).isEqualTo(confirmedKey);
        assertThat(confirmedKey).contains("/confirmed/uuid-" + expectedSha.substring(0, 16) + "_ok.png");
        assertThat(pending.getSha256()).isEqualTo(expectedSha);
        assertThat(pending.getPixelWidth()).isEqualTo(320);
        assertThat(pending.getPixelHeight()).isEqualTo(240);
        assertThat(pending.getPixelCount()).isEqualTo(76_800L);
        assertThat(pending.getPageCount()).isNull();
        assertThat(pending.getOcrProcessedAt()).isNull();
        verify(gcsService).uploadFile(eq(confirmedKey), any(), eq((long) png.length), eq("image/png"));
        verify(thumbnailService).generateThumbnailSync(png, confirmedKey, "image/png");
        verify(thumbnailService, never()).generateThumbnailSync(any(), any());
        verify(gcsService).deleteFile(stagingKey, 77L);

        Asset pdfAsset = Asset.builder()
                .userId(1L)
                .noteId(10L)
                .fileName("doc.pdf")
                .fileSize(1024L)
                .mimeType("application/pdf")
                .objectKey("users/1/notes/10/assets/uuid_doc.pdf")
                .source(Asset.AssetSource.upload)
                .status(AssetStatus.PENDING)
                .build();
        pdfAsset.confirmMetadata(expectedSha, 1024L, 3, null, null, null);
        assertThat(pdfAsset.getStatus()).isEqualTo(AssetStatus.ACTIVE);
        assertThat(pdfAsset.getOcrStatus()).isEqualTo(Asset.OcrStatus.processing);
        assertThat(pdfAsset.getOcrProcessedAt()).isNull();
    }

    private static void assertRejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private static byte[] encodeImage(String format, int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static byte[] headerOnlyWebpVp8x(int width, int height) {
        byte[] bytes = new byte[30];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        bytes[4] = 22;
        bytes[8] = 'W';
        bytes[9] = 'E';
        bytes[10] = 'B';
        bytes[11] = 'P';
        bytes[12] = 'V';
        bytes[13] = 'P';
        bytes[14] = '8';
        bytes[15] = 'X';
        bytes[16] = 10;
        int wMinus1 = width - 1;
        int hMinus1 = height - 1;
        bytes[24] = (byte) (wMinus1 & 0xFF);
        bytes[25] = (byte) ((wMinus1 >> 8) & 0xFF);
        bytes[26] = (byte) ((wMinus1 >> 16) & 0xFF);
        bytes[27] = (byte) (hMinus1 & 0xFF);
        bytes[28] = (byte) ((hMinus1 >> 8) & 0xFF);
        bytes[29] = (byte) ((hMinus1 >> 16) & 0xFF);
        return bytes;
    }

    private static byte[] validWebpVp8xWithVp8l(int canvasW, int canvasH, int frameW, int frameH) {
        byte[] bytes = new byte[44];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        bytes[4] = 36;
        bytes[8] = 'W';
        bytes[9] = 'E';
        bytes[10] = 'B';
        bytes[11] = 'P';
        bytes[12] = 'V';
        bytes[13] = 'P';
        bytes[14] = '8';
        bytes[15] = 'X';
        bytes[16] = 10;
        int cw = canvasW - 1;
        int ch = canvasH - 1;
        bytes[24] = (byte) (cw & 0xFF);
        bytes[25] = (byte) ((cw >> 8) & 0xFF);
        bytes[26] = (byte) ((cw >> 16) & 0xFF);
        bytes[27] = (byte) (ch & 0xFF);
        bytes[28] = (byte) ((ch >> 8) & 0xFF);
        bytes[29] = (byte) ((ch >> 16) & 0xFF);
        // VP8L chunk (5 bytes payload + 1 pad byte -> total 14 bytes)
        bytes[30] = 'V';
        bytes[31] = 'P';
        bytes[32] = '8';
        bytes[33] = 'L';
        bytes[34] = 5;
        bytes[38] = 0x2F;
        long bits = ((frameW - 1L) & 0x3FFFL) | (((frameH - 1L) & 0x3FFFL) << 14);
        bytes[39] = (byte) (bits & 0xFF);
        bytes[40] = (byte) ((bits >> 8) & 0xFF);
        bytes[41] = (byte) ((bits >> 16) & 0xFF);
        bytes[42] = (byte) ((bits >> 24) & 0xFF);
        return bytes;
    }

    private static byte[] createPdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] createPdfWithMediaBox(float widthPt, float heightPt) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(widthPt, heightPt)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] headerOnlyWebpVp8(int width, int height) {
        byte[] bytes = new byte[30];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        bytes[4] = 22;
        bytes[8] = 'W';
        bytes[9] = 'E';
        bytes[10] = 'B';
        bytes[11] = 'P';
        bytes[12] = 'V';
        bytes[13] = 'P';
        bytes[14] = '8';
        bytes[15] = ' ';
        bytes[16] = 10;
        bytes[20] = 0; // keyframe
        bytes[23] = (byte) 0x9D;
        bytes[24] = 0x01;
        bytes[25] = 0x2A;
        bytes[26] = (byte) (width & 0xFF);
        bytes[27] = (byte) ((width >> 8) & 0x3F);
        bytes[28] = (byte) (height & 0xFF);
        bytes[29] = (byte) ((height >> 8) & 0x3F);
        return bytes;
    }

    private static byte[] truncatedRealWebpVp8() {
        byte[] truncated = java.util.Arrays.copyOf(REAL_WEBP_VP8, 36);
        int riffSize = truncated.length - 8;
        int chunkSize = truncated.length - 20;
        truncated[4] = (byte) (riffSize & 0xFF);
        truncated[5] = (byte) ((riffSize >> 8) & 0xFF);
        truncated[6] = (byte) ((riffSize >> 16) & 0xFF);
        truncated[7] = (byte) ((riffSize >> 24) & 0xFF);
        truncated[16] = (byte) (chunkSize & 0xFF);
        truncated[17] = (byte) ((chunkSize >> 8) & 0xFF);
        truncated[18] = (byte) ((chunkSize >> 16) & 0xFF);
        truncated[19] = (byte) ((chunkSize >> 24) & 0xFF);
        return truncated;
    }

    private static byte[] animatedWebpVp8x() {
        byte[] base = REAL_WEBP_VP8X_VP8L.clone();
        base[20] = (byte) (base[20] | 0x02);
        return base;
    }

    private static byte[] createPdfWithEmbeddedImageXObject(int width, int height, boolean viaSmask) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDResources resources = new PDResources();
            page.setResources(resources);

            PDImageXObject primary = new PDImageXObject(doc);
            if (viaSmask) {
                primary.setWidth(16);
                primary.setHeight(16);
                PDImageXObject smask = new PDImageXObject(doc);
                smask.setWidth(width);
                smask.setHeight(height);
                primary.getCOSObject().setItem(COSName.SMASK, smask.getCOSObject());
            } else {
                primary.setWidth(width);
                primary.setHeight(height);
            }
            resources.put(COSName.getPDFName("Im0"), primary);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] createPdfWithOversizedInlineImage(int width, int height) throws Exception {
        byte[] content = ("q\nBI\n/W " + width + " /H " + height + " /CS /G /BPC 8\nID\n"
                + "\u0000\u0000\u0000\u0000\nEI\nQ\n").getBytes(StandardCharsets.ISO_8859_1);
        return createPdfWithRawContentStream(content);
    }

    private static byte[] createPdfWithFlateBombInlineImage(int uncompressedSize) throws Exception {
        byte[] rawZeros = new byte[uncompressedSize];
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (var dos = new java.util.zip.DeflaterOutputStream(deflated)) {
            dos.write(rawZeros);
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write("q\nBI\n/W 4 /H 4 /CS /G /BPC 8 /F /Fl\nID\n".getBytes(StandardCharsets.ISO_8859_1));
        content.write(deflated.toByteArray());
        content.write("\nEI\nQ\n".getBytes(StandardCharsets.ISO_8859_1));
        return createPdfWithRawContentStream(content.toByteArray());
    }

    private static byte[] createPdfWithRawContentStream(byte[] contentBytes) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            var stream = new org.apache.pdfbox.pdmodel.common.PDStream(doc, new java.io.ByteArrayInputStream(contentBytes));
            page.setContents(stream);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] createEncryptedPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
