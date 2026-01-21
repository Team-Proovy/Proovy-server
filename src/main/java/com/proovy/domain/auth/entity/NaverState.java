package com.proovy.domain.auth.entity;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

/**
 * 네이버 OAuth state 저장용 Redis 엔티티
 * CSRF 방지를 위해 백엔드에서 생성/검증
 */
@Getter
@RedisHash("naver_state")
public class NaverState {

    @Id
    private String state;        // state 값 (UUID)

    @TimeToLive
    private Long ttl;            // TTL (초)

    @Builder
    public NaverState(String state, Long ttl) {
        this.state = state;
        this.ttl = ttl;
    }
}
