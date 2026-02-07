package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
