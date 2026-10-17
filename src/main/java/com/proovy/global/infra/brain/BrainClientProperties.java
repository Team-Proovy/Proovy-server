package com.proovy.global.infra.brain;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code proovy.brain.*} — Brain 내부 실행 API client 설정. secret 값은 환경 변수로만 넣는다.
 *
 * @param baseUrl              Brain 서비스 origin(예: Cloud Run URL). id-token 모드는 https만 허용한다.
 * @param auth                 서비스 인증 모드와 값
 * @param connectTimeout       TCP 연결 상한
 * @param requestTimeout       status·cancel·cleanup 응답 전체 상한
 * @param streamIdleTimeout    stream에서 frame·keep-alive 사이 최대 공백(Brain keep-alive 10초보다 커야 한다)
 * @param streamDeadlineGrace  요청 {@code request_deadline_at} 뒤 stream을 끊기 전 여유(Brain tail 상한은 deadline + 4 × lease TTL)
 * @param maxResponseSize      JSON 응답·SSE frame 하나의 최대 크기
 */
@ConfigurationProperties(prefix = "proovy.brain")
public record BrainClientProperties(
        URI baseUrl,
        Auth auth,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("30s") Duration streamIdleTimeout,
        @DefaultValue("3m") Duration streamDeadlineGrace,
        @DefaultValue("4MB") DataSize maxResponseSize) {

    public enum AuthMode {
        /** production: Cloud Run 서비스 계정 ID token(고정 audience). */
        ID_TOKEN,
        /** local/test: pre-shared token. */
        SHARED_TOKEN
    }

    /**
     * @param mode        하나의 인증 모드
     * @param audience    ID token audience(Brain 서비스 URL). id-token 모드 필수
     * @param sharedToken local/test token. shared-token 모드 필수
     */
    public record Auth(AuthMode mode, String audience, String sharedToken) {

        @Override
        public String toString() {
            return "Auth[mode=" + mode + ", audience=" + audience + ", sharedToken="
                    + (sharedToken == null || sharedToken.isEmpty() ? "absent" : "<redacted>") + "]";
        }
    }

    public BrainClientProperties {
        if (baseUrl == null || !baseUrl.isAbsolute() || baseUrl.getHost() == null
                || !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("proovy.brain.base-url must be an absolute http(s) URL");
        }
        if (baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null || baseUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException("proovy.brain.base-url must not carry userinfo, query or fragment");
        }
        if (auth == null || auth.mode() == null) {
            throw new IllegalArgumentException("proovy.brain.auth.mode is required (id-token or shared-token)");
        }
        if (auth.mode() == AuthMode.ID_TOKEN && !"https".equals(baseUrl.getScheme())) {
            throw new IllegalArgumentException("proovy.brain.base-url must be https for id-token auth");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
        requirePositive(streamIdleTimeout, "stream-idle-timeout");
        requirePositive(streamDeadlineGrace, "stream-deadline-grace");
        if (maxResponseSize.toBytes() <= 0 || maxResponseSize.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("proovy.brain.max-response-size is out of range");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("proovy.brain." + name + " must be positive");
        }
    }
}
