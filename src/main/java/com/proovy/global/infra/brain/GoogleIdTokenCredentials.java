package com.proovy.global.infra.brain;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import java.io.IOException;

/**
 * production: Server Cloud Run 서비스 계정의 Google ID token(고정 audience = Brain 서비스 URL).
 * Brain은 audience와 서비스 계정 email을 검증한다({@code internal_api_auth_mode=oidc}).
 * token은 만료 전까지 재사용하고 만료되면 metadata server에서 갱신한다.
 */
public final class GoogleIdTokenCredentials implements BrainServiceCredentials {

    private final String audience;
    private final IdTokenCredentials credentials;

    public GoogleIdTokenCredentials(IdTokenProvider provider, String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("proovy.brain.auth.audience is required for id-token auth");
        }
        this.audience = audience;
        this.credentials = IdTokenCredentials.newBuilder()
                .setIdTokenProvider(provider)
                .setTargetAudience(audience)
                .build();
    }

    /** Application Default Credentials(Cloud Run에서는 서비스 계정)로 만든다. */
    public static GoogleIdTokenCredentials fromApplicationDefault(String audience) {
        GoogleCredentials source;
        try {
            source = GoogleCredentials.getApplicationDefault();
        } catch (IOException e) {
            throw new IllegalStateException("Application Default Credentials are unavailable for Brain id-token auth");
        }
        if (!(source instanceof IdTokenProvider provider)) {
            throw new IllegalStateException("Application Default Credentials cannot mint ID tokens");
        }
        return new GoogleIdTokenCredentials(provider, audience);
    }

    public String audience() {
        return audience;
    }

    @Override
    public synchronized String bearerToken() {
        try {
            credentials.refreshIfExpired();
        } catch (IOException e) {
            throw new IllegalStateException("could not mint a Brain ID token");
        }
        return credentials.getIdToken().getTokenValue();
    }

    @Override
    public String toString() {
        return "GoogleIdTokenCredentials[audience=" + audience + "]";
    }
}
