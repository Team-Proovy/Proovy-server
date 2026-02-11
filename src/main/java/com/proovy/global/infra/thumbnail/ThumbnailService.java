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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {

    private final S3Service s3Service;
    private final AssetRepository assetRepository;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    private static final int THUMBNAIL_WIDTH = 400;
    private static final int THUMBNAIL_HEIGHT = 400;
    private static final float THUMBNAIL_QUALITY = 0.85f;
    private static final int PDF_DPI = 150;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 썸네일 생성 (비동기)
     * - 이미지: 리사이즈
     * - PDF: 첫 페이지를 이미지로 변환 후 리사이즈
     */
    @Async
    public void generateThumbnailAsync(Long assetId, String s3Key, String mimeType) {
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
            String thumbnailS3Key = generateThumbnailS3Key(s3Key);

            // 5. 썸네일 S3 업로드
            try (InputStream thumbnailStream = new ByteArrayInputStream(thumbnailBytes)) {
                s3Service.uploadFile(thumbnailS3Key, thumbnailStream,
                        thumbnailBytes.length, "image/jpeg");
            }

            // 6. Asset 엔티티 업데이트 (별도 트랜잭션)
            updateAssetThumbnail(assetId, thumbnailS3Key);

            log.info("[Thumbnail] 썸네일 생성 완료 - assetId: {}, thumbnailS3Key: {}",
                    assetId, thumbnailS3Key);

        } catch (Exception e) {
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
     * 이미지 리사이즈 및 JPEG 변환
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

        // 고품질 리사이즈
        BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resizedImage.createGraphics();

        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        graphics.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
        graphics.dispose();

        // JPEG로 변환
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(resizedImage, "jpeg", baos);
        return baos.toByteArray();
    }

    /**
     * 파일 다운로드 (HTTP)
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

        return response.body();
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
        assetRepository.findById(assetId).ifPresent(asset -> {
            asset.updateThumbnail(thumbnailS3Key);
            assetRepository.save(asset);
            log.info("[Thumbnail] Asset 업데이트 완료 - assetId: {}, thumbnailS3Key: {}",
                    assetId, thumbnailS3Key);
        });
    }
}
