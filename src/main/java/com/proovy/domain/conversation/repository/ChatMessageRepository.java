package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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

    @Modifying
    @Query("DELETE FROM ChatMessage cm WHERE cm.chatSession.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    // =============================================
    // Note 기반 조회
    // =============================================

    /**
     * 특정 노트의 모든 메시지 조회 (시간순)
     */
    List<ChatMessage> findByNoteIdOrderByCreatedAtAsc(Long noteId);

    /**
     * 특정 노트의 메시지 개수 조회
     */
    long countByNoteId(Long noteId);

    /**
     * 여러 노트의 메시지 개수를 한 번에 조회
     */
    @Query("SELECT cm.note.id AS noteId, COUNT(cm) AS count " +
           "FROM ChatMessage cm " +
           "WHERE cm.note.id IN :noteIds " +
           "GROUP BY cm.note.id")
    List<Object[]> countByNoteIdIn(@Param("noteIds") List<Long> noteIds);

    /**
     * 여러 노트의 모든 메시지 조회 (시간순)
     */
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.note.id IN :noteIds ORDER BY cm.createdAt ASC")
    List<ChatMessage> findByNoteIdInOrderByCreatedAtAsc(@Param("noteIds") List<Long> noteIds);

    /**
     * 특정 노트의 모든 메시지 ID 조회
     */
    @Query("SELECT cm.id FROM ChatMessage cm WHERE cm.note.id = :noteId")
    List<Long> findIdsByNoteId(@Param("noteId") Long noteId);

    /**
     * 특정 노트의 모든 메시지 삭제 (벌크 삭제)
     */
    @Modifying
    @Query("DELETE FROM ChatMessage cm WHERE cm.note.id = :noteId")
    void deleteByNoteIdInBulk(@Param("noteId") Long noteId);

    // =============================================
    // Full-Text Search (JSONB content 필드에서 text 추출하여 검색)
    // =============================================

    /**
     * Full-Text Search: content_tsv 컬럼 기반 검색 (영문/숫자)
     * content_tsv는 트리거에 의해 자동 업데이트됨 (V30 마이그레이션)
     * COALESCE로 content_tsv가 NULL인 경우 to_tsvector 폴백 처리
     */
    @Query(value = """
            SELECT cm.* FROM chat_messages cm
            JOIN notes n ON cm.note_id = n.note_id
            WHERE n.user_id = :userId
              AND COALESCE(cm.content_tsv, to_tsvector('simple', COALESCE(cm.content->>'text', ''))) @@ plainto_tsquery('simple', :query)
              AND (CAST(:noteId AS BIGINT) IS NULL OR cm.note_id = CAST(:noteId AS BIGINT))
              AND (CAST(:startDate AS DATE) IS NULL OR DATE(cm.created_at) >= CAST(:startDate AS DATE))
              AND (CAST(:endDate AS DATE) IS NULL OR DATE(cm.created_at) <= CAST(:endDate AS DATE))
            ORDER BY cm.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM chat_messages cm
            JOIN notes n ON cm.note_id = n.note_id
            WHERE n.user_id = :userId
              AND COALESCE(cm.content_tsv, to_tsvector('simple', COALESCE(cm.content->>'text', ''))) @@ plainto_tsquery('simple', :query)
              AND (CAST(:noteId AS BIGINT) IS NULL OR cm.note_id = CAST(:noteId AS BIGINT))
              AND (CAST(:startDate AS DATE) IS NULL OR DATE(cm.created_at) >= CAST(:startDate AS DATE))
              AND (CAST(:endDate AS DATE) IS NULL OR DATE(cm.created_at) <= CAST(:endDate AS DATE))
            """,
            nativeQuery = true)
    Page<ChatMessage> searchByFullText(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("noteId") Long noteId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    /**
     * Trigram Search: 메시지 내용 또는 노트 제목으로 검색
     * ILIKE: PostgreSQL case-insensitive LIKE (한글/영문 모두 지원)
     */
    @Query(value = """
            SELECT cm.* FROM chat_messages cm
            JOIN notes n ON cm.note_id = n.note_id
            WHERE n.user_id = :userId
              AND (COALESCE(cm.content->>'text', '') ILIKE '%' || :query || '%'
                   OR n.title ILIKE '%' || :query || '%')
              AND (CAST(:noteId AS BIGINT) IS NULL OR cm.note_id = CAST(:noteId AS BIGINT))
              AND (CAST(:startDate AS DATE) IS NULL OR DATE(cm.created_at) >= CAST(:startDate AS DATE))
              AND (CAST(:endDate AS DATE) IS NULL OR DATE(cm.created_at) <= CAST(:endDate AS DATE))
            ORDER BY cm.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM chat_messages cm
            JOIN notes n ON cm.note_id = n.note_id
            WHERE n.user_id = :userId
              AND (COALESCE(cm.content->>'text', '') ILIKE '%' || :query || '%'
                   OR n.title ILIKE '%' || :query || '%')
              AND (CAST(:noteId AS BIGINT) IS NULL OR cm.note_id = CAST(:noteId AS BIGINT))
              AND (CAST(:startDate AS DATE) IS NULL OR DATE(cm.created_at) >= CAST(:startDate AS DATE))
              AND (CAST(:endDate AS DATE) IS NULL OR DATE(cm.created_at) <= CAST(:endDate AS DATE))
            """,
            nativeQuery = true)
    Page<ChatMessage> searchByTrigram(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("noteId") Long noteId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    /**
     * 검색어 하이라이트 추출
     */
    @Query(value = """
            SELECT ts_headline('simple', COALESCE(cm.content->>'text', ''), plainto_tsquery('simple', :query),
                   'StartSel=<mark>, StopSel=</mark>, MaxWords=50, MinWords=20')
            FROM chat_messages cm
            WHERE cm.chat_message_id = :messageId
            """, nativeQuery = true)
    String getSearchHighlight(@Param("messageId") Long messageId, @Param("query") String query);

    /**
     * Note 기반 대화 목록 조회 (USER 메시지와 그 다음 ASSISTANT 메시지를 쌍으로 그룹화)
     * 페이징 지원
     */
    @Query(value = """
            SELECT cm.* FROM chat_messages cm
            JOIN notes n ON cm.note_id = n.note_id
            WHERE n.user_id = :userId
              AND cm.note_id = :noteId
            ORDER BY cm.created_at ASC
            """,
            nativeQuery = true)
    List<ChatMessage> findByUserIdAndNoteId(
            @Param("userId") Long userId,
            @Param("noteId") Long noteId);
}
