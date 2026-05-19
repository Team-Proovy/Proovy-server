package com.proovy.global.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableRedisRepositories
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(host);
        config.setPort(port);
        
        // 비밀번호 설정 (비어있지 않은 경우)
        if (sslEnabled && (password == null || password.isBlank())) {
            throw new IllegalStateException("spring.data.redis.password must be set when SSL is enabled");
        }

        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }

        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfigBuilder = 
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(5000));

        // SSL 설정 (prod 환경에서 Upstash 호환)
        if (sslEnabled) {
            ClientOptions clientOptions = ClientOptions.builder()
                    .protocolVersion(ProtocolVersion.RESP2)  // Upstash Redis 호환을 위해 RESP2 명시
                    .sslOptions(SslOptions.builder()
                            .jdkSslProvider()  // JDK SSL 프로바이더 사용
                            .build())
                    .socketOptions(SocketOptions.builder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .keepAlive(true)  // Keep-alive 활성화
                            .build())
                    .autoReconnect(true)  // 자동 재연결 활성화
                    .build();
            
            clientConfigBuilder
                    .clientOptions(clientOptions)
                    .useSsl()
                    .and()
                    .shutdownTimeout(Duration.ofMillis(100));
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfigBuilder.build());
        factory.setValidateConnection(true);
        return factory;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(redisConnectionFactory());
        return template;
    }
}
