package com.proovy.domain.run.coordinator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proovy.domain.run.entity.AiRun;
import com.proovy.domain.run.snapshot.RunAssetAccess;
import com.proovy.global.infra.gcs.GcsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class StorageBackedRunAssetAccessProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    private JdbcTemplate jdbc;
    private GcsService gcsService;
    private StorageBackedRunAssetAccessProvider provider;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        gcsService = mock(GcsService.class);
        provider = new StorageBackedRunAssetAccessProvider(
                jdbc, gcsService, 15, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void textOnlyRunReturnsNoneWithoutDbOrSigningCalls() {
        AiRun run = mockRun(UUID.randomUUID(), 10L, 20L, MAPPER.createObjectNode().put("text", "2+2"),
                NOW.plusSeconds(180));

        RunAssetAccess access = provider.accessFor(run, null);

        assertThat(access).isEqualTo(RunAssetAccess.NONE);
        verifyNoInteractions(jdbc, gcsService);
    }

    @Test
    void approvedViewerFocusAndTurnAttachmentsReceiveFreshSignedReadUrls() {
        UUID runId = UUID.randomUUID();
        UUID approvedAttachmentId = UUID.randomUUID();
        UUID unapprovedAttachmentId = UUID.randomUUID();

        ObjectNode snapshot = MAPPER.createObjectNode();
        String viewerSha = "a".repeat(64);
        snapshot.putObject("viewer_context")
                .put("asset_id", "301")
                .put("sha256", viewerSha)
                .put("byte_size", 1024L);
        ArrayNode attachments = snapshot.putArray("turn_attachments");
        attachments.addObject().put("attachment_id", approvedAttachmentId.toString());
        attachments.addObject().put("attachment_id", unapprovedAttachmentId.toString());

        AiRun run = mockRun(runId, 10L, 20L, snapshot, NOW.plusSeconds(180));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(runId), eq(20L), eq(301L), eq(10L), eq(10L)))
                .thenAnswer(inv -> mapViewerAssetRow(
                        inv.getArgument(1), "users/10/notes/20/assets/viewer.pdf", viewerSha, 1024L));
        when(jdbc.query(anyString(), any(RowMapper.class),
                eq(20L), eq(10L), eq(approvedAttachmentId), eq(unapprovedAttachmentId)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<?> mapper = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("attachment_id", UUID.class)).thenReturn(approvedAttachmentId);
                    when(rs.getString("object_key")).thenReturn("users/10/notes/20/attachments/att.jpg");
                    return List.of(mapper.mapRow(rs, 0));
                });

        when(gcsService.generatePresignedReadUrl("users/10/notes/20/assets/viewer.pdf", 3))
                .thenReturn("https://mac.ts.net/bucket/users/10/notes/20/assets/viewer.pdf?sig=v");
        when(gcsService.generatePresignedReadUrl("users/10/notes/20/attachments/att.jpg", 3))
                .thenReturn("https://mac.ts.net/bucket/users/10/notes/20/attachments/att.jpg?sig=a");

        RunAssetAccess access = provider.accessFor(run, null);

        assertThat(access.viewerReadUrl())
                .isEqualTo("https://mac.ts.net/bucket/users/10/notes/20/assets/viewer.pdf?sig=v");
        assertThat(access.turnAttachmentReadUrls())
                .containsOnlyKeys(approvedAttachmentId.toString())
                .doesNotContainKey(unapprovedAttachmentId.toString())
                .containsEntry(approvedAttachmentId.toString(),
                        "https://mac.ts.net/bucket/users/10/notes/20/attachments/att.jpg?sig=a");
        verify(gcsService).generatePresignedReadUrl("users/10/notes/20/assets/viewer.pdf", 3);
        verify(gcsService).generatePresignedReadUrl("users/10/notes/20/attachments/att.jpg", 3);
    }

    @Test
    void unapprovedOrMalformedAssetsDoNotReceiveReadUrls() {
        UUID runId = UUID.randomUUID();
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.putObject("viewer_context").put("asset_id", "999");
        snapshot.putArray("turn_attachments").addObject().put("attachment_id", "not-a-uuid");

        AiRun run = mockRun(runId, 10L, 20L, snapshot, NOW.plusSeconds(3600));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(runId), eq(20L), eq(999L), eq(10L), eq(10L)))
                .thenReturn(List.of());

        RunAssetAccess access = provider.accessFor(run, null);

        assertThat(access).isEqualTo(RunAssetAccess.NONE);
        verifyNoInteractions(gcsService);
    }

    @Test
    void ttlClampsToDefaultMaxMinutes() {
        UUID runId = UUID.randomUUID();
        ObjectNode snapshot = MAPPER.createObjectNode();
        String viewerSha = "b".repeat(64);
        snapshot.putObject("viewer_context")
                .put("asset_id", "55")
                .put("sha256", viewerSha)
                .put("byte_size", 2048L);
        AiRun run = mockRun(runId, 1L, 2L, snapshot, NOW.plusSeconds(7200));

        when(jdbc.query(anyString(), any(RowMapper.class), eq(runId), eq(2L), eq(55L), eq(1L), eq(1L)))
                .thenAnswer(inv -> mapViewerAssetRow(
                        inv.getArgument(1), "users/1/notes/2/assets/x.pdf", viewerSha, 2048L));
        when(gcsService.generatePresignedReadUrl(anyString(), anyInt()))
                .thenReturn("https://mac.ts.net/bucket/users/1/notes/2/assets/x.pdf?sig=x");

        provider.accessFor(run, null);

        verify(gcsService).generatePresignedReadUrl("users/1/notes/2/assets/x.pdf", 15);
    }

    private static List<Object> mapViewerAssetRow(
            RowMapper<?> mapper, String objectKey, String sha256, long fileSize) throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getString("object_key")).thenReturn(objectKey);
        when(rs.getString("sha256")).thenReturn(sha256);
        when(rs.getLong("file_size")).thenReturn(fileSize);
        when(rs.wasNull()).thenReturn(false);
        return List.of(mapper.mapRow(rs, 0));
    }

    private static AiRun mockRun(UUID runId, long userId, long noteId, ObjectNode snapshot, Instant deadline) {
        AiRun run = mock(AiRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getUserId()).thenReturn(userId);
        when(run.getNoteId()).thenReturn(noteId);
        when(run.getRequestSnapshot()).thenReturn(snapshot);
        when(run.getRequestDeadlineAt()).thenReturn(deadline);
        return run;
    }
}
