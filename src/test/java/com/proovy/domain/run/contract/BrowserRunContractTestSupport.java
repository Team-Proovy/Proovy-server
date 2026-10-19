package com.proovy.domain.run.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * pin된 proovy-brain 계약 사본(src/test/resources/contracts/brain)을 읽는 테스트 도우미.
 * 사본 자체의 무결성은 {@link BrainContractMirrorTest}가 검사한다.
 */
public final class BrowserRunContractTestSupport {

    /** Java 쪽이 맞춰 둔 consumer_version. 사본을 새 version으로 갱신하면 함께 올린다. */
    static final String EXPECTED_CONSUMER_VERSION = "browser-run-fixtures-v1";
    static final String SCHEMA_BASE = "https://proovy.invalid/contracts/";
    static final String SCHEMA_ID = SCHEMA_BASE + "browser-run-v1/schemas/browser-run.schema.json";

    /** Spring Boot 기본 ObjectMapper와 같은 설정(ISO 시각, 알 수 없는 필드 무시). */
    static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();
    static final Path MIRROR = mirrorRoot();
    static final Path FIXTURES = MIRROR.resolve("browser-run-v1");

    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(SCHEMA_BASE, "classpath:contracts/brain/")));
    private static final Map<String, JsonSchema> DEFINITIONS = new ConcurrentHashMap<>();

    private BrowserRunContractTestSupport() {
    }

    private static Path mirrorRoot() {
        try {
            return Path.of(BrowserRunContractTestSupport.class.getClassLoader()
                    .getResource("contracts/brain").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode load(String relative) {
        return read(FIXTURES.resolve(relative));
    }

    static JsonNode read(Path path) {
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(String relative) {
        try {
            return Files.readString(FIXTURES.resolve(relative));
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

    /** browser-run-v1 schema의 {@code $defs/<name>}으로 검증한 오류 목록. */
    public static Set<ValidationMessage> schemaErrors(String definition, JsonNode value) {
        JsonSchema schema = DEFINITIONS.computeIfAbsent(definition, name -> SCHEMAS.getSchema(
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                        + "\"$ref\":\"" + SCHEMA_ID + "#/$defs/" + name + "\"}"));
        return schema.validate(value);
    }

    public static boolean schemaOk(String definition, JsonNode value) {
        return schemaErrors(definition, value).isEmpty();
    }

    /** DTO를 실제 wire 텍스트로 직렬화한 뒤 다시 읽는다(숫자 노드 타입 차이 없이 fixture와 비교). */
    static JsonNode toTree(Object value) {
        try {
            return MAPPER.readTree(MAPPER.writeValueAsString(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
