package com.proovy.domain.conversation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.note.entity.Note;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Note에 직접 연결된 대화 메시지 (conversation_id = note_id).
 *
 * <p>assistant canonical 본문(response_pages·source_references·plain_text)은 terminal
 * transaction(1.82)에서 한 번만 채워지며, 이후 DB trigger가 변경을 막는다.
 * Source Reference catalog는 Note Asset FK에 묶이지 않은 snapshot이다.
 */
@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @Column(name = "message_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false, updatable = false)
    private Note note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private MessageRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageStatus status;

    @Type(JsonBinaryType.class)
    @Column(name = "user_input", columnDefinition = "jsonb", updatable = false)
    private JsonNode userInput; // USER: {"text": ..., "latex"?: ...}

    @Type(JsonBinaryType.class)
    @Column(name = "response_pages", columnDefinition = "jsonb")
    private JsonNode responsePages; // ASSISTANT COMPLETED: canonical response_pages

    @Type(JsonBinaryType.class)
    @Column(name = "source_references", columnDefinition = "jsonb")
    private JsonNode sourceReferences; // ASSISTANT COMPLETED: Source Reference snapshot catalog

    @Column(name = "plain_text", columnDefinition = "TEXT")
    private String plainText; // 공개 본문 파생 plain text (USER는 입력 text)

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    private ChatMessage(UUID id, Note note, MessageRole role, MessageStatus status,
                        JsonNode userInput, String plainText, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.note = Objects.requireNonNull(note);
        this.role = role;
        this.status = status;
        this.userInput = userInput;
        this.plainText = plainText;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /**
     * 사용자 메시지. userInput은 반드시 문자열 text를 가진 object여야 한다.
     */
    public static ChatMessage user(UUID id, Note note, JsonNode userInput, Instant createdAt) {
        if (userInput == null || !userInput.isObject() || !userInput.path("text").isTextual()) {
            throw new IllegalArgumentException("user message input must be an object with a text field");
        }
        return new ChatMessage(id, note, MessageRole.USER, MessageStatus.COMPLETED,
                userInput, userInput.get("text").asText(), createdAt);
    }

    /**
     * 본문 없는 assistant placeholder (Active Run 진행 표시용).
     */
    public static ChatMessage assistantPlaceholder(UUID id, Note note, Instant createdAt) {
        return new ChatMessage(id, note, MessageRole.ASSISTANT, MessageStatus.PENDING,
                null, null, createdAt);
    }
}
