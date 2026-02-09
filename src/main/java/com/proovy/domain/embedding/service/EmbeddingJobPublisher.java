package com.proovy.domain.embedding.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
        try {
            String lockKey = String.format("%s:%d:%s", lockPrefix, noteId, contentHash);
            Boolean lockAcquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", Duration.ofSeconds(lockTtl));

            if (Boolean.FALSE.equals(lockAcquired)) {
                log.info("임베딩 작업 중복 감지. 발행 생략: noteId={}, hash={}", noteId, contentHash);
                return;
            }

            Map<String, String> message = Map.of(
                    "noteId", String.valueOf(noteId),
                    "contentHash", contentHash,
                    "model", (model == null || model.isBlank()) ? DEFAULT_MODEL : model,
                    "timestamp", String.valueOf(System.currentTimeMillis())
            );

            RecordId recordId = redisTemplate.opsForStream()
                    .add(StreamRecords.newRecord()
                            .ofStrings(message)
                            .withStreamKey(streamKey));

            log.info("임베딩 작업 발행 완료: noteId={}, recordId={}", noteId, recordId);
        } catch (Exception e) {
            log.error("임베딩 작업 발행 실패: noteId={}", noteId, e);
        }
    }
}
