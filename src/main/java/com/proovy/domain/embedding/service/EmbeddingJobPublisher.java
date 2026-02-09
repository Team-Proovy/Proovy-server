package com.proovy.domain.embedding.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingJobPublisher {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";

    private final StringRedisTemplate redisTemplate;

    @Value("${embedding.redis.stream.key}")
    private String streamKey;

    @Value("${embedding.redis.lock.prefix}")
    private String lockPrefix;

    @Value("${embedding.redis.lock.ttl}")
    private long lockTtl;

    public void publishEmbeddingJob(Long noteId, String contentHash, String model) {
        if (noteId == null) {
            log.warn("임베딩 작업 발행 생략: noteId가 null입니다.");
            return;
        }
        if (contentHash == null || contentHash.isBlank()) {
            log.warn("임베딩 작업 발행 생략: contentHash가 비어 있습니다. noteId={}", noteId);
            return;
        }

        String resolvedModel = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishNow(noteId, contentHash, resolvedModel);
                }
            });
            return;
        }

        publishNow(noteId, contentHash, resolvedModel);
    }

    private void publishNow(Long noteId, String contentHash, String model) {
        String lockKey = String.format("%s:%d:%s", lockPrefix, noteId, contentHash);
        boolean lockAcquired = false;
        try {
            lockAcquired = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(lockTtl))
            );
            if (!lockAcquired) {
                log.info("임베딩 작업 중복 감지. 발행 생략: noteId={}, hash={}", noteId, contentHash);
                return;
            }

            Map<String, String> message = Map.of(
                    "noteId", String.valueOf(noteId),
                    "contentHash", contentHash,
                    "model", model,
                    "timestamp", String.valueOf(System.currentTimeMillis())
            );

            RecordId recordId = redisTemplate.opsForStream()
                    .add(StreamRecords.newRecord()
                            .ofStrings(message)
                            .withStreamKey(streamKey));
            if (recordId == null) {
                throw new IllegalStateException("Redis stream recordId is null");
            }

            log.info("임베딩 작업 발행 완료: noteId={}, recordId={}", noteId, recordId);
        } catch (Exception e) {
            if (lockAcquired) {
                redisTemplate.delete(lockKey);
            }
            log.error("임베딩 작업 발행 실패: noteId={}", noteId, e);
        }
    }
}
