package com.proovy.domain.auth.service;

import com.proovy.domain.auth.entity.AccessTokenBlacklist;
import com.proovy.domain.auth.repository.AccessTokenBlacklistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessTokenBlacklistService {

    private final AccessTokenBlacklistRepository accessTokenBlacklistRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public void blacklist(String accessToken, Long userId) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }

        long remainingSeconds = jwtTokenProvider.getRemainingExpiration(accessToken);
        if (remainingSeconds <= 0) {
            return;
        }

        AccessTokenBlacklist blacklist = AccessTokenBlacklist.builder()
                .token(accessToken)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .ttl(remainingSeconds)
                .build();
        accessTokenBlacklistRepository.save(blacklist);
    }

    @Transactional(readOnly = true)
    public boolean isBlacklisted(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        return accessTokenBlacklistRepository.existsByToken(accessToken);
    }
}
