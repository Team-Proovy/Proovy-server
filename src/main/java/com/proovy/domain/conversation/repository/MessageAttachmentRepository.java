package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, Long> {

    /**
     * 특정 메시지의 첨부 파일 목록 조회
     */
    List<MessageAttachment> findByChatMessageId(Long chatMessageId);
}
