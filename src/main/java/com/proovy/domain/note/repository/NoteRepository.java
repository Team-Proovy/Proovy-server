package com.proovy.domain.note.repository;

import com.proovy.domain.note.entity.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NoteRepository extends JpaRepository<Note, Long> {

    List<Note> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT n FROM Note n WHERE n.user.id = :userId " +
           "AND LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY n.createdAt DESC")
    List<Note> searchByTitleKeyword(@Param("userId") Long userId, @Param("keyword") String keyword);
}
