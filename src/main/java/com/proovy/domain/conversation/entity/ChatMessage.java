package com.proovy.domain.conversation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.note.entity.Note;
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
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_message_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_session_id", nullable = false)
    private ChatSession chatSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id")
    private Note note; // Note 기반 대화인 경우 연결 (nullable)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageRole role; // USER, ASSISTANT, SYSTEM

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode content; // JSONB로 유연한 데이터 저장

    @Column(name = "message_type", nullable = false, length = 50)
    private String messageType; // text, image, code, etc.

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MessageStatus status; // STREAMING, COMPLETED

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public ChatMessage(ChatSession chatSession, Note note, MessageRole role, JsonNode content, String messageType, MessageStatus status) {
        this.chatSession = chatSession;
        this.note = note;
        this.role = role;
        this.content = content;
        this.messageType = messageType != null ? messageType : "text";
        this.status = status;
    }

    public void updateContent(JsonNode content) {
        this.content = content;
    }

    public void updateContentAndStatus(JsonNode content, MessageStatus status) {
        this.content = content;
        this.status = status;
    }

    /**
     * content에서 텍스트 추출 (검색용)
     */
    public String getTextContent() {
        if (content == null) {
            return null;
        }
        if (content.has("text")) {
            return content.get("text").asText();
        }
        return content.toString();
    }
}
