package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, Long> {

    /**
     * 특정 메시지의 첨부 파일 목록 조회
     */
    List<MessageAttachment> findByChatMessageId(Long chatMessageId);

    @Modifying
    @Query("DELETE FROM MessageAttachment ma WHERE ma.chatMessage.chatSession.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
