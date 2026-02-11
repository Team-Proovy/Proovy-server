package com.proovy.global.infra.thumbnail;

import com.proovy.domain.asset.entity.Asset;
import com.proovy.domain.asset.repository.AssetRepository;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.infra.s3.S3Service;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {

    private final S3Service s3Service;
    private final AssetRepository assetRepository;
    private final ApplicationContext applicationContext;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private static final int THUMBNAIL_WIDTH = 400;
    private static final int THUMBNAIL_HEIGHT = 400;
    private static final float THUMBNAIL_QUALITY = 0.85f;
    private static final int PDF_DPI = 150;
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB 제한

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 셀프 프록시 주입 (트랜잭션 AOP 적용용)
     */
    private ThumbnailService getSelf() {
        return applicationContext.getBean(ThumbnailService.class);
    }

    /**
     * 썸네일 생성 (비동기)
     * - 이미지: 리사이즈
     * - PDF: 첫 페이지를 이미지로 변환 후 리사이즈
     */
    @Async
    public void generateThumbnailAsync(Long assetId, String s3Key, String mimeType) {
        String thumbnailS3Key = null;
        boolean thumbnailUploaded = false;
        try {
            log.info("[Thumbnail] 썸네일 생성 시작 - assetId: {}, s3Key: {}, mimeType: {}",
                    assetId, s3Key, mimeType);

            // 1. 원본 파일 다운로드 URL 생성
            String fileUrl = s3Service.getFileUrl(s3Key);

            // 2. 파일 다운로드
            byte[] fileBytes = downloadFile(fileUrl);

            // 3. 썸네일 생성
            byte[] thumbnailBytes;
            if (mimeType.equals("application/pdf")) {
                thumbnailBytes = createPdfThumbnail(fileBytes);
            } else if (mimeType.startsWith("image/")) {
                thumbnailBytes = createImageThumbnail(fileBytes);
            } else {
                log.warn("[Thumbnail] 지원하지 않는 파일 형식 - assetId: {}, mimeType: {}",
                        assetId, mimeType);
                return;
            }

            // 4. 썸네일 S3 키 생성
            // 원본: users/{userId}/notes/{noteId}/assets/{uuid}_{filename}
            // 썸네일: users/{userId}/notes/{noteId}/thumbnails/{uuid}_thumb.jpg
            thumbnailS3Key = generateThumbnailS3Key(s3Key);

            // 5. 썸네일 S3 업로드
            try (InputStream thumbnailStream = new ByteArrayInputStream(thumbnailBytes)) {
                s3Service.uploadFile(thumbnailS3Key, thumbnailStream,
                        thumbnailBytes.length, "image/jpeg");
                thumbnailUploaded = true;
            }

            // 6. Asset 엔티티 업데이트 (별도 트랜잭션, 프록시를 통해 호출)
            getSelf().updateAssetThumbnail(assetId, thumbnailS3Key);

            log.info("[Thumbnail] 썸네일 생성 완료 - assetId: {}, thumbnailS3Key: {}",
                    assetId, thumbnailS3Key);

        } catch (Exception e) {
            if (thumbnailUploaded && thumbnailS3Key != null) {
                try {
                    s3Service.deleteFile(thumbnailS3Key);
                    log.warn("[Thumbnail] Asset 업데이트 실패로 고아 썸네일 삭제 - assetId: {}, thumbnailS3Key: {}",
                            assetId, thumbnailS3Key);
                } catch (Exception deleteException) {
                    log.error("[Thumbnail] 고아 썸네일 삭제 실패 - assetId: {}, thumbnailS3Key: {}, error: {}",
                            assetId, thumbnailS3Key, deleteException.getMessage(), deleteException);
                }
            }
            log.error("[Thumbnail] 썸네일 생성 실패 - assetId: {}, error: {}",
                    assetId, e.getMessage(), e);
            // 썸네일 생성 실패해도 에러를 throw하지 않음 (비동기이므로)
        }
    }

    /**
     * PDF 첫 페이지를 썸네일로 변환
     */
    private byte[] createPdfThumbnail(byte[] pdfBytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.getNumberOfPages() == 0) {
                throw new IllegalArgumentException("PDF에 페이지가 없습니다.");
            }

            // 첫 페이지를 이미지로 렌더링
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            BufferedImage image = pdfRenderer.renderImageWithDPI(0, PDF_DPI);

            // 리사이즈 및 JPEG 변환
            return resizeAndConvertToJpeg(image);
        }
    }

    /**
     * 이미지를 썸네일로 리사이즈
     */
    private byte[] createImageThumbnail(byte[] imageBytes) throws Exception {
        try (InputStream is = new ByteArrayInputStream(imageBytes)) {
            BufferedImage image = ImageIO.read(is);
            if (image == null) {
                throw new IllegalArgumentException("이미지를 읽을 수 없습니다.");
            }

            return resizeAndConvertToJpeg(image);
        }
    }

    /**
     * 이미지 리사이즈 및 JPEG 변환 (투명 배경 처리 포함)
     */
    private byte[] resizeAndConvertToJpeg(BufferedImage originalImage) throws Exception {
        // 원본 비율 유지하면서 리사이즈
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        double widthRatio = (double) THUMBNAIL_WIDTH / originalWidth;
        double heightRatio = (double) THUMBNAIL_HEIGHT / originalHeight;
        double ratio = Math.min(widthRatio, heightRatio);

        int newWidth = (int) (originalWidth * ratio);
        int newHeight = (int) (originalHeight * ratio);

        // 고품질 리사이즈 (TYPE_INT_RGB로 생성)
        BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resizedImage.createGraphics();

        // 투명 PNG 처리: 흰색 배경으로 채우기
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, newWidth, newHeight);

        // 렌더링 힌트 설정
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 원본 이미지 그리기
        graphics.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
        graphics.dispose();

        // JPEG 품질을 명시적으로 제어해서 썸네일 용량/품질 균형을 맞춘다.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("JPEG writer를 찾을 수 없습니다.");
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(THUMBNAIL_QUALITY);
            }
            writer.write(null, new IIOImage(resizedImage, null, null), writeParam);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    /**
     * 파일 다운로드 (HTTP) - 크기 체크 포함
     */
    private byte[] downloadFile(String fileUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("파일 다운로드 실패: HTTP " + response.statusCode());
        }

        byte[] body = response.body();

        // 파일 크기 체크 (OOM 방지)
        if (body.length > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    String.format("파일 크기가 너무 큽니다: %d bytes (최대 %d bytes)",
                            body.length, MAX_FILE_SIZE_BYTES));
        }

        return body;
    }

    /**
     * 썸네일 S3 키 생성
     * users/{userId}/notes/{noteId}/assets/{uuid}_{filename}
     * → users/{userId}/notes/{noteId}/thumbnails/{uuid}_thumb.jpg
     */
    private String generateThumbnailS3Key(String originalS3Key) {
        String[] parts = originalS3Key.split("/");
        if (parts.length < 5) {
            throw new IllegalArgumentException("잘못된 S3 키 형식: " + originalS3Key);
        }

        // UUID 추출 (파일명에서 첫 번째 '_' 앞부분)
        String fileName = parts[parts.length - 1];
        String uuid = fileName.split("_")[0];

        // users/{userId}/notes/{noteId}/thumbnails/{uuid}_thumb.jpg
        return String.format("%s/%s/%s/%s/thumbnails/%s_thumb.jpg",
                parts[0], parts[1], parts[2], parts[3], uuid);
    }

    /**
     * Asset 엔티티의 thumbnailS3Key 업데이트 (별도 트랜잭션)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAssetThumbnail(Long assetId, String thumbnailS3Key) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> {
                    log.warn("[Thumbnail] Asset 미존재로 썸네일 연결 실패 - assetId: {}, thumbnailS3Key: {}",
                            assetId, thumbnailS3Key);
                    return new BusinessException(ErrorCode.ASSET4041);
                });

        asset.updateThumbnail(thumbnailS3Key);
        assetRepository.save(asset);
        log.info("[Thumbnail] Asset 업데이트 완료 - assetId: {}, thumbnailS3Key: {}",
                assetId, thumbnailS3Key);
    }
}
