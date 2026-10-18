package com.proovy.domain.run.admission;

import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Note Asset hard delete 전 검사 (#227 R5, S13 DELETING 전환 전까지). run이 한 번이라도 의존한 asset은
 * {@code fk_run_asset_dependencies_asset}(deferred) 때문에 commit에서 FK 위반이 되므로, 미리 막고 ASSET4092로 답한다.
 * <p>
 * 접수와 같은 잠금 순서로 Note 행을 먼저 잠근다({@code FOR NO KEY UPDATE}, note_id 순 — 접수와는 서로 직렬화되고
 * Note를 참조하는 자식 insert는 막지 않는다). 그래서 진행 중인 접수가 있으면 그 commit을
 * 기다린 뒤 새 의존성을 보고 거부하고, 삭제가 먼저면 접수는 Note 잠금 뒤 asset이 없는 corpus로 snapshot을 만든다.
 */
@Component
public class RunAssetDeletionGuard {

    private static final String LOCK_NOTES = """
            SELECT note_id FROM notes WHERE note_id IN (%s) ORDER BY note_id FOR NO KEY UPDATE
            """;
    private static final String HAS_DEPENDENCY = "SELECT EXISTS (SELECT 1 FROM run_asset_dependencies WHERE asset_id IN (%s))";

    private final JdbcTemplate jdbc;

    public RunAssetDeletionGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param noteIds  삭제할 asset들이 속한 Note
     * @param assetIds 삭제할 asset
     * @throws BusinessException ASSET4092 — 어떤 run이 의존한 asset이 있다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNoRunDependency(Collection<Long> noteIds, Collection<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return;
        }
        List<Long> notes = new ArrayList<>(new TreeSet<>(noteIds));
        jdbc.queryForList(LOCK_NOTES.formatted(placeholders(notes.size())), Long.class, notes.toArray());
        List<Long> assets = List.copyOf(new TreeSet<>(assetIds));
        Boolean used = jdbc.queryForObject(HAS_DEPENDENCY.formatted(placeholders(assets.size())), Boolean.class,
                assets.toArray());
        if (Boolean.TRUE.equals(used)) {
            throw new BusinessException(ErrorCode.ASSET4092);
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
