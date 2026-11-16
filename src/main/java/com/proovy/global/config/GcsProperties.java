package com.proovy.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code gcs.*} 스토리지 설정.
 * 기본값({@code gcs.local-emulator.enabled=false})에서는 기존 운영 GCS + ADC/SA JSON 경로를 그대로 유지하며,
 * 로컬 에뮬레이터·오프라인 RSA V4 서명은 명시적 opt-in 시에만 활성화된다.
 */
@ConfigurationProperties(prefix = "gcs")
public record GcsProperties(
        String projectId,
        @DefaultValue("proovy-assets-dev") String bucket,
        String credentialsJson,
        String endpoint,
        @DefaultValue("15") int readUrlTtlMinutes,
        LocalEmulator localEmulator) {

    public static final int DEFAULT_READ_URL_TTL_MINUTES = 15;

    public GcsProperties {
        if (bucket == null || bucket.isBlank()) {
            bucket = "proovy-assets-dev";
        }
        if (readUrlTtlMinutes <= 0) {
            throw new IllegalArgumentException("gcs.read-url-ttl-minutes must be positive");
        }
        if (localEmulator == null) {
            localEmulator = LocalEmulator.disabled();
        }
    }

    public record LocalEmulator(
            @DefaultValue("false") boolean enabled,
            String publicBaseUrl,
            String signerPrivateKeyPath,
            String signerPrivateKeyPem,
            String signerClientEmail) {

        public static final String DEFAULT_SIGNER_CLIENT_EMAIL = "local-signer@proovy.local";

        public LocalEmulator {
            if (signerClientEmail == null || signerClientEmail.isBlank()) {
                signerClientEmail = DEFAULT_SIGNER_CLIENT_EMAIL;
            }
        }

        public static LocalEmulator disabled() {
            return new LocalEmulator(false, null, null, null, DEFAULT_SIGNER_CLIENT_EMAIL);
        }
    }
}
