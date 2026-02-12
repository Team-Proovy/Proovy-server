package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Note 도메인 전용 MessageRepository
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    // =============================================
    // Full-Text Search Native Queries
    // =============================================

    /**
     * Full-Text Search: tsvector + ts_rank 기반 검색
     * 한글 검색은 pg_trgm LIKE 폴백 사용
     */
    @Query(value = """
            SELECT m.* FROM messages m
            JOIN conversations c ON m.conversation_id = c.conversation_id
            JOIN notes n ON c.note_id = n.note_id
            WHERE n.user_id = :userId
              AND m.search_vector @@ plainto_tsquery('simple', :query)
            ORDER BY ts_rank(m.search_vector, plainto_tsquery('simple', :query)) DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM messages m
            JOIN conversations c ON m.conversation_id = c.conversation_id
            JOIN notes n ON c.note_id = n.note_id
            WHERE n.user_id = :userId
              AND m.search_vector @@ plainto_tsquery('simple', :query)
            """,
            nativeQuery = true)
    Page<Message> searchByFullText(
            @Param("userId") Long userId,
            @Param("query") String query,
            Pageable pageable);

    /**
     * Full-Text Search + noteId 필터
     */
    @Query(value = """
            SELECT m.* FROM messages m
            JOIN conversations c ON m.conversation_id = c.conversation_id
            JOIN notes n ON c.note_id = n.note_id
            WHERE n.user_id = :userId
              AND n.note_id = :noteId
              AND m.search_vector @@ plainto_tsquery('simple', :query)
            ORDER BY ts_rank(m.search_vector, plainto_tsquery('simple', :query)) DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM messages m
            JOIN conversations c ON m.conversation_id = c.conversation_id
            JOIN notes n ON c.note_id = n.note_id
            WHERE n.user_id = :userId
              AND n.note_id = :noteId
              AND m.search_vector @@ plainto_tsquery('simple', :query)
            """,
            nativeQuery = true)
    Page<Message> searchByFullTextAndNoteId(
            @Param("userId") Long userId,
            @Param("noteId") Long noteId,
            @Param("query") String query,
            Pageable pageable);

    /**
     * pg_trgm LIKE 검색 (한글 폴백)
     */
    @Query(value = """
            SELECT m.* FROM messages m
            JOIN conversations c ON m.conversation_id = c.conversation_id
            JOIN notes n ON c.note_id = n.note_id
            WHERE n.user_id = :userId
              AND m.content ILIKE '%' || :query || '%'
            ORDER BY similarity(m.content, :query) DESC, m.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM messages m
            JOIN conversations c ON m.conversation_id = c.conversation_id
            JOIN notes n ON c.note_id = n.note_id
            WHERE n.user_id = :userId
              AND m.content ILIKE '%' || :query || '%'
            """,
            nativeQuery = true)
    Page<Message> searchByTrigram(
            @Param("userId") Long userId,
            @Param("query") String query,
            Pageable pageable);

    /**
     * pg_trgm LIKE 검색 + noteId 필터 (한글 폴백)
     */
    @Query(value = """
            SELECT m.* FROM messages m
            JOIN conversations c ON m.conversation_id = c.conversation_id
            JOIN notes n ON c.note_id = n.note_id
            WHERE n.user_id = :userId
              AND n.note_id = :noteId
              AND m.content ILIKE '%' || :query || '%'
            ORDER BY similarity(m.content, :query) DESC, m.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM messages m
            JOIN conversations c ON m.conversation_id = c.conversation_id
            JOIN notes n ON c.note_id = n.note_id
            WHERE n.user_id = :userId
              AND n.note_id = :noteId
              AND m.content ILIKE '%' || :query || '%'
            """,
            nativeQuery = true)
    Page<Message> searchByTrigramAndNoteId(
            @Param("userId") Long userId,
            @Param("noteId") Long noteId,
            @Param("query") String query,
            Pageable pageable);

    /**
     * 검색어 하이라이트 추출 (ts_headline)
     */
    @Query(value = """
            SELECT ts_headline('simple', m.content, plainto_tsquery('simple', :query),
                   'StartSel=<mark>, StopSel=</mark>, MaxWords=50, MinWords=20')
            FROM messages m
            WHERE m.id = :messageId
            """, nativeQuery = true)
    String getSearchHighlight(@Param("messageId") Long messageId, @Param("query") String query);

    /**
     * 특정 대화의 모든 메시지 조회
     */
    List<Message> findByConversationId(Long conversationId);

    /**
     * 특정 대화의 모든 메시지 ID 조회
     */
    @Query("SELECT m.id FROM Message m WHERE m.conversation.id = :conversationId")
    List<Long> findIdsByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 여러 대화의 모든 메시지 조회 (생성 시각 순)
     */
    List<Message> findByConversationIdInOrderByCreatedAtAsc(List<Long> conversationIds);

    /**
     * 여러 대화의 모든 메시지 ID 조회
     */
    @Query("SELECT m.id FROM Message m WHERE m.conversation.id IN :conversationIds")
    List<Long> findIdsByConversationIdIn(@Param("conversationIds") List<Long> conversationIds);

    /**
     * 특정 대화들의 모든 메시지 삭제 (벌크 삭제)
     */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversation.id IN :conversationIds")
    void deleteByConversationIdInBulk(@Param("conversationIds") List<Long> conversationIds);
}
