package com.proovy.domain.conversation.entity;

import com.proovy.domain.note.entity.Note;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Note 도메인 전용 Conversation 엔티티
 * 
 * ⚠️ 주의: 이 엔티티는 Note 도메인(NoteService.createNote)에서만 사용됩니다.
 * 채팅 기능은 ChatSession 엔티티를 사용하세요.
 * 
 * TODO: Note 도메인을 ChatSession 기반으로 마이그레이션하면 이 엔티티는 삭제 가능
 */
@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversation_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    private Note note;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Conversation(Note note) {
        this.note = note;
    }
}

