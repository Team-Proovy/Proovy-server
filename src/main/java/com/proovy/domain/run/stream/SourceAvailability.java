package com.proovy.domain.run.stream;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Source Reference {@code available}의 read-time 판정(browser-run-v1: replay와 live의 유일한 허용 차이).
 * 같은 Note의 asset이 지금 {@code ACTIVE}면 true, DELETING·DELETED·없음이면 false다. label·page snapshot은 판정과 무관하게
 * 저장값 그대로다.
 */
@Component
public class SourceAvailability {

    private final JdbcTemplate jdbc;

    public SourceAvailability(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** canonical {@code source_references}(snake_case) 배열들의 asset 중 지금 쓸 수 있는 것. */
    public LongPredicate of(long noteId, List<JsonNode> sourceReferences) {
        Set<Long> assetIds = new HashSet<>();
        for (JsonNode references : sourceReferences) {
            references.forEach(reference -> assetIds.add(reference.get("asset_id").asLong()));
        }
        if (assetIds.isEmpty()) {
            return assetId -> false;
        }
        Set<Long> active = new HashSet<>(jdbc.query(connection -> {
            var ps = connection.prepareStatement(
                    "SELECT id FROM assets WHERE note_id = ? AND status = 'ACTIVE' AND id = ANY(?)");
            ps.setLong(1, noteId);
            ps.setArray(2, connection.createArrayOf("bigint", assetIds.toArray(Long[]::new)));
            return ps;
        }, (rs, row) -> rs.getLong(1)));
        return active::contains;
    }
}
