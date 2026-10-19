package com.proovy.domain.run.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * replay 가능한 durable run event (append-only). {@code (run_id, seq)}와
 * {@code (run_id, source_event_id)}가 각각 unique이므로 같은 source event 재수신은 row·seq를 늘리지 않는다.
 * canonical 응답 본문은 중복 저장하지 않고 replay 시 message에서 채운다.
 */
@Entity
@Immutable
@Table(name = "ai_run_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(nullable = false)
    private Long seq;

    @Convert(converter = AiRunEventType.Converter.class)
    @Column(name = "event_type", nullable = false, length = 40)
    private AiRunEventType eventType;

    @Column(name = "source_event_id", length = 128)
    private String sourceEventId;

    private Integer attempt;

    @Column(name = "activity_id", length = 128)
    private String activityId;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    private AiRunEvent(UUID runId, Long noteId, long seq, AiRunEventType eventType, String sourceEventId,
                       Integer attempt, String activityId, JsonNode payload, Instant createdAt) {
        this.runId = Objects.requireNonNull(runId);
        this.noteId = Objects.requireNonNull(noteId);
        this.seq = seq;
        this.eventType = Objects.requireNonNull(eventType);
        this.sourceEventId = sourceEventId;
        this.attempt = attempt;
        this.activityId = activityId;
        this.payload = Objects.requireNonNull(payload);
        this.createdAt = Objects.requireNonNull(createdAt);
    }
}
