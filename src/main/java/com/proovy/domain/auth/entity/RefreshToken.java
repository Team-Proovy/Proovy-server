package com.proovy.domain.auth.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash(value = "refreshToken", timeToLive = 604800) // 7일
public class RefreshToken {

    @Id
    private String token;           // Refresh Token 값 = ID

    @Indexed                        // userId로 조회 가능
    private Long userId;

    private LocalDateTime createdAt;
}
