package com.proovy.domain.conversation.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * chat_messages.content_tsv 컬럼 백필을 위한 ApplicationRunner
 *
 * Flyway 트랜잭션 내에서 배치 처리 시 SKIP LOCKED가 무의미하므로
 * 서버 시작 시 autocommit 모드로 별도 실행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageTsvBackfillRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    private static final int BATCH_SIZE = 500;
    private static final int SLEEP_MS = 50;

    @Override
    public void run(ApplicationArguments args) {
        backfillContentTsv();
    }

    private void backfillContentTsv() {
        // 백필이 필요한 행이 있는지 먼저 확인
        Integer nullCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM chat_messages WHERE content_tsv IS NULL",
                Integer.class
        );

        if (nullCount == null || nullCount == 0) {
            log.info("[ChatMessageTsvBackfill] No rows need backfill, skipping");
            return;
        }

        log.info("[ChatMessageTsvBackfill] Starting backfill for {} rows", nullCount);

        int totalUpdated = 0;

        while (true) {
            // 각 배치를 별도 트랜잭션으로 실행 (autocommit 효과)
            Integer updated = transactionTemplate.execute(status -> {
                return jdbcTemplate.update("""
                    UPDATE chat_messages
                    SET content_tsv = to_tsvector('simple', COALESCE(content->>'text', ''))
                    WHERE chat_message_id IN (
                        SELECT chat_message_id FROM chat_messages
                        WHERE content_tsv IS NULL
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    """, BATCH_SIZE);
            });

            if (updated == null || updated == 0) {
                break;
            }

            totalUpdated += updated;

            if (totalUpdated % 5000 == 0) {
                log.info("[ChatMessageTsvBackfill] Progress: {} rows updated", totalUpdated);
            }

            // 다른 트랜잭션에 기회 제공
            try {
                Thread.sleep(SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[ChatMessageTsvBackfill] Interrupted, stopping backfill");
                break;
            }
        }

        log.info("[ChatMessageTsvBackfill] Completed. Total updated: {} rows", totalUpdated);
    }
}
