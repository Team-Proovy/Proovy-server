package com.proovy.domain.run.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * pin된 activity-v1 사본(src/test/resources/contracts/brain/activity-v1)을 읽는 테스트 도우미.
 * 사본 무결성은 {@link ActivityContractMirrorTest}가 검사한다.
 */
final class ActivityFixtures {

    static final ObjectMapper RAW = new ObjectMapper();
    static final Path MIRROR = mirrorRoot();
    static final Path FIXTURES = MIRROR.resolve("activity-v1");

    private ActivityFixtures() {
    }

    private static Path mirrorRoot() {
        try {
            return Path.of(ActivityFixtures.class.getClassLoader().getResource("contracts/brain").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode read(Path path) {
        try {
            return RAW.readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 디렉터리의 *.json fixture 원문을 이름순으로. */
    static List<Map.Entry<String, String>> raw(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .map(path -> Map.entry(path.getFileName().toString().replace(".json", ""), text(path)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
