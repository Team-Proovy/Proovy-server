package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 특정 세션의 모든 메시지 조회 (시간순)
     */
    List<ChatMessage> findByChatSessionIdOrderByCreatedAtAsc(Long chatSessionId);

    /**
     * 특정 세션의 메시지 개수 조회
     */
    long countByChatSessionId(Long chatSessionId);

    // =============================================
    // Note 기반 조회 메서드
    // =============================================

    /**
     * 특정 노트의 모든 메시지 조회 (시간순)
     */
    List<ChatMessage> findByNoteIdOrderByCreatedAtAsc(Long noteId);

    /**
     * 특정 노트의 메시지 조회 (페이징, 최신순)
     */
    Page<ChatMessage> findByNoteIdOrderByCreatedAtDesc(Long noteId, Pageable pageable);

    /**
     * 특정 노트의 메시지 개수 조회
     */
    long countByNoteId(Long noteId);

    /**
     * 특정 노트의 메시지 ID 목록 조회
     */
    @Query("SELECT cm.id FROM ChatMessage cm WHERE cm.note.id = :noteId")
    List<Long> findIdsByNoteId(@Param("noteId") Long noteId);

    /**
     * 특정 노트의 모든 메시지 삭제 (벌크)
     */
    @Modifying
    @Query("DELETE FROM ChatMessage cm WHERE cm.note.id = :noteId")
    void deleteByNoteIdInBulk(@Param("noteId") Long noteId);

    /**
     * 여러 노트의 메시지 개수를 한 번에 조회 (배치)
     */
    @Query("SELECT cm.note.id AS noteId, COUNT(cm) AS count " +
           "FROM ChatMessage cm " +
           "WHERE cm.note.id IN :noteIds " +
           "GROUP BY cm.note.id")
    List<Object[]> countByNoteIdIn(@Param("noteIds") List<Long> noteIds);

    @Modifying
    @Query("DELETE FROM ChatMessage cm WHERE cm.chatSession.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
