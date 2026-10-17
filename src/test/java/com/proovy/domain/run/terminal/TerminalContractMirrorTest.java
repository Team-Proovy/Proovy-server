package com.proovy.domain.run.terminal;

import static com.proovy.domain.run.terminal.TerminalFixtures.MIRROR;
import static com.proovy.domain.run.terminal.TerminalFixtures.read;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D9: terminal-v2·response-v1·publication-v1 사본은 pin된 Brain commit의 검증된 mirror여야 한다(파일 해시는 PIN이 가진다). */
class TerminalContractMirrorTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "terminal-v2, terminal-fixtures-v2",
            "response-v1, response-fixtures-v1",
            "publication-v1, publication-fixtures-v1.2"})
    void mirrorMatchesPin(String artifact, String consumerVersion) throws Exception {
        JsonNode pin = read(MIRROR.resolve("PIN-" + artifact + ".json"));
        assertThat(pin.get("repository").asText()).isEqualTo("Team-Proovy/proovy-brain");
        assertThat(pin.get("commit").asText()).isEqualTo("303a9e45943207cb22fc555452cc781fb8117d21");
        assertThat(pin.get("consumer_version").asText()).isEqualTo(consumerVersion);
        Set<String> listed = new HashSet<>();
        for (var entry : pin.get("files").properties()) {
            assertThat(sha256(MIRROR.resolve(entry.getKey()))).as(entry.getKey()).isEqualTo(entry.getValue().asText());
            listed.add(entry.getKey());
        }
        if (pin.hasNonNull("manifest")) {
            String manifest = pin.get("manifest").asText();
            assertThat(sha256(MIRROR.resolve(manifest))).isEqualTo(pin.get("manifest_sha256").asText());
            assertThat(read(MIRROR.resolve(manifest)).get("consumer_version").asText()).isEqualTo(consumerVersion);
            listed.add(manifest);
        } else {
            String catalog = Files.readString(MIRROR.resolve(pin.get("catalog").asText()));
            assertThat(catalog).contains("버전: `" + consumerVersion + "`");
        }
        try (Stream<Path> files = Files.walk(MIRROR.resolve(artifact))) {
            Set<String> onDisk = new HashSet<>();
            files.filter(Files::isRegularFile)
                    .forEach(path -> onDisk.add(MIRROR.relativize(path).toString().replace('\\', '/')));
            assertThat(onDisk).isEqualTo(listed);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"pairs", "negative_pairs"})
    void manifestEntriesExist(String section) {
        JsonNode manifest = TerminalFixtures.load("terminal-v2/manifest.json");
        String directory = section.equals("pairs") ? "pairs" : "invalid";
        assertThat(manifest.get(section)).isNotEmpty();
        for (JsonNode name : manifest.get(section)) {
            assertThat(MIRROR.resolve("terminal-v2/" + directory + "/" + name.asText() + ".json")).exists();
        }
        assertThat(TerminalFixtures.files("terminal-v2/" + directory, ".json")).hasSize(manifest.get(section).size());
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
