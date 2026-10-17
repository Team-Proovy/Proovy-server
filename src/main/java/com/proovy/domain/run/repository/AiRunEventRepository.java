package com.proovy.domain.run.repository;

import com.proovy.domain.run.entity.AiRunEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiRunEventRepository extends JpaRepository<AiRunEvent, Long> {

    /**
     * Last-Event-ID cursor 이후 durable event replay
     */
    List<AiRunEvent> findByRunIdAndSeqGreaterThanOrderBySeqAsc(UUID runId, long afterSeq);
}
