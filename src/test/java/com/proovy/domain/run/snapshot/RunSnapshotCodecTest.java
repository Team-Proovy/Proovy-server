package com.proovy.domain.run.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.brain.ExecutionUsage;
import com.proovy.domain.run.brain.InternalRunFixtures;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.InternalRunWire;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * 저장 문서 ↔ 전달 요청. pin된 internal-run-v1 fingerprint fixture(27개) 전부에서, 저장 문서에 control·자격증명이 없고
 * 다른 attempt·fence·grant·URL·usage로 복원해도 Brain이 기대하는 fingerprint가 그대로인지 본다.
 */
class RunSnapshotCodecTest {

    private static final ExecutionUsage LATER_USAGE = new ExecutionUsage(3, 2, 900, 1200, 1);
    private static final String NEW_GRANT = "grant-rotated-secret";
    private static final String NEW_VIEWER_URL = "https://storage.example/viewer?sig=rotated-secret";
    private static final String NEW_ATTACHMENT_URL = "https://storage.example/att?sig=rotated-secret";

    @TestFactory
    Stream<DynamicTest> everyFingerprintFixtureSurvivesStripAndRestoreWithNewControlValues() {
        return fingerprintFixtures().map(fixture -> DynamicTest.dynamicTest(fixture.getKey(), () -> {
            JsonNode node = fixture.getValue();
            InternalRunRequest original = InternalRunWire.read(node.get("request"), InternalRunRequest.class);
            ObjectNode stored = RunSnapshotCodec.strip(original);

            assertNoControlOrCredential(stored, original);

            InternalRunRequest restored = RunSnapshotCodec.restore(stored, original.attempt() + 5, "fence-later",
                    LATER_USAGE, access(original));
            assertThat(restored.fingerprint().sha256()).isEqualTo(node.get("expected_fingerprint").asText());
            assertThat(restored.fingerprint().canonical()).isEqualTo(node.get("expected_canonical").asText());
            assertThat(restored.executionOptions().requestDeadlineAt())
                    .isEqualTo(original.executionOptions().requestDeadlineAt());
            assertThat(restored.executionOptions().usage()).isEqualTo(LATER_USAGE);
            assertThat(restored.attempt()).isEqualTo(original.attempt() + 5);
            assertThat(restored.fenceToken()).isEqualTo("fence-later");
            assertThat(restored.assetAccessGrant()).isEqualTo(NEW_GRANT);
        }));
    }

    @Test
    void restoringWithTheOriginalControlValuesReproducesTheOriginalRequest() {
        InternalRunRequest original = InternalRunFixtures.fullRequest();
        ObjectNode stored = RunSnapshotCodec.strip(original);

        InternalRunRequest restored = RunSnapshotCodec.restore(stored, original.attempt(), original.fenceToken(),
                original.executionOptions().usage(), new RunAssetAccess(original.assetAccessGrant(),
                        original.viewerContext().readUrl(),
                        Map.of("att-201", original.turnAttachments().get(0).readUrl())));

        assertThat(restored).isEqualTo(original);
        assertThat(InternalRunWire.tree(restored)).isEqualTo(InternalRunWire.tree(original));
    }

    @Test
    void restoreNeverReadsAStoredUsageOrDeadlineFromTheCaller() {
        InternalRunRequest original = InternalRunFixtures.fullRequest();
        ObjectNode stored = RunSnapshotCodec.strip(original);

        assertThat(stored.at("/execution_options/request_deadline_at").asText()).isEqualTo("2026-10-02T09:30:00Z");
        assertThat(stored.at("/execution_options").has("usage")).isFalse();
        InternalRunRequest restored = RunSnapshotCodec.restore(stored, 2, "fence-2", ExecutionUsage.ZERO,
                access(original));
        assertThat(restored.executionOptions().requestDeadlineAt().toInstant())
                .isEqualTo(Instant.parse("2026-10-02T09:30:00Z"));
    }

    @Test
    void aCurrentTurnAttachmentWithoutAFreshReadUrlCannotBeDelivered() {
        InternalRunRequest original = InternalRunFixtures.fullRequest();
        ObjectNode stored = RunSnapshotCodec.strip(original);

        assertThatThrownBy(() -> RunSnapshotCodec.restore(stored, 2, "fence-2", ExecutionUsage.ZERO,
                RunAssetAccess.NONE))
                .isInstanceOf(RunSnapshotIntegrityException.class)
                .hasMessageNotContaining("att-201");
    }

