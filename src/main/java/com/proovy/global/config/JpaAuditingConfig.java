package com.proovy.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Configuration
public class JpaAuditingConfig {
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public DateTimeProvider seoulDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now(SEOUL_ZONE));
    }
}
