package com.proovy.domain.note.repository;

import com.proovy.domain.note.entity.Note;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NoteRepository extends JpaRepository<Note, Long> {

    interface EmbeddingSourceProjection {
        Long getNoteId();
        Long getUserId();
        String getContentMd();
    }

    List<Note> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserId(Long userId);

    @Query("SELECT n FROM Note n WHERE n.user.id = :userId " +
           "AND LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY n.createdAt DESC")
    List<Note> searchByTitleKeyword(@Param("userId") Long userId, @Param("keyword") String keyword);

    /**
     * 사용자의 노트 목록 페이지네이션 조회
     */
    Page<Note> findByUserId(Long userId, Pageable pageable);

    @Query("""
           SELECT n.id AS noteId, n.user.id AS userId, n.contentMd AS contentMd
           FROM Note n
           WHERE n.id = :noteId
           """)
    java.util.Optional<EmbeddingSourceProjection> findEmbeddingSourceById(@Param("noteId") Long noteId);

    /**
     * 특정 사용자의 모든 노트 삭제 (회원 탈퇴용)
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Note n WHERE n.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * 특정 사용자의 모든 노트 ID 조회
     */
    @Query("SELECT n.id FROM Note n WHERE n.user.id = :userId")
    List<Long> findIdsByUserId(@Param("userId") Long userId);
}
