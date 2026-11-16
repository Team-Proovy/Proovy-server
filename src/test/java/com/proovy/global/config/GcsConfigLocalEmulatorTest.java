package com.proovy.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.proovy.global.config.GcsProperties.LocalEmulator;
import com.proovy.global.infra.gcs.GcsLocalSigning;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcsConfigLocalEmulatorTest {

    private final GcsConfig gcsConfig = new GcsConfig();

    @Test
    void disabledLocalEmulatorPreservesDefaultPath() throws Exception {
        GcsProperties props = new GcsProperties(
                "proovy-prod",
                "proovy-assets",
                null,
                "http://127.0.0.1:9",
                15,
                LocalEmulator.disabled());

        Storage storage = gcsConfig.gcsStorage(props);
        GcsLocalSigning signing = gcsConfig.gcsLocalSigning(props);

        assertThat(storage.getOptions().getProjectId()).isEqualTo("proovy-prod");
        assertThat(storage.getOptions().getHost()).isEqualTo("http://127.0.0.1:9");
        assertThat(storage.getOptions().getCredentials()).isNotEqualTo(NoCredentials.getInstance());
        assertThat(signing.isEnabled()).isFalse();
    }

    @Test
    void enabledLocalEmulatorConfiguresNoCredentialsRpcAndRsaSigner(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("signer.pem");
        Files.writeString(keyFile, generatePkcs8Pem(2048), StandardCharsets.UTF_8);

        GcsProperties props = new GcsProperties(
                "local-project",
                "proovy-local-assets",
                null,
                "http://gcs:4443",
                15,
                new LocalEmulator(
                        true,
                        "https://Mac-Mini.Tailnet.ts.net/",
                        keyFile.toString(),
                        null,
                        null));

        Storage storage = gcsConfig.gcsStorage(props);
        GcsLocalSigning signing = gcsConfig.gcsLocalSigning(props);

        assertThat(storage.getOptions().getHost()).isEqualTo("http://gcs:4443");
        assertThat(storage.getOptions().getCredentials()).isEqualTo(NoCredentials.getInstance());
        assertThat(signing.isEnabled()).isTrue();
        assertThat(signing.publicBaseUrl()).isEqualTo("https://mac-mini.tailnet.ts.net");
        assertThat(signing.signer().getAccount()).isEqualTo(LocalEmulator.DEFAULT_SIGNER_CLIENT_EMAIL);
    }

    @Test
    void enabledLocalEmulatorFailsClosedOnUnsafeOrIncompleteConfig(@TempDir Path tempDir) throws Exception {
        String pem = generatePkcs8Pem(2048);

        // Missing endpoint
        assertThatThrownBy(() -> gcsConfig.gcsStorage(new GcsProperties(
                "local", "bucket", null, "", 15,
                new LocalEmulator(true, "https://host.ts.net", null, pem, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gcs.endpoint");

        // Mixing prod credentials-json with local-emulator
        assertThatThrownBy(() -> gcsConfig.gcsStorage(new GcsProperties(
                "local", "bucket", "{\"type\":\"service_account\"}", "http://gcs:4443", 15,
                new LocalEmulator(true, "https://host.ts.net", null, pem, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials-json");

        // Non-HTTPS or non-443 or subpath public base URLs
        for (String badUrl : new String[] {
                "http://host.ts.net",
                "https://host.ts.net:5173",
                "https://host.ts.net/prefix",
                "https://127.0.0.1",
                "https://100.64.1.10",
                "https://[::1]",
                "https://2130706433",
                "https://0x7f000001",
                "https://user:pass@host.ts.net"
        }) {
            assertThatThrownBy(() -> GcsConfig.validatePublicBaseUrl(badUrl))
                    .as("Expected rejection for publicBaseUrl=%s", badUrl)
                    .isInstanceOf(IllegalStateException.class);
        }

        // Missing private key
        assertThatThrownBy(() -> gcsConfig.gcsLocalSigning(new GcsProperties(
                "local", "bucket", null, "http://gcs:4443", 15,
                new LocalEmulator(true, "https://host.ts.net", null, null, null))))
                .isInstanceOf(IllegalStateException.class);

        // Weak (<2048 bit) RSA key rejected
        String weakPem = generatePkcs8Pem(1024);
        assertThatThrownBy(() -> gcsConfig.gcsLocalSigning(new GcsProperties(
                "local", "bucket", null, "http://gcs:4443", 15,
                new LocalEmulator(true, "https://host.ts.net", null, weakPem, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");

        // Missing file fails cleanly
        Path missing = tempDir.resolve("missing.pem");
        assertThatThrownBy(() -> gcsConfig.gcsLocalSigning(new GcsProperties(
                "local", "bucket", null, "http://gcs:4443", 15,
                new LocalEmulator(true, "https://host.ts.net", missing.toString(), null, null))))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsPkcs1KeyHeaderAndAcceptsDirectPem() throws Exception {
        assertThatThrownBy(() -> GcsConfig.parsePkcs8RsaPrivateKey(
                "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");

        RSAPrivateKey parsed = GcsConfig.parsePkcs8RsaPrivateKey(generatePkcs8Pem(2048));
        assertThat(parsed.getModulus().bitLength()).isEqualTo(2048);
    }

    static String generatePkcs8Pem(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        KeyPair pair = generator.generateKeyPair();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
