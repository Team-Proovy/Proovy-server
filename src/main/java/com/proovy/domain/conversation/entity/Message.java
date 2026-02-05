package com.proovy.domain.conversation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Note 도메인 전용 Message 엔티티
 * 
 * ⚠️ 주의: 이 엔티티는 Note 도메인(NoteService.createNote)에서만 사용됩니다.
 * 채팅 기능은 ChatMessage 엔티티를 사용하세요.
 * 
 * TODO: Note 도메인을 ChatMessage 기반으로 마이그레이션하면 이 엔티티는 삭제 가능
 */
@Entity
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role; // USER, ASSISTANT

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MessageStatus status; // STREAMING, COMPLETED

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Message(Conversation conversation, MessageRole role, String content, MessageStatus status) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.status = status;
    }
}

