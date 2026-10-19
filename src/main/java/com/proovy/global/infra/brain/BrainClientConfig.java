package com.proovy.global.infra.brain;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * {@code proovy.brain.base-url}이 있을 때만 Brain client를 만든다(아직 소비자가 없는 test·local 기동을 막지 않는다).
 * prod profile은 id-token 모드만 허용한다 — shared token으로 production Brain을 부르지 않는다.
 */
@Configuration
@ConditionalOnExpression("!'${proovy.brain.base-url:}'.isBlank()")
@EnableConfigurationProperties(BrainClientProperties.class)
public class BrainClientConfig {

    @Bean
    public BrainServiceCredentials brainServiceCredentials(BrainClientProperties properties, Environment environment) {
        BrainClientProperties.Auth auth = properties.auth();
        if (environment.acceptsProfiles(Profiles.of("prod"))
                && auth.mode() != BrainClientProperties.AuthMode.ID_TOKEN) {
            throw new IllegalStateException("the prod profile requires proovy.brain.auth.mode=id-token");
        }
        return switch (auth.mode()) {
            case ID_TOKEN -> GoogleIdTokenCredentials.fromApplicationDefault(auth.audience());
            case SHARED_TOKEN -> new SharedTokenCredentials(auth.sharedToken());
        };
    }

    @Bean
    public BrainRunClient brainRunClient(BrainClientProperties properties, BrainServiceCredentials credentials) {
        return new BrainRunClient(properties, credentials, Clock.systemUTC());
    }
}
