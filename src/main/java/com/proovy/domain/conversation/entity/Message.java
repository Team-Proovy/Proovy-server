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
 * @deprecated 이 엔티티는 더 이상 사용되지 않습니다. ChatMessage를 사용하세요.
 * ⚠️ 주의: 마이그레이션 완료 후 이 파일은 삭제 예정입니다.
 *
 * 마이그레이션 단계:
 * 1. V28 마이그레이션으로 데이터가 chat_messages로 이동됨
 * 2. ConversationQueryService를 ChatMessage 기반으로 전환 필요
 * 3. 완료 후 messages/conversations 테이블 삭제
 */
@Deprecated(since = "2026-02-12", forRemoval = true)
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

    public void updateContent(String content) {
        this.content = content;
    }

    public void completeStreaming() {
        this.status = MessageStatus.COMPLETED;
    }
}
