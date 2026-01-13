package com.proovy.global.infra.s3;

import com.proovy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "S3 Health Check", description = "S3 연결 상태 확인 API (개발용)")
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class S3HealthController {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Operation(
            summary = "S3 연결 테스트",
            description = "AWS S3 버킷 연결 상태를 확인합니다. 개발 및 테스트 용도로 사용됩니다."
    )
    @GetMapping("/s3")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkS3Connection() {
        Map<String, Object> result = new HashMap<>();

        try {
            // S3 버킷 존재 여부 및 접근 권한 확인
            HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build();

            s3Client.headBucket(headBucketRequest);

            result.put("status", "OK");
            result.put("bucketName", bucketName);
            result.put("message", "S3 버킷에 정상적으로 연결되었습니다.");
            result.put("accessible", true);

            log.info("[S3 Health Check] 성공 - Bucket: {}", bucketName);

            return ResponseEntity.ok(
                    ApiResponse.success("S3 연결 테스트 성공", result)
            );

        } catch (S3Exception e) {
            result.put("status", "ERROR");
            result.put("bucketName", bucketName);
            result.put("accessible", false);

            if (e.awsErrorDetails() != null) {
                result.put("errorCode", e.awsErrorDetails().errorCode());
                result.put("errorMessage", e.awsErrorDetails().errorMessage());
            } else {
                result.put("errorMessage", e.getMessage());
            }

            log.error("[S3 Health Check] 실패 - Bucket: {}, Error: {}",
                    bucketName, e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage());

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    ApiResponse.success("S3 연결 테스트 실패", result)
            );

        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("bucketName", bucketName);
            result.put("accessible", false);
            result.put("errorMessage", e.getMessage());

            log.error("[S3 Health Check] 예외 발생 - Bucket: {}, Error: {}",
                    bucketName, e.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ApiResponse.success("S3 연결 테스트 중 오류 발생", result)
            );
        }
    }
}
