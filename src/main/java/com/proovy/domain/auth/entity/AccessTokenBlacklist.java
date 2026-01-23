package com.proovy.domain.auth.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash(value = "blacklist")
public class AccessTokenBlacklist {

    @Id
    private String token;

    private Long userId;

    private LocalDateTime createdAt;

    @TimeToLive(unit = TimeUnit.SECONDS)
    private Long ttl;
}
