package com.proovy.domain.run.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.entity.ClarificationReason;
import com.proovy.domain.run.entity.ResponseDisposition;
import java.util.List;

/**
 * 수락한 {@code run.completed}의 저장 입력.
 * <ul>
 *   <li>{@code responsePages}: canonical {@code response_pages[{response_page_id, presentation, title?, result_blocks}]}</li>
 *   <li>{@code sourceReferences}: {@code [{source_ref_id, asset_id, page_start, page_end, display_label_snapshot}]}.
 *       {@code asset_id}는 Server Note Asset ID(정수), label은 Brain publication이 정답 숨기기 검사를 한 label이다.</li>
 *   <li>{@code plainText}: 공개 page title과 block body에서만 파생(Brain {@code derive_plain_text}). artifact·label·summary는 넣지 않는다.</li>
 *   <li>{@code contentSha256}: {@code {response_pages, source_references, plain_text}}의 canonical JSON sha256</li>
 *   <li>{@code verificationSummary}: 공개 summary 배열(허용 문구만)</li>
 * </ul>
 * 출처 검사는 metadata(소유·version·page 범위·provenance manifest) 대조일 뿐 인용 의미·정답을 보장하지 않는다.
 * JsonNode 값은 이 객체가 소유하므로 호출자가 바꾸지 않는다.
 */
public record AcceptedCompletion(
        String runId,
        int attempt,
        String fenceToken,
        String sourceEventId,
        ResponseDisposition disposition,
        ClarificationReason clarificationReason,
        JsonNode responsePages,
        JsonNode sourceReferences,
        String plainText,
        String contentSha256,
        JsonNode verificationSummary,
        List<VerifiedSolutionInput> verifiedSolutions,
        String model,
        String tier,
        TerminalRecheck recheck) implements AcceptedTerminal {

    public AcceptedCompletion {
        verifiedSolutions = List.copyOf(verifiedSolutions);
    }
}
