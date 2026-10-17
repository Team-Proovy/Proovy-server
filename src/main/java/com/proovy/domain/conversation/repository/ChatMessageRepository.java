package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /**
     * 특정 노트의 모든 메시지 조회 (시간순)
     */
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.note.id = :noteId ORDER BY cm.createdAt ASC, cm.id ASC")
    List<ChatMessage> findByNoteIdOrdered(@Param("noteId") Long noteId);

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
     * Trigram Search: 메시지 plain text 또는 노트 제목으로 검색
     * plain_text는 공개 본문에서 파생된 값이며 미완료 placeholder에는 없다.
     */
    @Query(value = """
            SELECT cm.* FROM chat_messages cm
            JOIN notes n ON cm.note_id = n.note_id
            WHERE n.user_id = :userId
              AND n.status = 'ACTIVE'
              AND cm.status = 'COMPLETED'
              AND (cm.plain_text ILIKE '%' || :query || '%'
                   OR n.title ILIKE '%' || :query || '%')
              AND (CAST(:noteId AS BIGINT) IS NULL OR cm.note_id = CAST(:noteId AS BIGINT))
              AND (CAST(:startDate AS DATE) IS NULL OR DATE(cm.created_at AT TIME ZONE 'Asia/Seoul') >= CAST(:startDate AS DATE))
              AND (CAST(:endDate AS DATE) IS NULL OR DATE(cm.created_at AT TIME ZONE 'Asia/Seoul') <= CAST(:endDate AS DATE))
            ORDER BY cm.created_at DESC, cm.message_id
            """,
            countQuery = """
            SELECT COUNT(*) FROM chat_messages cm
            JOIN notes n ON cm.note_id = n.note_id
            WHERE n.user_id = :userId
              AND n.status = 'ACTIVE'
              AND cm.status = 'COMPLETED'
              AND (cm.plain_text ILIKE '%' || :query || '%'
                   OR n.title ILIKE '%' || :query || '%')
              AND (CAST(:noteId AS BIGINT) IS NULL OR cm.note_id = CAST(:noteId AS BIGINT))
              AND (CAST(:startDate AS DATE) IS NULL OR DATE(cm.created_at AT TIME ZONE 'Asia/Seoul') >= CAST(:startDate AS DATE))
              AND (CAST(:endDate AS DATE) IS NULL OR DATE(cm.created_at AT TIME ZONE 'Asia/Seoul') <= CAST(:endDate AS DATE))
            """,
            nativeQuery = true)
    Page<ChatMessage> searchByTrigram(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("noteId") Long noteId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);
}
