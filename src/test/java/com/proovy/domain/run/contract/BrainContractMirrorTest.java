package com.proovy.domain.run.contract;

import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.EXPECTED_CONSUMER_VERSION;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.MIRROR;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.load;
import static com.proovy.domain.run.contract.BrowserRunContractTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D9: Server 사본은 pin된 Brain commit의 검증된 mirror여야 한다. */
class BrainContractMirrorTest {

    private static final JsonNode PIN = read(MIRROR.resolve("PIN.json"));
    private static final JsonNode MANIFEST = load("manifest.json");

    @Test
    @DisplayName("pin의 manifest sha256·consumer_version이 사본 manifest와 같다")
    void pinMatchesManifest() throws Exception {
        assertThat(PIN.get("repository").asText()).isEqualTo("Team-Proovy/proovy-brain");
        assertThat(PIN.get("commit").asText()).matches("[0-9a-f]{40}");
        assertThat(sha256(MIRROR.resolve(PIN.get("manifest").asText())))
                .isEqualTo(PIN.get("manifest_sha256").asText());
        assertThat(MANIFEST.get("consumer_version").asText())
                .isEqualTo(PIN.get("consumer_version").asText())
                .isEqualTo(EXPECTED_CONSUMER_VERSION);
        assertThat(MANIFEST.get("original_path").asText())
                .isEqualTo(PIN.get("source_path").asText() + "/browser-run-v1");
    }

    @Test
    @DisplayName("manifest에 적힌 모든 파일의 sha256이 사본과 같고 목록 밖 파일이 없다")
    void everyFileMatchesManifestDigest() throws Exception {
        Set<String> listed = new HashSet<>();
        for (String section : new String[]{"files", "dependencies"}) {
            for (var entry : MANIFEST.get(section).properties()) {
                assertThat(sha256(MIRROR.resolve(entry.getKey())))
                        .as(entry.getKey())
                        .isEqualTo(entry.getValue().asText());
                listed.add(entry.getKey());
            }
        }
        listed.add("browser-run-v1/manifest.json");
        listed.add("PIN.json");
        listed.add("README.md");
        // 다른 artifact 사본은 각자의 PIN-*.json이 소유하고 그 mirror test가 검사한다
        // (internal-run-v1: task 1.76 InternalRunContractMirrorTest, activity-v1: task 1.80 ActivityContractMirrorTest).
        try (Stream<Path> pins = Files.list(MIRROR)) {
            for (Path pinFile : pins.filter(path -> path.getFileName().toString().matches("PIN-.+\\.json")).toList()) {
                JsonNode pin = read(pinFile);
                pin.get("files").properties().forEach(entry -> listed.add(entry.getKey()));
                if (pin.hasNonNull("manifest")) {
                    listed.add(pin.get("manifest").asText());
                }
                listed.add(pinFile.getFileName().toString());
            }
        }
        try (Stream<Path> files = Files.walk(MIRROR)) {
            Set<String> onDisk = new HashSet<>();
            files.filter(Files::isRegularFile)
                    .forEach(path -> onDisk.add(MIRROR.relativize(path).toString().replace('\\', '/')));
            assertThat(onDisk).isEqualTo(listed);
        }
    }

    @Test
    @DisplayName("Java 계약 상수가 manifest의 version·limits와 같다")
    void constantsMatchManifest() {
        JsonNode limits = MANIFEST.get("limits");
        assertThat(MANIFEST.get("contract_version").asText()).isEqualTo(BrowserRunContract.CONTRACT_VERSION);
        assertThat(MANIFEST.get("frame_contract_version").asText())
                .isEqualTo(BrowserRunContract.FRAME_CONTRACT_VERSION);
        assertThat(MANIFEST.get("fingerprint_algorithm").asText())
                .isEqualTo(BrowserRunContract.FINGERPRINT_ALGORITHM);
        assertThat(limits.get("text_max_code_points").asInt()).isEqualTo(BrowserRunContract.TEXT_MAX_CODE_POINTS);
        assertThat(limits.get("latex_max_code_points").asInt()).isEqualTo(BrowserRunContract.LATEX_MAX_CODE_POINTS);
        assertThat(limits.get("turn_attachment_ids_wire_max").asInt())
                .isEqualTo(BrowserRunContract.TURN_ATTACHMENT_IDS_WIRE_MAX);
        assertThat(limits.get("heartbeat_seconds").asInt()).isEqualTo(BrowserRunContract.HEARTBEAT_SECONDS);
        assertThat(limits.get("max_seq").asLong()).isEqualTo(BrowserRunContract.MAX_SEQ);
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
