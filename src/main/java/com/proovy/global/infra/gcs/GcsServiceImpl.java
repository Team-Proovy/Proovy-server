package com.proovy.global.infra.gcs;

import com.google.cloud.storage.*;
import com.proovy.global.config.GcsProperties;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GcsServiceImpl implements GcsService {

    private final Storage storage;
    private final String bucketName;
    private final String endpoint;
    private final GcsLocalSigning localSigning;

    @Autowired
    public GcsServiceImpl(Storage storage, GcsProperties gcsProperties, GcsLocalSigning localSigning) {
        this(storage, gcsProperties.bucket(), gcsProperties.endpoint(), localSigning);
    }

    public GcsServiceImpl(Storage storage, String bucketName, String endpoint, GcsLocalSigning localSigning) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.endpoint = endpoint;
        this.localSigning = localSigning != null ? localSigning : GcsLocalSigning.disabled();
    }

    @Override
    public void deleteFile(String gcsKey) {
        if (gcsKey == null || gcsKey.isBlank()) return;

        try {
            storage.delete(BlobId.of(bucketName, gcsKey));
            log.info("[GCS] 파일 삭제 성공: {}", gcsKey);
        } catch (StorageException e) {
            log.error("[GCS] 파일 삭제 실패: {}, code={}, message={}", gcsKey, e.getCode(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    @Override
    public void deleteFiles(List<String> gcsKeys) {
        if (gcsKeys == null || gcsKeys.isEmpty()) {
            log.warn("[GCS] 삭제할 파일 목록이 비어있습니다.");
            return;
        }

        List<BlobId> blobIds = gcsKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .map(key -> BlobId.of(bucketName, key))
                .collect(Collectors.toList());

        if (blobIds.isEmpty()) {
            log.warn("[GCS] 유효한 삭제 대상 key가 없습니다.");
            return;
        }

        try {
            List<Boolean> results = storage.delete(blobIds);
            long missingCount = results.stream().filter(Boolean.FALSE::equals).count();

            if (missingCount > 0) {
                log.warn("[GCS] {} 개 파일은 이미 존재하지 않아 삭제를 건너뛰었습니다.", missingCount);
            }

            log.info("[GCS] 파일 일괄 삭제 성공: {} 개", results.size());
        } catch (StorageException e) {
            log.error("[GCS] 파일 일괄 삭제 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    @Override
    public String uploadFile(String gcsKey, InputStream inputStream, long contentLength, String contentType) {
        if (gcsKey == null || gcsKey.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON400);
        }

        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, gcsKey)
                    .setContentType(contentType)
                    .build();

            storage.createFrom(blobInfo, inputStream);
            log.info("[GCS] 파일 업로드 성공: {}", gcsKey);

            return getFileUrl(gcsKey);
        } catch (IOException | StorageException e) {
            log.error("[GCS] 파일 업로드 실패: {}, message={}", gcsKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    @Override
    public String getFileUrl(String gcsKey) {
        if (localSigning.isEnabled()) {
            return localSigning.publicFileUrl(bucketName, gcsKey);
        }
        String encodedKey = URLEncoder.encode(gcsKey, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String baseUrl = (endpoint != null && !endpoint.isBlank())
                ? endpoint
                : "https://storage.googleapis.com";
        return String.format("%s/%s/%s", baseUrl, bucketName, encodedKey);
    }

    @Override
    public String getThumbnailUrl(String thumbnailGcsKey) {
        if (thumbnailGcsKey == null || thumbnailGcsKey.isBlank()) return null;
        return getFileUrl(thumbnailGcsKey);
    }

    @Override
    public boolean doesFileExist(String gcsKey) {
        if (gcsKey == null || gcsKey.isBlank()) return false;

        try {
            Blob blob = storage.get(BlobId.of(bucketName, gcsKey));
            return blob != null && blob.exists();
        } catch (StorageException e) {
            log.error("[GCS] 파일 존재 확인 실패: {}, message={}", gcsKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    @Override
    public String generatePresignedUploadUrl(String gcsKey, String contentType, int durationMinutes) {
        if (gcsKey == null || gcsKey.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON400);
        }

        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, gcsKey)
                    .setContentType(contentType)
                    .build();

            URL signedUrl = signUrl(
                    blobInfo,
                    durationMinutes,
                    List.of(
                            Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                            Storage.SignUrlOption.withContentType()
                    )
            );

            log.info("[GCS] Upload Signed URL 생성 성공: {}", gcsKey);
            return signedUrl.toString();
        } catch (IllegalStateException | StorageException e) {
            log.error("[GCS] Upload Signed URL 생성 실패: {}, message={}", gcsKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    @Override
    public byte[] readFileBytes(String gcsKey) {
        if (gcsKey == null || gcsKey.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON400);
        }

        try {
            byte[] bytes = storage.readAllBytes(bucketName, gcsKey);
            if (bytes == null) {
                throw new BusinessException(ErrorCode.COMMON500);
            }
            return bytes;
        } catch (StorageException e) {
            log.error("[GCS] 파일 읽기 실패: {}, message={}", gcsKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    @Override
    public String generatePresignedDownloadUrl(String gcsKey, String fileName, int durationMinutes) {
        if (gcsKey == null || gcsKey.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON400);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON400);
        }

        try {
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            String contentDisposition = "attachment; filename=\"" + encodedFileName + "\"";

            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, gcsKey)
                    .setContentDisposition(contentDisposition)
                    .build();

            URL signedUrl = signUrl(blobInfo, durationMinutes, List.of());

            log.debug("[GCS] Download Signed URL 생성 성공: {}", gcsKey);
            return signedUrl.toString();
        } catch (IllegalStateException | StorageException e) {
            log.error("[GCS] Download Signed URL 생성 실패: {}, message={}", gcsKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    @Override
    public String generatePresignedReadUrl(String gcsKey, int durationMinutes) {
        if (gcsKey == null || gcsKey.isBlank() || durationMinutes <= 0) {
            throw new BusinessException(ErrorCode.COMMON400);
        }

        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, gcsKey).build();
            URL signedUrl = signUrl(
                    blobInfo,
                    durationMinutes,
                    List.of(Storage.SignUrlOption.httpMethod(HttpMethod.GET))
            );
            log.debug("[GCS] Read Signed URL 생성 성공: {}", gcsKey);
            return signedUrl.toString();
        } catch (IllegalStateException | StorageException e) {
            log.error("[GCS] Read Signed URL 생성 실패: {}, message={}", gcsKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    private URL signUrl(BlobInfo blobInfo, int durationMinutes, List<Storage.SignUrlOption> extraOptions) {
        List<Storage.SignUrlOption> options = new ArrayList<>(extraOptions);
        options.add(Storage.SignUrlOption.withV4Signature());
        if (localSigning.isEnabled()) {
            options.add(Storage.SignUrlOption.signWith(localSigning.signer()));
            options.add(Storage.SignUrlOption.withHostName(localSigning.publicBaseUrl()));
            options.add(Storage.SignUrlOption.withPathStyle());
        }
        return storage.signUrl(
                blobInfo,
                durationMinutes,
                TimeUnit.MINUTES,
                options.toArray(Storage.SignUrlOption[]::new)
        );
    }
}
