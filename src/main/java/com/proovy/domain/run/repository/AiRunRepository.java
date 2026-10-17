package com.proovy.domain.run.repository;

import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.entity.AiRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AiRunRepository extends JpaRepository<AiRun, UUID> {

    /**
     * request_id는 전역 unique다. 멱등 재생 시 호출자(1.77)는 반드시 run의 user_id를 요청 사용자와 비교하고,
     * 다르면 상세를 노출하지 않고 거부한다(1.75 계약 D7: 409 RUN4092).
     */
    Optional<AiRun> findByRequestId(String requestId);

    /**
     * Note의 Active Run (409 activeRunId 응답용). partial unique index로 최대 하나다.
     */
    @Query("""
           SELECT r FROM AiRun r
           WHERE r.noteId = :noteId
             AND r.status IN (com.proovy.domain.run.entity.AiRunStatus.PENDING,
                              com.proovy.domain.run.entity.AiRunStatus.RUNNING,
                              com.proovy.domain.run.entity.AiRunStatus.RETRYING,
                              com.proovy.domain.run.entity.AiRunStatus.CANCELING)
           """)
    Optional<AiRun> findActiveByNoteId(@Param("noteId") Long noteId);

    long countByNoteIdAndStatus(Long noteId, AiRunStatus status);
}
