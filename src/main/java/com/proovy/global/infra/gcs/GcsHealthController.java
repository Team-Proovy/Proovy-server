package com.proovy.global.infra.gcs;

import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
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

import java.util.HashMap;
import java.util.Map;

@Tag(name = "GCS Health Check", description = "GCS 연결 상태 확인 API (개발용)")
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class GcsHealthController {

    private final Storage storage;

    @Value("${gcs.bucket}")
    private String bucketName;

    @Operation(
            summary = "GCS 연결 테스트",
            description = "Google Cloud Storage 버킷 연결 상태를 확인합니다. 개발 및 테스트 용도로 사용됩니다."
    )
    @GetMapping("/gcs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkGcsConnection() {
        Map<String, Object> result = new HashMap<>();

        try {
            Bucket bucket = storage.get(bucketName);

            if (bucket == null) {
                result.put("status", "ERROR");
                result.put("bucketName", bucketName);
                result.put("accessible", false);
                result.put("errorMessage", "버킷을 찾을 수 없습니다.");

                log.error("[GCS Health Check] 버킷 없음 - Bucket: {}", bucketName);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                        ApiResponse.failure("GCS503", "GCS 연결 테스트 실패: 버킷을 찾을 수 없습니다.")
                );
            }

            result.put("status", "OK");
            result.put("bucketName", bucketName);
            result.put("accessible", true);
            result.put("message", "GCS 버킷에 정상적으로 연결되었습니다.");

            log.info("[GCS Health Check] 성공 - Bucket: {}", bucketName);
            return ResponseEntity.ok(ApiResponse.success("GCS 연결 테스트 성공", result));

        } catch (StorageException e) {
            log.error("[GCS Health Check] 실패 - Bucket: {}, Code: {}, Error: {}",
                    bucketName, e.getCode(), e.getMessage());

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    ApiResponse.failure("GCS503", "GCS 연결 테스트 실패: " + e.getMessage())
            );
        } catch (Exception e) {
            log.error("[GCS Health Check] 예외 발생 - Bucket: {}, Error: {}", bucketName, e.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ApiResponse.failure("GCS500", "GCS 연결 테스트 중 오류 발생: " + e.getMessage())
            );
        }
    }
}
