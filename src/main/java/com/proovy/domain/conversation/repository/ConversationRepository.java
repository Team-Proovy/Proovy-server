package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 특정 노트의 대화 개수 조회
     */
    long countByNoteId(Long noteId);

    /**
     * 특정 노트의 모든 대화 조회
     */
    List<Conversation> findByNoteId(Long noteId);

    /**
     * 특정 노트의 대화 조회 (페이징)
     */
    Page<Conversation> findByNoteIdOrderByCreatedAtDesc(Long noteId, Pageable pageable);

    /**
     * 특정 노트의 모든 대화 ID 조회
     */
    @Query("SELECT c.id FROM Conversation c WHERE c.note.id = :noteId")
    List<Long> findIdsByNoteId(@Param("noteId") Long noteId);

    /**
     * 특정 노트의 모든 대화 삭제 (벌크 삭제)
     */
    @Modifying
    @Query("DELETE FROM Conversation c WHERE c.note.id = :noteId")
    void deleteByNoteIdInBulk(@Param("noteId") Long noteId);

    /**
     * 여러 노트의 대화 개수를 한 번에 조회 (배치 쿼리)
     * @param noteIds 노트 ID 목록
     * @return Map<노트ID, 대화개수>
     */
    @Query("SELECT c.note.id AS noteId, COUNT(c) AS count " +
           "FROM Conversation c " +
           "WHERE c.note.id IN :noteIds " +
           "GROUP BY c.note.id")
    List<Map<String, Object>> countByNoteIdIn(@Param("noteIds") List<Long> noteIds);
}

