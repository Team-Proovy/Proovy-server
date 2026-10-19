package com.proovy.domain.conversation.repository;

import com.proovy.domain.conversation.entity.TurnAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TurnAttachmentRepository extends JpaRepository<TurnAttachment, UUID> {

    List<TurnAttachment> findBySourceMessageIdOrderByAttachmentIndexAsc(UUID sourceMessageId);
}
