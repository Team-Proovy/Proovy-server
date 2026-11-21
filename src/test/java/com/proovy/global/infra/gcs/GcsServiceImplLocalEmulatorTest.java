package com.proovy.global.infra.gcs;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.proovy.global.config.GcsConfig;
import com.proovy.global.config.GcsProperties;
import com.proovy.global.config.GcsProperties.LocalEmulator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GcsServiceImplLocalEmulatorTest {

    private final GcsConfig gcsConfig = new GcsConfig();

    @Test
    void localEmulatorSignsPathStyleUrlsWithFrontHttpsHostAndNoInternalEndpointLeak() throws Exception {
        String pem = generatePkcs8Pem();
        GcsProperties props = new GcsProperties(
                "local-project",
                "proovy-local-assets",
                null,
                "http://gcs:4443",
                15,
                new LocalEmulator(
                        true,
                        "https://mac.tailnet.ts.net",
                        null,
                        pem,
                        "local-signer@proovy.local"));

        Storage storage = gcsConfig.gcsStorage(props);
        GcsLocalSigning signing = gcsConfig.gcsLocalSigning(props);
        GcsServiceImpl service = new GcsServiceImpl(storage, props, signing);

        String assetKey = "users/10/notes/20/assets/uuid-1_my photo.jpg";
        String thumbKey = "users/10/notes/20/thumbnails/uuid-1_thumb.jpg";

        String uploadUrl = service.generatePresignedUploadUrl(assetKey, "image/jpeg", 10);
        URI uploadUri = URI.create(uploadUrl);
        assertThat(uploadUri.getScheme()).isEqualTo("https");
        assertThat(uploadUri.getHost()).isEqualTo("mac.tailnet.ts.net");
        assertThat(uploadUri.getPort()).isEqualTo(-1);
        assertThat(uploadUri.getRawPath()).isEqualTo("/proovy-local-assets/users/10/notes/20/assets/uuid-1_my%20photo.jpg");
        assertThat(uploadUri.getRawPath()).doesNotContain("%2F");
        assertThat(uploadUri.getRawQuery())
                .contains("X-Goog-Algorithm=GOOG4-RSA-SHA256")
                .contains("X-Goog-SignedHeaders=host")
                .contains("X-Goog-Signature=");

        String downloadUrl = service.generatePresignedDownloadUrl(assetKey, "내 사진.jpg", 10);
        URI downloadUri = URI.create(downloadUrl);
        assertThat(downloadUri.getScheme()).isEqualTo("https");
        assertThat(downloadUri.getHost()).isEqualTo("mac.tailnet.ts.net");
        assertThat(downloadUri.getRawPath()).startsWith("/proovy-local-assets/users/10/notes/20/assets/");
        assertThat(downloadUri.getRawPath()).doesNotContain("%2F");
        assertThat(downloadUri.getRawQuery()).contains("X-Goog-Algorithm=GOOG4-RSA-SHA256");

        String readUrl = service.generatePresignedReadUrl(assetKey, 15);
        URI readUri = URI.create(readUrl);
        assertThat(readUri.getScheme()).isEqualTo("https");
        assertThat(readUri.getHost()).isEqualTo("mac.tailnet.ts.net");
        assertThat(readUri.getRawPath()).isEqualTo("/proovy-local-assets/users/10/notes/20/assets/uuid-1_my%20photo.jpg");
        assertThat(readUri.getRawQuery())
                .contains("X-Goog-Algorithm=GOOG4-RSA-SHA256")
                .contains("X-Goog-SignedHeaders=host")
                .contains("X-Goog-Signature=");

        String thumbUrl = service.getThumbnailUrl(thumbKey);
        assertThat(thumbUrl)
                .isEqualTo("https://mac.tailnet.ts.net/proovy-local-assets/users/10/notes/20/thumbnails/uuid-1_thumb.jpg")
                .doesNotContain("http://gcs:4443")
                .doesNotContain("%2F");
        assertThat(service.getThumbnailUrl(null)).isNull();
    }

    @Test
    void disabledLocalSigningPreservesLegacyFileUrlFormatting() {
        Storage storage = org.mockito.Mockito.mock(Storage.class);
        GcsServiceImpl prodDefault = new GcsServiceImpl(storage, "prod-bucket", "", GcsLocalSigning.disabled());
        assertThat(prodDefault.getFileUrl("users/1/file.png"))
                .isEqualTo("https://storage.googleapis.com/prod-bucket/users%2F1%2Ffile.png");

        GcsServiceImpl customEndpoint = new GcsServiceImpl(storage, "dev-bucket", "http://127.0.0.1:9",
                GcsLocalSigning.disabled());
        assertThat(customEndpoint.getFileUrl("users/1/file.png"))
                .isEqualTo("http://127.0.0.1:9/dev-bucket/users%2F1%2Ffile.png");
    }

    private static String generatePkcs8Pem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
