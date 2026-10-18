package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.asset.entity.AssetStatus;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.brain.StoredTerminal;
import com.proovy.domain.run.brain.SummaryCoverage;
import com.proovy.domain.solution.entity.VerifiedSolutionStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * pin된 terminal-v2·response-v1·publication-v1 사본을 읽고 seeded scope를 만드는 테스트 도우미.
 * 사본 무결성은 {@link TerminalContractMirrorTest}가 검사한다.
 */
final class TerminalFixtures {

    static final ObjectMapper RAW = new ObjectMapper();
    static final Path MIRROR = mirrorRoot();
    static final long USER_ID = 7L;
    static final long NOTE_ID = 3L;

    private TerminalFixtures() {
    }

    private static Path mirrorRoot() {
        try {
            return Path.of(TerminalFixtures.class.getClassLoader().getResource("contracts/brain").toURI());
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

    static JsonNode load(String relative) {
        return read(MIRROR.resolve(relative));
    }

    static String text(String relative) {
        try {
            return Files.readString(MIRROR.resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 디렉터리의 fixture를 이름순으로(이름 → 경로). */
    static Map<String, Path> files(String directory, String suffix) {
        try (Stream<Path> files = Files.list(MIRROR.resolve(directory))) {
            Map<String, Path> out = new LinkedHashMap<>();
            files.filter(path -> path.toString().endsWith(suffix)).sorted()
                    .forEach(path -> out.put(path.getFileName().toString().replace(suffix, ""), path));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode pair(String name) {
        return load("terminal-v2/pairs/" + name + ".json");
    }

    static StoredTerminal stored(JsonNode source) {
        return InternalRunWire.read(source, StoredTerminal.class);
    }

    static SourceEvent event(JsonNode source) {
        return InternalRunWire.read(source, SourceEvent.class);
    }

    /** source를 바꿔 볼 수 있는 깊은 사본. */
    static ObjectNode copy(JsonNode source) {
        return (ObjectNode) source.deepCopy();
    }

    /**
     * seeded scope: envelope의 run·attempt·fence, 고정 user/Note, provenance에 나온 asset마다 같은 version으로
     * 승인한 ACTIVE dependency(page_count는 인용 page보다 넉넉히). source 없는 결과면 asset이 없다.
     */
    static TerminalAcceptanceScope seededScope(JsonNode source) {
        return new TerminalAcceptanceScope(source.get("run_id").asText(), source.get("attempt").asInt(),
                source.get("fence_token").asText(), USER_ID, NOTE_ID, seededAssets(source.get("payload")),
                seededSolutions(source.get("payload")), Map.of());
    }

    /** payload의 existing reference마다 같은 version·coverage로 저장된 ACTIVE solution(run 요청 참조). */
    static List<ScopedSolution> seededSolutions(JsonNode payload) {
        List<ScopedSolution> solutions = new ArrayList<>();
        for (JsonNode reference : payload.path("existing_solution_references")) {
            Map<String, SummaryCoverage> coverage = new LinkedHashMap<>();
            for (JsonNode claim : reference.path("verification").path("claims")) {
                coverage.put(claim.get("claim_id").asText(), claim.get("coverage").asText().equals("direct")
                        ? SummaryCoverage.DIRECT : SummaryCoverage.SUPPORTING);
            }
            String publicId = reference.get("solution_id").asText();
            solutions.add(new ScopedSolution(publicId, solutionUuid(publicId), NOTE_ID, VerifiedSolutionStatus.ACTIVE,
                    null, reference.get("version").asInt(), coverage));
        }
        return solutions;
    }

    static UUID solutionUuid(String publicId) {
        return UUID.nameUUIDFromBytes(publicId.getBytes(StandardCharsets.UTF_8));
    }

    static List<ScopedAsset> seededAssets(JsonNode payload) {
        Map<String, ScopedAsset> assets = new LinkedHashMap<>();
        long nextId = 100;
        for (JsonNode item : payload.path("selected_page_provenance")) {
            String brainId = item.get("asset_id").asText();
            if (!assets.containsKey(brainId)) {
                assets.put(brainId, new ScopedAsset(brainId, nextId++, USER_ID, NOTE_ID, AssetStatus.ACTIVE, 1, 1,
                        item.get("content_version").asText(), item.get("sha256").asText(), 20,
                        item.get("understanding_version").asText()));
            }
        }
        return new ArrayList<>(assets.values());
    }
}
