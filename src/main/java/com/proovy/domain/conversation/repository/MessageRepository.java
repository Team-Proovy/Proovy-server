package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {
}

