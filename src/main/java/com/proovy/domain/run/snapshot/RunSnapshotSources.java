package com.proovy.domain.run.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * snapshot 구성에 필요한 제품 데이터 조회. 모두 Note 범위로 걸러진 projection만 돌려주고 엔티티 그래프를 읽지 않는다.
 * 구현은 {@link JdbcRunSnapshotSources}.
 */
public interface RunSnapshotSources {

    /** 사용자 소유의 ACTIVE Note인지. 없음·타인·삭제 중은 모두 false다(존재 비노출). */
    boolean isActiveNoteOwnedBy(long noteId, long userId);

    /**
     * 결과가 확정된(COMPLETED run) turn을 최신(run 생성 순)부터 최대 {@code limit}개. user·assistant 짝은 run 기준이다
     * (run의 user_message_id·assistant_message_id). 실패·취소·진행 중 run의 메시지는 오지 않는다.
     */
    List<CompletedTurn> completedTurnsNewestFirst(long noteId, int limit);

    /** 주어진 assistant 메시지에 묶인 같은 Note의 Verified Solution. */
    List<SolutionRow> solutionsForMessages(long noteId, Collection<UUID> assistantMessageIds);

    /** 주어진 user 메시지에 묶인 같은 Note의 Turn Attachment. */
    List<AttachmentRow> attachmentsForMessages(long noteId, Collection<UUID> userMessageIds);

    /** Note의 ACTIVE asset(Note Corpus 구성원). */
    List<CorpusAsset> activeAssets(long noteId);

    record CompletedTurn(UUID userMessageId, String userText, UUID assistantMessageId, String assistantText) {
    }

    /**
     * @param publicSolutionId        Brain neutral ID(run 안에서만 unique)
     * @param revisesSolutionId       정정 대상의 Server ID(없으면 null)
     * @param revisesPublicSolutionId 정정 대상의 Brain neutral ID(없으면 null)
     * @param superseded              현재 SUPERSEDED인지(조회 시점 상태)
     * @param claims                  {@code [{claim_id, statement, coverage}]}
     */
    record SolutionRow(UUID solutionId, String publicSolutionId, UUID assistantMessageId, String problemStatement,
                       JsonNode claims, UUID revisesSolutionId, String revisesPublicSolutionId, boolean superseded) {
    }

    record AttachmentRow(UUID attachmentId, UUID sourceMessageId, int attachmentIndex, String sha256,
                         String mimeType, String pageUnderstandingVersion, String imageNormalizationVersion,
                         String displayLabel) {
    }

    record CorpusAsset(long assetId, int contentVersion) {
    }
}
