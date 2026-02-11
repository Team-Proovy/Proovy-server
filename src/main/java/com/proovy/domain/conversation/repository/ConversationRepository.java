package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
     * 특정 노트의 모든 대화 조회
     */
    //List<Conversation> findByNoteId(Long noteId);

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
     * 특정 노트의 모든 대화 조회
     */
    //List<Conversation> findByNoteId(Long noteId);

    /**
     * 특정 노트의 대화 조회 (페이징)
     */
    Page<Conversation> findByNoteIdOrderByCreatedAtDesc(Long noteId, Pageable pageable);

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

    /**
     * 여러 노트의 대화 조회
     */
    @Query("SELECT c FROM Conversation c WHERE c.note.id IN :noteIds")
    List<Conversation> findByNoteIdIn(@Param("noteIds") List<Long> noteIds);

    // =============================================
    // Full-Text Search (DB 레벨 필터링)
    // =============================================

    /**
     * Full-Text Search (tsvector) - 모든 필터 DB 레벨 적용
     */
    @Query(value = """
            SELECT DISTINCT c.* FROM conversations c
            JOIN notes n ON c.note_id = n.id
            JOIN messages m ON m.conversation_id = c.id
            LEFT JOIN message_tools mt ON mt.message_id = m.id
            WHERE n.user_id = :userId
              AND m.search_vector @@ plainto_tsquery('simple', :query)
              AND (:noteId IS NULL OR n.id = :noteId)
              AND (:toolCode IS NULL OR mt.tool_code = :toolCode)
              AND (:startDate IS NULL OR DATE(c.created_at) >= :startDate)
              AND (:endDate IS NULL OR DATE(c.created_at) <= :endDate)
            ORDER BY c.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT c.id) FROM conversations c
            JOIN notes n ON c.note_id = n.id
            JOIN messages m ON m.conversation_id = c.id
            LEFT JOIN message_tools mt ON mt.message_id = m.id
            WHERE n.user_id = :userId
              AND m.search_vector @@ plainto_tsquery('simple', :query)
              AND (:noteId IS NULL OR n.id = :noteId)
              AND (:toolCode IS NULL OR mt.tool_code = :toolCode)
              AND (:startDate IS NULL OR DATE(c.created_at) >= :startDate)
              AND (:endDate IS NULL OR DATE(c.created_at) <= :endDate)
            """,
            nativeQuery = true)
    Page<Conversation> searchByFullText(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("noteId") Long noteId,
            @Param("toolCode") String toolCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    /**
     * Trigram Search (한글) - 모든 필터 DB 레벨 적용
     */
    @Query(value = """
            SELECT DISTINCT c.* FROM conversations c
            JOIN notes n ON c.note_id = n.id
            JOIN messages m ON m.conversation_id = c.id
            LEFT JOIN message_tools mt ON mt.message_id = m.id
            WHERE n.user_id = :userId
              AND m.content ILIKE '%' || :query || '%'
              AND (:noteId IS NULL OR n.id = :noteId)
              AND (:toolCode IS NULL OR mt.tool_code = :toolCode)
              AND (:startDate IS NULL OR DATE(c.created_at) >= :startDate)
              AND (:endDate IS NULL OR DATE(c.created_at) <= :endDate)
            ORDER BY c.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT c.id) FROM conversations c
            JOIN notes n ON c.note_id = n.id
            JOIN messages m ON m.conversation_id = c.id
            LEFT JOIN message_tools mt ON mt.message_id = m.id
            WHERE n.user_id = :userId
              AND m.content ILIKE '%' || :query || '%'
              AND (:noteId IS NULL OR n.id = :noteId)
              AND (:toolCode IS NULL OR mt.tool_code = :toolCode)
              AND (:startDate IS NULL OR DATE(c.created_at) >= :startDate)
              AND (:endDate IS NULL OR DATE(c.created_at) <= :endDate)
            """,
            nativeQuery = true)
    Page<Conversation> searchByTrigram(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("noteId") Long noteId,
            @Param("toolCode") String toolCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);
}

