package com.proovy.global.config;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.proovy.global.infra.gcs.GcsLocalSigning;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GcsProperties.class)
public class GcsConfig {

    private static final int MIN_RSA_KEY_BITS = 2048;
    private static final Pattern IPV4_DOTTED = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Pattern NUMERIC_HOST = Pattern.compile("^(?:\\d+|0x[0-9a-f]+)$");
    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern UNSAFE_BUCKET_CHARS = Pattern.compile("[/?#\\s]");

    @Bean
    public Storage gcsStorage(GcsProperties properties) throws IOException {
        StorageOptions.Builder builder = StorageOptions.newBuilder()
                .setProjectId(properties.projectId());

        if (properties.localEmulator().enabled()) {
            validateLocalEmulatorConfig(properties);
            builder.setCredentials(NoCredentials.getInstance());
            builder.setHost(properties.endpoint());
            return builder.build().getService();
        }

        String credentialsJson = properties.credentialsJson();
        if (credentialsJson != null && !credentialsJson.isBlank()) {
            ServiceAccountCredentials credentials = ServiceAccountCredentials
                    .fromStream(new ByteArrayInputStream(
                            credentialsJson.getBytes(StandardCharsets.UTF_8)));
            builder.setCredentials(credentials);
        }
        // credentialsJson 미설정 시 ADC(Application Default Credentials) 사용
        // Cloud Run에서는 서비스 계정이 자동 적용됨

        String endpoint = properties.endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.setHost(endpoint);
        }

        return builder.build().getService();
    }

    @Bean
    public GcsLocalSigning gcsLocalSigning(GcsProperties properties) throws IOException {
        if (!properties.localEmulator().enabled()) {
            return GcsLocalSigning.disabled();
        }
        String publicBaseUrl = validateLocalEmulatorConfig(properties);
        RSAPrivateKey privateKey = loadLocalSignerPrivateKey(properties.localEmulator());
        ServiceAccountSigner signer = ServiceAccountCredentials.newBuilder()
                .setClientEmail(properties.localEmulator().signerClientEmail())
                .setPrivateKey(privateKey)
                .setPrivateKeyId("local-emulator-key")
                .build();
        return GcsLocalSigning.enabled(publicBaseUrl, signer);
    }

    static String validateLocalEmulatorConfig(GcsProperties properties) {
        if (properties.credentialsJson() != null && !properties.credentialsJson().isBlank()) {
            throw new IllegalStateException(
                    "gcs.credentials-json must not be set when gcs.local-emulator.enabled=true");
        }
        String endpoint = properties.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "gcs.endpoint must be set when gcs.local-emulator.enabled=true");
        }
        validateEmulatorEndpoint(endpoint);
        validateBucket(properties.bucket());
        return validatePublicBaseUrl(properties.localEmulator().publicBaseUrl());
    }

    private static void validateEmulatorEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("gcs.endpoint is not a valid URI", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("gcs.endpoint must be a valid http/https URL with host");
        }
    }

    private static void validateBucket(String bucket) {
        if (bucket == null || bucket.isBlank() || UNSAFE_BUCKET_CHARS.matcher(bucket).find()
                || bucket.contains("..")) {
            throw new IllegalStateException("gcs.bucket is invalid for local emulator path-style URLs");
        }
    }

    static String validatePublicBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalStateException(
                    "gcs.local-emulator.public-base-url must be set when gcs.local-emulator.enabled=true");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("gcs.local-emulator.public-base-url is invalid", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("gcs.local-emulator.public-base-url must use https");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "gcs.local-emulator.public-base-url must not include userinfo, query, or fragment");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalStateException("gcs.local-emulator.public-base-url must not contain a subpath");
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new IllegalStateException("gcs.local-emulator.public-base-url must use default HTTPS port 443");
        }
        String host = normalizeDnsHost(uri.getHost());
        return "https://" + host;
    }

    private static String normalizeDnsHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            throw new IllegalStateException("gcs.local-emulator.public-base-url host must not be blank");
        }
        String host = rawHost.trim();
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.length() > 253 || host.contains(":") || host.startsWith("[")) {
            throw new IllegalStateException(
                    "gcs.local-emulator.public-base-url host must be a DNS hostname (no IP literal)");
        }
        if (IPV4_DOTTED.matcher(host).matches() || NUMERIC_HOST.matcher(host).matches()) {
            throw new IllegalStateException(
                    "gcs.local-emulator.public-base-url host must be a DNS hostname (no IP literal)");
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!DNS_LABEL.matcher(label).matches()) {
                throw new IllegalStateException(
                        "gcs.local-emulator.public-base-url host is not a valid DNS hostname");
            }
        }
        return host;
    }

    static RSAPrivateKey loadLocalSignerPrivateKey(GcsProperties.LocalEmulator local) throws IOException {
        boolean hasPath = local.signerPrivateKeyPath() != null && !local.signerPrivateKeyPath().isBlank();
        boolean hasPem = local.signerPrivateKeyPem() != null && !local.signerPrivateKeyPem().isBlank();
        if (!hasPath && !hasPem) {
            throw new IllegalStateException(
                    "Either gcs.local-emulator.signer-private-key-path or signer-private-key-pem must be set");
        }
        if (hasPath && hasPem) {
            throw new IllegalStateException(
                    "Set only one of gcs.local-emulator.signer-private-key-path or signer-private-key-pem");
        }
        String pem = hasPath
                ? Files.readString(Path.of(local.signerPrivateKeyPath().trim()), StandardCharsets.UTF_8)
                : local.signerPrivateKeyPem();
        return parsePkcs8RsaPrivateKey(pem);
    }

    static RSAPrivateKey parsePkcs8RsaPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("Local emulator RSA private key PEM is blank");
        }
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException(
                    "Local emulator RSA private key must use PKCS#8 format (-----BEGIN PRIVATE KEY-----)");
        }
        String sanitized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s+", "");
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(sanitized);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Local emulator RSA private key PEM is not valid Base64", e);
        }
        try {
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
            if (!(key instanceof RSAPrivateKey rsaKey)) {
                throw new IllegalStateException("Local emulator signer key is not an RSAPrivateKey");
            }
            if (rsaKey.getModulus().bitLength() < MIN_RSA_KEY_BITS) {
                throw new IllegalStateException(
                        "Local emulator RSA signer key must be at least " + MIN_RSA_KEY_BITS + " bits");
            }
            return rsaKey;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to parse PKCS#8 RSA private key for local emulator", e);
        }
    }
}
