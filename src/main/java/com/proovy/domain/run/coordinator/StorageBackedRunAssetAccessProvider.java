package com.proovy.domain.run.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.snapshot.RunAssetAccess;
import com.proovy.global.infra.gcs.GcsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 전달 시점마다 DB 승인 상태(run 의존성·노트 소유·asset ACTIVE)를 확인한 뒤에만
 * 단기 V4 GET Signed URL을 발급하는 {@link RunAssetAccessProvider}.
 * <p>
 * URL은 DB에 저장하거나 로그로 남기지 않으며, viewer_context/turn_attachments가 없는
 * 텍스트 전용 run에서는 DB 조회와 서명 없이 {@link RunAssetAccess#NONE}을 반환한다.
 */
public class StorageBackedRunAssetAccessProvider implements RunAssetAccessProvider {

    private static final String APPROVED_VIEWER_OBJECT_KEY_SQL = """
            SELECT a.object_key
            FROM run_asset_dependencies d
            JOIN assets a ON a.id = d.asset_id AND a.note_id = d.note_id
            JOIN notes n ON n.note_id = d.note_id
            WHERE d.run_id = ?
              AND d.note_id = ?
              AND d.asset_id = ?
              AND d.dependency_kind = 'VIEWER_FOCUS'
              AND a.user_id = ?
              AND a.status = 'ACTIVE'
              AND n.user_id = ?
              AND n.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final GcsService gcsService;
    private final int defaultTtlMinutes;
    private final Clock clock;

    public StorageBackedRunAssetAccessProvider(JdbcTemplate jdbc, GcsService gcsService, int defaultTtlMinutes) {
        this(jdbc, gcsService, defaultTtlMinutes, Clock.systemUTC());
    }

    StorageBackedRunAssetAccessProvider(JdbcTemplate jdbc, GcsService gcsService, int defaultTtlMinutes, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.gcsService = Objects.requireNonNull(gcsService, "gcsService");
        if (defaultTtlMinutes <= 0) {
            throw new IllegalArgumentException("defaultTtlMinutes must be positive");
        }
        this.defaultTtlMinutes = defaultTtlMinutes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RunAssetAccess accessFor(AiRun run, ClaimedRun claim) {
        Objects.requireNonNull(run, "run");
        JsonNode snapshot = run.getRequestSnapshot();
        if (snapshot == null || snapshot.isNull()) {
            return RunAssetAccess.NONE;
        }

        Long viewerAssetId = extractViewerAssetId(snapshot);
        List<UUID> attachmentIds = extractAttachmentIds(snapshot);
        if (viewerAssetId == null && attachmentIds.isEmpty()) {
            return RunAssetAccess.NONE;
        }

        int ttlMinutes = computeTtlMinutes(run.getRequestDeadlineAt());
        String viewerReadUrl = viewerAssetId != null ? resolveViewerReadUrl(run, viewerAssetId, ttlMinutes) : null;
        Map<String, String> attachmentUrls = attachmentIds.isEmpty()
                ? Map.of()
                : resolveAttachmentReadUrls(run, attachmentIds, ttlMinutes);

        if (viewerReadUrl == null && attachmentUrls.isEmpty()) {
            return RunAssetAccess.NONE;
        }
        return new RunAssetAccess(null, viewerReadUrl, attachmentUrls);
    }

    private String resolveViewerReadUrl(AiRun run, long viewerAssetId, int ttlMinutes) {
        List<String> keys = jdbc.query(
                APPROVED_VIEWER_OBJECT_KEY_SQL,
                (rs, rowNum) -> rs.getString("object_key"),
                run.getId(),
                run.getNoteId(),
                viewerAssetId,
                run.getUserId(),
                run.getUserId()
        );
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        String objectKey = keys.getFirst();
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return gcsService.generatePresignedReadUrl(objectKey, ttlMinutes);
    }

    private Map<String, String> resolveAttachmentReadUrls(AiRun run, List<UUID> attachmentIds, int ttlMinutes) {
        String placeholders = String.join(", ", Collections.nCopies(attachmentIds.size(), "?"));
        String sql = """
                SELECT ta.attachment_id, ta.object_key
                FROM turn_attachments ta
                JOIN notes n ON n.note_id = ta.note_id
                WHERE ta.note_id = ?
                  AND n.user_id = ?
                  AND n.status = 'ACTIVE'
                  AND ta.attachment_id IN (%s)
                """.formatted(placeholders);

        List<Object> params = new ArrayList<>(3 + attachmentIds.size());
        params.add(run.getNoteId());
        params.add(run.getUserId());
        params.addAll(attachmentIds);

        List<AttachmentObject> rows = jdbc.query(
                sql,
                (rs, rowNum) -> new AttachmentObject(
                        rs.getObject("attachment_id", UUID.class),
                        rs.getString("object_key")),
                params.toArray()
        );
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Map<String, String> urls = new LinkedHashMap<>();
        for (AttachmentObject row : rows) {
            if (row.attachmentId() != null && row.objectKey() != null && !row.objectKey().isBlank()) {
                urls.put(
                        row.attachmentId().toString(),
                        gcsService.generatePresignedReadUrl(row.objectKey(), ttlMinutes));
            }
        }
        return urls;
    }

    private int computeTtlMinutes(Instant deadline) {
        if (deadline == null) {
            return defaultTtlMinutes;
        }
        long remainingSeconds = Math.max(0L, Duration.between(clock.instant(), deadline).getSeconds());
        long minutes = Math.max(1L, (remainingSeconds + 59L) / 60L);
        return (int) Math.min(defaultTtlMinutes, minutes);
    }

    private static Long extractViewerAssetId(JsonNode snapshot) {
        JsonNode viewerNode = snapshot.get("viewer_context");
        if (!(viewerNode instanceof ObjectNode viewer)) {
            return null;
        }
        String raw = viewer.path("asset_id").asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<UUID> extractAttachmentIds(JsonNode snapshot) {
        JsonNode attachmentsNode = snapshot.get("turn_attachments");
        if (!(attachmentsNode instanceof ArrayNode attachments) || attachments.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (JsonNode entry : attachments) {
            String raw = entry.path("attachment_id").asText(null);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ignored) {
                // 잘못된 UUID 문자열은 승인 목록에서 제외한다 (RunSnapshotCodec에서 fail-closed).
            }
        }
        return List.copyOf(ids);
    }

    private record AttachmentObject(UUID attachmentId, String objectKey) {
    }
}
