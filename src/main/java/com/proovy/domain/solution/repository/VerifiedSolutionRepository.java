package com.proovy.domain.solution.repository;

import com.proovy.domain.solution.entity.VerifiedSolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VerifiedSolutionRepository extends JpaRepository<VerifiedSolution, UUID> {

    List<VerifiedSolution> findByNoteIdOrderByCreatedAtAsc(Long noteId);

    List<VerifiedSolution> findByAssistantMessageId(UUID assistantMessageId);
}
