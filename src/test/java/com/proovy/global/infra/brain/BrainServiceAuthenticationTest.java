package com.proovy.global.infra.brain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.auth.oauth2.IdToken;
import com.google.auth.oauth2.IdTokenProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 서비스 인증 선택·고정 audience·prod guard. 실제 Cloud Run 서비스 계정·IAM·Brain OIDC 검증 연결은 S14에서 확인한다.
 */
class BrainServiceAuthenticationTest {

    private static final String AUDIENCE = "https://proovy-brain-abc-du.a.run.app";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(BrainClientConfig.class);

    @Test
    @DisplayName("ID token은 설정한 audience 하나로만 발급받고 만료 전까지 재사용한다")
    void idTokenUsesFixedAudience() {
        List<String> audiences = new ArrayList<>();
        IdTokenProvider provider = (audience, options) -> {
            audiences.add(audience);
            return IdToken.create(jwt(audience, Instant.now().plusSeconds(3600)));
        };
        GoogleIdTokenCredentials credentials = new GoogleIdTokenCredentials(provider, AUDIENCE);

        String first = credentials.bearerToken();
        String second = credentials.bearerToken();

        assertThat(first).isEqualTo(second).startsWith("eyJ");
        assertThat(audiences).containsExactly(AUDIENCE);
        assertThat(credentials.toString()).doesNotContain(first);
    }

    @Test
    @DisplayName("ID token 발급 실패는 원인 없는 예외다(client는 CREDENTIALS_UNAVAILABLE로 바꾼다)")
    void idTokenFailureIsOpaque() throws Exception {
        IdTokenProvider provider = (audience, options) -> {
            throw new java.io.IOException("metadata server detail");
        };
        GoogleIdTokenCredentials credentials = new GoogleIdTokenCredentials(provider, AUDIENCE);
        assertThatThrownBy(credentials::bearerToken).hasNoCause().hasMessageNotContaining("metadata server detail");
    }

    @Test
    @DisplayName("audience 없는 id-token·공백 shared token은 만들 수 없다")
    void requiredValues() {
        IdTokenProvider provider = (audience, options) -> null;
        assertThatThrownBy(() -> new GoogleIdTokenCredentials(provider, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SharedTokenCredentials("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SharedTokenCredentials("two words")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SharedTokenCredentials("local-token").toString()).doesNotContain("local-token");
    }

    @Test
    @DisplayName("base-url이 없으면 client bean을 만들지 않는다")
    void disabledWithoutBaseUrl() {
        context.run(app -> assertThat(app).doesNotHaveBean(BrainRunClient.class));
        context.withPropertyValues("proovy.brain.base-url=")
                .run(app -> assertThat(app).doesNotHaveBean(BrainRunClient.class));
    }

    @Test
    @DisplayName("local/test: shared-token 모드 하나로 client를 만든다")
    void sharedTokenMode() {
        context.withPropertyValues("proovy.brain.base-url=http://localhost:8000",
                        "proovy.brain.auth.mode=shared-token", "proovy.brain.auth.shared-token=local-token")
                .run(app -> {
                    assertThat(app).hasSingleBean(BrainRunClient.class);
                    assertThat(app.getBean(BrainServiceCredentials.class)).isInstanceOf(SharedTokenCredentials.class);
                });
    }

    @Test
    @DisplayName("prod profile은 shared-token을 거부하고, id-token은 https base-url만 허용한다")
    void productionGuards() {
        context.withInitializer(app -> app.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("proovy.brain.base-url=https://brain.example",
                        "proovy.brain.auth.mode=shared-token", "proovy.brain.auth.shared-token=local-token")
                .run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("proovy.brain.base-url=http://brain.example",
                        "proovy.brain.auth.mode=id-token", "proovy.brain.auth.audience=" + AUDIENCE)
                .run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("proovy.brain.base-url=https://brain.example")
                .run(app -> assertThat(app).hasFailed());
    }

    private static String jwt(String audience, Instant expiry) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + expiry.getEpochSecond()
                + ",\"iat\":" + Instant.now().getEpochSecond() + ",\"email\":\"server@proovy.iam.gserviceaccount.com\"}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + encoder.encodeToString("sig".getBytes(StandardCharsets.UTF_8));
    }
}
