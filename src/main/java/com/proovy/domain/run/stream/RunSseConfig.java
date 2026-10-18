package com.proovy.domain.run.stream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RunSseProperties.class)
public class RunSseConfig {

    /** run SSE 전용 pool. 전역 executor/scheduler 기본 bean과 겹치지 않는 자체 타입이다({@link RunSseScheduler}). */
    @Bean(destroyMethod = "close")
    public RunSseScheduler runSseScheduler(RunSseProperties properties) {
        return new RunSseScheduler(properties.threads());
    }
}
