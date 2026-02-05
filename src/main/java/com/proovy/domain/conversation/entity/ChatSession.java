package com.proovy.domain.conversation.entity;

import com.proovy.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_session_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "external_thread_id", unique = true, length = 100)
    private String externalThreadId; // Proovy-ai thread_id

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ChatSessionStatus status; // active, closed

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Builder
    public ChatSession(User user, String externalThreadId, ChatSessionStatus status) {
        this.user = user;
        this.externalThreadId = externalThreadId;
        this.status = status != null ? status : ChatSessionStatus.ACTIVE;
    }

    public void updateExternalThreadId(String externalThreadId) {
        this.externalThreadId = externalThreadId;
    }

    public void close() {
        this.status = ChatSessionStatus.CLOSED;
        this.closedAt = LocalDateTime.now();
    }

    public void activate() {
        this.status = ChatSessionStatus.ACTIVE;
        this.closedAt = null;
    }
}
