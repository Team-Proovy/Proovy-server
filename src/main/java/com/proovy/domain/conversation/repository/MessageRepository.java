package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note 도메인 전용 MessageRepository
 */
public interface MessageRepository extends JpaRepository<Message, Long> {
}
