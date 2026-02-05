package com.proovy.domain.conversation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "message_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class MessageAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_attachment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_message_id", nullable = false)
    private ChatMessage chatMessage;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(nullable = false, length = 20)
    private String source; // upload, ai_generated

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode metadata;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public MessageAttachment(ChatMessage chatMessage, String fileName, String mimeType, 
                           String storageKey, String source, JsonNode metadata) {
        this.chatMessage = chatMessage;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.storageKey = storageKey;
        this.source = source;
        this.metadata = metadata;
    }
}
