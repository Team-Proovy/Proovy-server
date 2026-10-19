package com.proovy.global.infra.brain;

/** local/test 전용 pre-shared token (Brain {@code internal_api_auth_mode=shared_token}). */
public final class SharedTokenCredentials implements BrainServiceCredentials {

    private final String token;

    public SharedTokenCredentials(String token) {
        if (token == null || token.isBlank() || !token.equals(token.strip()) || token.contains(" ")) {
            throw new IllegalArgumentException("proovy.brain.auth.shared-token must be a single non-blank token");
        }
        this.token = token;
    }

    @Override
    public String bearerToken() {
        return token;
    }

    @Override
    public String toString() {
        return "SharedTokenCredentials[<redacted>]";
    }
}
