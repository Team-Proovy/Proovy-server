package com.proovy.global.infra.s3;

import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3ServiceImpl implements S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.region}")
    private String region;

    @Override
    public void deleteFile(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) return;

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            s3Client.deleteObject(request);
            log.info("[S3] 파일 삭제 성공: {}", s3Key);

        } catch (S3Exception e) {
            log.error("[S3] 파일 삭제 실패: {}, code={}, message={}",
                    s3Key,
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "unknown",
                    e.getMessage(),
                    e
            );
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    @Override
    public void deleteFiles(List<String> s3Keys) {
        if (s3Keys == null || s3Keys.isEmpty()) {
            log.warn("[S3] 삭제할 파일 목록이 비어있습니다.");
            return;
        }

        // S3 DeleteObjects는 최대 1000개까지 지원
        List<ObjectIdentifier> objectIdentifiers = s3Keys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .collect(Collectors.toList());

        if (objectIdentifiers.isEmpty()) {
            log.warn("[S3] 유효한 삭제 대상 key가 없습니다.");
            return;
        }

        try {
            Delete delete = Delete.builder()
                    .objects(objectIdentifiers)
                    .build();

            DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(delete)
                    .build();

            DeleteObjectsResponse response = s3Client.deleteObjects(request);

            if (response.hasDeleted()) {
                log.info("[S3] 파일 일괄 삭제 성공: {} 개", response.deleted().size());
            }

            // 부분 실패 가능 -> errors 있으면 에러 처리
            if (response.hasErrors() && !response.errors().isEmpty()) {
                response.errors().forEach(error ->
                        log.error("[S3] 파일 삭제 실패 - Key: {}, Code: {}, Message: {}",
                                error.key(), error.code(), error.message())
                );
                throw new BusinessException(ErrorCode.COMMON500);
            }

        } catch (S3Exception e) {
            log.error("[S3] 파일 일괄 삭제 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    /**
     * 파일 업로드
     */
    @Override
    public String uploadFile(String s3Key, InputStream inputStream, long contentLength, String contentType) {
        if (s3Key == null || s3Key.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON400);
        }

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(contentType)
                    .contentLength(contentLength)
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, contentLength));
            log.info("[S3] 파일 업로드 성공: {}", s3Key);

            return getFileUrl(s3Key);

        } catch (S3Exception e) {
            log.error("[S3] 파일 업로드 실패: {}, code={}, message={}",
                    s3Key,
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "unknown",
                    e.getMessage(),
                    e
            );
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    /**
     * 파일 URL 생성
     */
    @Override
    public String getFileUrl(String s3Key) {
        String encodedKey = URLEncoder.encode(s3Key, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return String.format("https://%s.s3.%s.amazonaws.com/%s",
                bucketName,
                region,
                encodedKey);
    }

    /**
     * 썸네일 URL 생성
     */
    @Override
    public String getThumbnailUrl(String thumbnailS3Key) {
        if (thumbnailS3Key == null || thumbnailS3Key.isBlank()) {
            return null;
        }
        return getFileUrl(thumbnailS3Key);
    }

    /**
     * 파일 존재 여부 확인
     */
    @Override
    public boolean doesFileExist(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) return false;

        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            s3Client.headObject(request);
            return true;

        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;

            log.error("[S3] 파일 존재 확인 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }

    /**
     * 파일 업로드용 Presigned URL 생성
     */
    @Override
    public String generatePresignedUploadUrl(String s3Key, String contentType, int durationMinutes) {
        if (s3Key == null || s3Key.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON400);
        }

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(contentType)
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(durationMinutes))
                    .putObjectRequest(putObjectRequest)
                    .build();

            PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
            String presignedUrl = presignedRequest.url().toString();

            log.info("[S3] Presigned URL 생성 성공: {}", s3Key);
            return presignedUrl;

        } catch (S3Exception e) {
            log.error("[S3] Presigned URL 생성 실패: {}, code={}, message={}",
                    s3Key,
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "unknown",
                    e.getMessage(),
                    e
            );
            throw new BusinessException(ErrorCode.COMMON500);
        }
    }
}
