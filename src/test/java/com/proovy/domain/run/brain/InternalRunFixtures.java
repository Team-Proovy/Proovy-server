package com.proovy.domain.run.brain;

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
 * pin된 internal-run-v1 사본(src/test/resources/contracts/brain/internal-run-v1)을 읽는 테스트 도우미.
 * 사본 무결성은 {@link InternalRunContractMirrorTest}가 검사한다.
 */
public final class InternalRunFixtures {

    /** fixture 원문을 손대지 않고 tree로 읽는다(숫자 형태 유지). */
    static final ObjectMapper RAW = new ObjectMapper();
    public static final Path MIRROR = mirrorRoot();
    public static final Path FIXTURES = MIRROR.resolve("internal-run-v1");

    private InternalRunFixtures() {
    }

    private static Path mirrorRoot() {
        try {
            return Path.of(InternalRunFixtures.class.getClassLoader().getResource("contracts/brain").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode load(String relative) {
        return read(FIXTURES.resolve(relative));
    }

    static JsonNode read(Path path) {
        try {
            return RAW.readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 디렉터리의 *.json fixture를 이름순으로. */
    static List<Map.Entry<String, JsonNode>> entries(String directory) {
        try (Stream<Path> files = Files.list(FIXTURES.resolve(directory))) {
            return files.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .map(path -> Map.entry(path.getFileName().toString().replace(".json", ""), read(path)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** fixture 요청을 Java DTO로 읽는다. */
    public static InternalRunRequest request(String relative) {
        return InternalRunWire.read(load(relative), InternalRunRequest.class);
    }

    public static InternalRunRequest fullRequest() {
        return request("requests/full.json");
    }
}