    @Test
    void storedDocumentsThatCarryControlOrCredentialFieldsAreRejected() {
        InternalRunRequest original = InternalRunFixtures.fullRequest();
        for (String pointer : List.of("", "/viewer_context", "/turn_attachments/0", "/execution_options")) {
            ObjectNode stored = RunSnapshotCodec.strip(original);
            ObjectNode target = (ObjectNode) (pointer.isEmpty() ? stored : stored.at(pointer));
            String field = switch (pointer) {
                case "" -> "asset_access_grant";
                case "/execution_options" -> "usage";
                default -> "read_url";
            };
            target.put(field, "leaked");
            assertThatThrownBy(() -> RunSnapshotCodec.restore(stored, 2, "fence-2", ExecutionUsage.ZERO,
                    access(original)))
                    .as(pointer + "/" + field)
                    .isInstanceOf(RunSnapshotIntegrityException.class);
        }
    }

    @Test
    void aTamperedSnapshotNoLongerMatchesTheStoredFingerprint() {
        InternalRunRequest original = InternalRunFixtures.fullRequest();
        ObjectNode stored = RunSnapshotCodec.strip(original);
        String fingerprint = original.fingerprint().sha256();
        Instant deadline = original.executionOptions().requestDeadlineAt().toInstant();

        ObjectNode changedText = stored.deepCopy();
        ((ObjectNode) changedText.get("input")).put("text", "다른 질문");
        assertThatThrownBy(() -> RunSnapshotRestorer.restore(changedText, fingerprint, deadline, 2, "fence-2",
                ExecutionUsage.ZERO, access(original)))
                .isInstanceOf(RunSnapshotIntegrityException.class);

        ObjectNode extendedDeadline = stored.deepCopy();
        ((ObjectNode) extendedDeadline.get("execution_options")).put("request_deadline_at", "2026-10-02T10:30:00Z");
        assertThatThrownBy(() -> RunSnapshotRestorer.restore(extendedDeadline, fingerprint, deadline, 2, "fence-2",
                ExecutionUsage.ZERO, access(original)))
                .isInstanceOf(RunSnapshotIntegrityException.class)
                .hasMessageContaining("deadline");

        ObjectNode unknownField = stored.deepCopy();
        unknownField.put("auth_token", "jwt");
        assertThatThrownBy(() -> RunSnapshotRestorer.restore(unknownField, fingerprint, deadline, 2, "fence-2",
                ExecutionUsage.ZERO, access(original)))
                .isInstanceOf(RunSnapshotIntegrityException.class)
                .hasMessageNotContaining("jwt");
    }

    static RunAssetAccess access(InternalRunRequest request) {
        return new RunAssetAccess(NEW_GRANT, request.viewerContext() == null ? null : NEW_VIEWER_URL,
                request.turnAttachments().stream()
                        .collect(java.util.stream.Collectors.toMap(item -> item.attachmentId(),
                                item -> NEW_ATTACHMENT_URL)));
    }

    private static void assertNoControlOrCredential(ObjectNode stored, InternalRunRequest original) {
        String text = stored.toString();
        assertThat(text).doesNotContain("read_url", "asset_access_grant", "fence_token", "\"attempt\"", "usage");
        if (original.assetAccessGrant() != null) {
            assertThat(text).doesNotContain(original.assetAccessGrant());
        }
        if (original.viewerContext() != null && original.viewerContext().readUrl() != null) {
            assertThat(text).doesNotContain(original.viewerContext().readUrl());
        }
        original.turnAttachments().forEach(item -> assertThat(text).doesNotContain(item.readUrl()));
        assertThat(text).doesNotContain(original.fenceToken());
    }

    private static Stream<Map.Entry<String, JsonNode>> fingerprintFixtures() {
        Path directory = InternalRunFixtures.FIXTURES.resolve("fingerprint");
        try (Stream<Path> files = Files.list(directory)) {
            List<Map.Entry<String, JsonNode>> entries = files.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .map(path -> Map.entry(path.getFileName().toString(),
                            InternalRunFixtures.load("fingerprint/" + path.getFileName())))
                    .toList();
            assertThat(entries).hasSize(27);
            return entries.stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
