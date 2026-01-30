package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Note 도메인 전용 ConversationRepository
 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 특정 노트의 대화 조회
     */
    Optional<Conversation> findByNoteId(Long noteId);

    /**
     * 특정 노트의 대화 개수 조회
     */
    long countByNoteId(Long noteId);

    /**
     * 여러 노트의 대화 개수를 한 번에 조회 (배치 쿼리)
     */
    @Query("SELECT c.note.id AS noteId, COUNT(c) AS count " +
           "FROM Conversation c " +
           "WHERE c.note.id IN :noteIds " +
           "GROUP BY c.note.id")
    List<Map<String, Object>> countByNoteIdIn(@Param("noteIds") List<Long> noteIds);
}

