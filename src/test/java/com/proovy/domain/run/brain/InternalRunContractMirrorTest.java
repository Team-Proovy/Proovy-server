package com.proovy.domain.run.brain;

import static com.proovy.domain.run.brain.InternalRunFixtures.FIXTURES;
import static com.proovy.domain.run.brain.InternalRunFixtures.MIRROR;
import static com.proovy.domain.run.brain.InternalRunFixtures.read;
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

/** D9: internal-run-v1 사본은 pin된 Brain commit의 검증된 mirror여야 한다(manifest에 파일 해시가 없어 PIN이 가진다). */
class InternalRunContractMirrorTest {

    static final String PIN_FILE = "PIN-internal-run-v1.json";
    private static final JsonNode PIN = read(MIRROR.resolve(PIN_FILE));
    private static final JsonNode MANIFEST = read(FIXTURES.resolve("manifest.json"));

    @Test
    @DisplayName("pin의 manifest sha256·consumer_version·contract_version이 사본·Java 상수와 같다")
    void pinMatchesManifest() throws Exception {
        assertThat(PIN.get("repository").asText()).isEqualTo("Team-Proovy/proovy-brain");
        assertThat(PIN.get("commit").asText()).matches("[0-9a-f]{40}");
        assertThat(sha256(MIRROR.resolve(PIN.get("manifest").asText())))
                .isEqualTo(PIN.get("manifest_sha256").asText());
        assertThat(MANIFEST.get("consumer_version").asText())
                .isEqualTo(PIN.get("consumer_version").asText())
                .isEqualTo(InternalRunContract.CONSUMER_VERSION);
        assertThat(MANIFEST.get("contract_version").asText()).isEqualTo(InternalRunContract.CONTRACT_VERSION);
        assertThat(MANIFEST.get("fingerprint_algorithm").asText())
                .isEqualTo(InternalRunContract.FINGERPRINT_ALGORITHM);
        assertThat(MANIFEST.get("original_path").asText())
                .isEqualTo(PIN.get("source_path").asText() + "/internal-run-v1");
    }

    @Test
    @DisplayName("pin에 적힌 모든 파일의 sha256이 사본과 같고 목록 밖 파일이 없다")
    void everyFileMatchesPinnedDigest() throws Exception {
        Set<String> listed = pinnedPaths();
        for (var entry : PIN.get("files").properties()) {
            assertThat(sha256(MIRROR.resolve(entry.getKey()))).as(entry.getKey()).isEqualTo(entry.getValue().asText());
        }
        try (Stream<Path> files = Files.walk(FIXTURES)) {
            Set<String> onDisk = new HashSet<>();
            files.filter(Files::isRegularFile)
                    .forEach(path -> onDisk.add(MIRROR.relativize(path).toString().replace('\\', '/')));
            listed.remove(PIN_FILE);
            assertThat(onDisk).isEqualTo(listed);
        }
    }

    @Test
    @DisplayName("manifest가 나열한 fixture가 모두 사본에 있다")
    void manifestEntriesExist() {
        for (String section : new String[]{"requests", "operations", "invalid", "fingerprint"}) {
            for (JsonNode name : MANIFEST.get(section)) {
                assertThat(FIXTURES.resolve(section + "/" + name.asText() + ".json")).as(section).exists();
            }
        }
        assertThat(FIXTURES.resolve(MANIFEST.get("canonical_rules").asText())).exists();
        assertThat(FIXTURES.resolve(MANIFEST.get("decisions").asText())).exists();
    }

    /** 이 pin이 소유하는 사본 경로(PIN 파일 자신 포함). */
    private static Set<String> pinnedPaths() {
        Set<String> paths = new HashSet<>();
        PIN.get("files").properties().forEach(entry -> paths.add(entry.getKey()));
        paths.add(PIN.get("manifest").asText());
        paths.add(PIN_FILE);
        return paths;
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
