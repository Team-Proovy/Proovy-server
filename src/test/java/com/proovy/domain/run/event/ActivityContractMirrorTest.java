package com.proovy.domain.run.event;

import static com.proovy.domain.run.event.ActivityFixtures.FIXTURES;
import static com.proovy.domain.run.event.ActivityFixtures.MIRROR;
import static com.proovy.domain.run.event.ActivityFixtures.read;
import static com.proovy.domain.run.event.ActivityFixtures.text;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D9: activity-v1 사본은 pin된 Brain commit의 검증된 mirror여야 한다(manifest가 없어 PIN이 파일 해시를 가진다). */
class ActivityContractMirrorTest {

    static final String PIN_FILE = "PIN-activity-v1.json";
    private static final JsonNode PIN = read(MIRROR.resolve(PIN_FILE));

    @Test
    @DisplayName("pin의 repository·commit·consumer_version이 CATALOG·Java 상수와 같다")
    void pinMatchesCatalog() {
        assertThat(PIN.get("repository").asText()).isEqualTo("Team-Proovy/proovy-brain");
        assertThat(PIN.get("commit").asText()).matches("[0-9a-f]{40}");
        assertThat(PIN.get("source_path").asText()).isEqualTo("tests/fixtures/contracts");
        assertThat(PIN.get("consumer_version").asText()).isEqualTo(SourceActivities.CONSUMER_VERSION);
        assertThat(text(FIXTURES.resolve("CATALOG.md"))).contains("버전: `" + SourceActivities.CONSUMER_VERSION + "`");
    }

    @Test
    @DisplayName("pin에 적힌 모든 파일의 sha256이 사본과 같고 목록 밖 파일이 없다")
    void everyFileMatchesPinnedDigest() throws Exception {
        Set<String> listed = new HashSet<>();
        for (var entry : PIN.get("files").properties()) {
            assertThat(sha256(MIRROR.resolve(entry.getKey()))).as(entry.getKey()).isEqualTo(entry.getValue().asText());
            listed.add(entry.getKey());
        }
        try (Stream<Path> files = Files.walk(FIXTURES)) {
            Set<String> onDisk = new HashSet<>();
            files.filter(Files::isRegularFile)
                    .forEach(path -> onDisk.add(MIRROR.relativize(path).toString().replace('\\', '/')));
            assertThat(onDisk).isEqualTo(listed);
        }
    }

    @Test
    @DisplayName("CATALOG 표의 fixture와 사본 파일이 양방향으로 같다")
    void catalogMatchesFixtures() throws IOException {
        String catalog = text(FIXTURES.resolve("CATALOG.md"));
        Set<String> named = new HashSet<>();
        Matcher matcher = Pattern.compile("[a-z0-9-]+(?:\\.browser)?\\.json").matcher(catalog);
        while (matcher.find()) {
            named.add(matcher.group());
        }
        Set<String> onDisk = new HashSet<>();
        for (String directory : new String[]{"valid", "invalid", "browser"}) {
            try (Stream<Path> files = Files.list(FIXTURES.resolve(directory))) {
                files.forEach(path -> onDisk.add(path.getFileName().toString()));
            }
        }
        assertThat(onDisk).isEqualTo(named);
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
