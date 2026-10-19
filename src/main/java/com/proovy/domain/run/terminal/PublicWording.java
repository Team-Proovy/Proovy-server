package com.proovy.domain.run.terminal;

import com.proovy.domain.run.entity.RunFailureClass;
import com.proovy.domain.run.entity.RunFailureCode;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Brain {@code contracts/publication.py}·{@code terminal.py}의 고정 공개 문구 allowlist.
 * 공개 summary·step 사유·미지원 안내·실패 message는 이 문구만 쓸 수 있다(내부 reason·provider·code 원문 금지).
 */
final class PublicWording {

    static final String FULL_STEP_VERIFICATION = "모든 단계를 실행 검사로 확인";
    static final String INDEPENDENT_ANSWER_CHECK = "독립 계산으로 답을 비교(과정 미검증)";
    static final Set<String> METHOD_LABELS = Set.of(
            FULL_STEP_VERIFICATION,
            "일부 단계를 수치·보조 증거로 확인",
            "일부 단계를 논리 수사 검토로 확인",
            INDEPENDENT_ANSWER_CHECK);

    static final String LEGITIMATE_LOGICAL_MOVE = "실행 검사 대상이 아닌 논리 수사 단계로 확인됐다";
    static final Set<String> STEP_REASONS = Set.of(
            "이 단계가 성립하지 않는다고 확인됐다",
            "이 단계를 확인할 실행 검사가 만들어지지 않았다",
            "검사 실행 증거가 없다",
            "검사가 이 단계를 판별하지 못했다",
            "검사가 검토되지 않았다",
            "검사가 검증 증거로 인정되지 않았다",
            "검토가 이 검사의 판별을 확정하지 못했다",
            LEGITIMATE_LOGICAL_MOVE,
            "이 단계는 확인되지 않았다");

    static final String UNSUPPORTED_RESPONSE_BODY =
            "변형·연습문제 생성은 아직 지원하지 않는다. 기존 문제의 풀이·검산·설명을 요청해 주세요.";

    private static final Map<RunFailureCode, String> FAILURE_MESSAGES = new EnumMap<>(Map.of(
            RunFailureCode.VERIFICATION_FAILED, "풀이의 오류를 수정하지 못했습니다. 새 요청으로 다시 시도할 수 있습니다.",
            RunFailureCode.BUDGET_EXCEEDED, "요청의 처리 한도 안에서 응답을 완성하지 못했습니다.",
            RunFailureCode.PROVIDER_ERROR, "응답 처리 서비스를 사용할 수 없습니다.",
            RunFailureCode.TIMEOUT, "응답 처리 시간이 초과됐습니다.",
            RunFailureCode.STORAGE_ERROR, "응답을 저장하지 못했습니다.",
            RunFailureCode.INTERNAL_ERROR, "응답 처리를 완료하지 못했습니다."));

    private PublicWording() {
    }

    /** code(와 invalid_attachment의 reason)가 가질 수 있는 유일한 안전 message. */
    static String failureMessage(RunFailureCode code, AttachmentFailureReason reason) {
        return code == RunFailureCode.INVALID_ATTACHMENT ? reason.message() : FAILURE_MESSAGES.get(code);
    }

    /** Brain {@code expected_failure_class}. */
    static RunFailureClass failureClass(RunFailureCode code) {
        return switch (code) {
            case VERIFICATION_FAILED -> RunFailureClass.VERIFICATION;
            case BUDGET_EXCEEDED -> RunFailureClass.BUDGET;
            case INVALID_ATTACHMENT -> RunFailureClass.ATTACHMENT;
            default -> RunFailureClass.INFRASTRUCTURE;
        };
    }
}
