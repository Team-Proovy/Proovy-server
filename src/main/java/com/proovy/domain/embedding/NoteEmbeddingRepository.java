package com.proovy.domain.embedding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NoteEmbeddingRepository extends JpaRepository<NoteEmbedding, Long> {

    Optional<NoteEmbedding> findByNoteIdAndEmbeddingModel(Long noteId, String embeddingModel);

    void deleteByNoteId(Long noteId);
}
