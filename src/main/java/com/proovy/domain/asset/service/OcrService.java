package com.proovy.domain.asset.service;

import com.proovy.domain.asset.entity.Asset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrService {

    private final WebClient webClient;

    @Value("${ai.server.url:http://localhost:8000}")
    private String aiServerUrl;

    @Async("ocrTaskExecutor")
    public void requestOcrProcessing(Asset asset) {
        log.info("[OCR] OCR 처리 요청 시작 - assetId: {}, s3Key: {}", asset.getId(), asset.getS3Key());

        try {
            webClient.post()
                    .uri(aiServerUrl + "/api/ocr/process")
                    .bodyValue(new OcrRequest(
                            asset.getId(),
                            asset.getS3Key(),
                            asset.getMimeType()
                    ))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .doOnSuccess(v -> log.info("[OCR] OCR 처리 요청 완료 - assetId: {}", asset.getId()))
                    .doOnError(e -> log.error("[OCR] OCR 처리 요청 실패 - assetId: {}, error: {}",
                            asset.getId(), e.getMessage()))
                    .subscribe();

        } catch (Exception e) {
            log.error("[OCR] OCR 처리 요청 중 예외 발생 - assetId: {}, error: {}",
                    asset.getId(), e.getMessage(), e);
        }
    }

    private record OcrRequest(Long assetId, String s3Key, String mimeType) {}
}
