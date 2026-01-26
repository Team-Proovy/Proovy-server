package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 특정 노트의 대화 개수 조회
     */
    long countByNoteId(Long noteId);
}

