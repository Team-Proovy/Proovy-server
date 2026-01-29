package com.proovy.domain.conversation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "message_tools")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageTool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(nullable = false, length = 50)
    private String toolCode;

    @Builder
    public MessageTool(Message message, String toolCode) {
        this.message = message;
        this.toolCode = toolCode;
    }
}

