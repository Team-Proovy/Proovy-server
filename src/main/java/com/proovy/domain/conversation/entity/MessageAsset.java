package com.proovy.domain.conversation.entity;

import com.proovy.domain.asset.entity.Asset;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "message_assets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Builder
    public MessageAsset(Message message, Asset asset) {
        this.message = message;
        this.asset = asset;
    }
}

