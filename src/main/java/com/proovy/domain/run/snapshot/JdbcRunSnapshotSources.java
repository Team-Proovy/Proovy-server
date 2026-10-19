package com.proovy.domain.run.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link RunSnapshotSources}의 SQL 구현. 필요한 열만 읽고(response_pages·solution_text 등 공개되지 않을 수 있는 본문은
 * 읽지 않는다) 개수 상한을 DB에서 건다.
 */
@Slf4j
@Repository
public class JdbcRunSnapshotSources implements RunSnapshotSources {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRunSnapshotSources(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActiveNoteOwnedBy(long noteId, long userId) {
        Boolean owned = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notes WHERE note_id = :noteId AND user_id = :userId AND status = 'ACTIVE')
                """, Map.of("noteId", noteId, "userId", userId), Boolean.class);
        return Boolean.TRUE.equals(owned);
    }

    /**
     * turn 순서는 run 생성 순서다({@code idx_ai_runs_note_created}를 탄다). 접수(1.79)가 한 transaction에서 user 메시지와
     * run을 함께 만들고 Note당 Active Run이 하나라, run 생성 순서는 user 메시지 순서와 같다.
     */
    @Override
    public List<CompletedTurn> completedTurnsNewestFirst(long noteId, int limit) {
        return jdbc.query("""
                SELECT u.message_id AS user_message_id, u.plain_text AS user_text,
                       a.message_id AS assistant_message_id, a.plain_text AS assistant_text
                FROM ai_runs r
                JOIN chat_messages u ON u.message_id = r.user_message_id AND u.note_id = r.note_id
                JOIN chat_messages a ON a.message_id = r.assistant_message_id AND a.note_id = r.note_id
                WHERE r.note_id = :noteId AND r.status = 'COMPLETED'
                  AND u.status = 'COMPLETED' AND a.status = 'COMPLETED'
                ORDER BY r.created_at DESC, r.run_id DESC
                LIMIT :limit
                """, Map.of("noteId", noteId, "limit", limit), (rs, i) -> new CompletedTurn(
                rs.getObject("user_message_id", UUID.class), rs.getString("user_text"),
                rs.getObject("assistant_message_id", UUID.class), rs.getString("assistant_text")));
    }

    @Override
    public List<SolutionRow> solutionsForMessages(long noteId, Collection<UUID> assistantMessageIds) {
        if (assistantMessageIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT s.solution_id, s.public_solution_id, s.assistant_message_id, s.problem_statement,
                       s.claims::text AS claims, s.revises_solution_id,
                       p.public_solution_id AS revises_public_solution_id,
                       s.status = 'SUPERSEDED' AS superseded
                FROM verified_solutions s
                LEFT JOIN verified_solutions p ON p.solution_id = s.revises_solution_id AND p.note_id = s.note_id
                WHERE s.note_id = :noteId AND s.assistant_message_id IN (:messageIds)
                ORDER BY s.created_at, s.solution_id
                """, Map.of("noteId", noteId, "messageIds", assistantMessageIds), (rs, i) -> new SolutionRow(
                rs.getObject("solution_id", UUID.class), rs.getString("public_solution_id"),
                rs.getObject("assistant_message_id", UUID.class), rs.getString("problem_statement"),
                claims(rs), rs.getObject("revises_solution_id", UUID.class),
                rs.getString("revises_public_solution_id"), rs.getBoolean("superseded")));
    }

    @Override
    public List<AttachmentRow> attachmentsForMessages(long noteId, Collection<UUID> userMessageIds) {
        if (userMessageIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT attachment_id, source_message_id, attachment_index, sha256, mime_type,
                       page_understanding_version, image_normalization_version, display_label
                FROM turn_attachments
                WHERE note_id = :noteId AND source_message_id IN (:messageIds)
                ORDER BY created_at, attachment_index
                """, Map.of("noteId", noteId, "messageIds", userMessageIds), (rs, i) -> new AttachmentRow(
                rs.getObject("attachment_id", UUID.class), rs.getObject("source_message_id", UUID.class),
                rs.getInt("attachment_index"), rs.getString("sha256"), rs.getString("mime_type"),
                rs.getString("page_understanding_version"), rs.getString("image_normalization_version"),
                rs.getString("display_label")));
    }

    @Override
    public List<CorpusAsset> activeAssets(long noteId) {
        return jdbc.query("""
                SELECT id, content_version FROM assets WHERE note_id = :noteId AND status = 'ACTIVE' ORDER BY id
                """, Map.of("noteId", noteId),
                (rs, i) -> new CorpusAsset(rs.getLong("id"), rs.getInt("content_version")));
    }

    /** claims JSONB. 읽을 수 없으면 빈 값으로 두고 원인(solution ID만)을 남긴다 — 그 solution은 참조에서 빠진다. */
    private static JsonNode claims(ResultSet rs) throws SQLException {
        String text = rs.getString("claims");
        try {
            return text == null ? NullNode.getInstance() : JSON.readTree(text);
        } catch (JsonProcessingException e) {
            log.warn("verified solution {} claims are not readable JSON", rs.getObject("solution_id", UUID.class));
            return NullNode.getInstance();
        }
    }
}
