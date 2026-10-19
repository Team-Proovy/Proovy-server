package com.proovy.global.infra.brain;

/**
 * Brain 내부 API 호출 실패 원인. 호출자(coordinator)가 다음 행동을 고르는 기준이다.
 * client는 어떤 실패에서도 스스로 재시도하거나 새 run·attempt를 만들지 않는다. 범주는 셋이다.
 * <ul>
 *   <li>{@link #isRecoverableByStatusQuery()}: 요청이 Brain에 닿았을 수 있어 실행이 남아 있을 수 있다.
 *       같은 attempt·fence로 {@code status}를 조회해 수습한다(새 실행 금지).</li>
 *   <li>{@link #isSafeToResend()}: 요청이 Brain record에 닿지 않았다(연결 실패·capacity·429·credential 발급 실패).
 *       같은 요청(같은 attempt·fence)을 deadline 안에서 다시 보내도 된다. 언제·몇 번 보낼지는 호출자가 정한다.</li>
 *   <li>둘 다 아니면 영구 실패다(요청·계약·인증·설정 문제, contention 거부). 같은 요청을 다시 보내도 달라지지 않는다.</li>
 * </ul>
 */
public enum BrainFailureKind {
    /** 연결 수립 실패(요청이 Brain에 닿지 않았다). */
    CONNECT_FAILED,
    /** 연결 후 전송 유실(응답·stream 중간 단절). */
    TRANSPORT_LOST,
    /** 응답 시간 또는 stream idle 상한 초과. */
    TIMEOUT,
    /** stream이 요청의 고정 deadline + grace를 넘었다. */
    DEADLINE_EXCEEDED,
    /**
     * 503 {@code reason=capacity}(Brain 인스턴스 동시 실행 상한) 또는 429(Cloud Run 앞단 상한).
     * record가 만들어지기 전 거부라 같은 요청을 다시 보낼 수 있다.
     */
    CAPACITY,
    /** 그 밖의 5xx(503 unavailable 등). */
    UNAVAILABLE,
    /** 409 {@code reason=request_id_bound_to_other_run}: request_id가 다른 run에 묶였다(항상 Server 버그). */
    REQUEST_ID_BOUND_TO_OTHER_RUN,
    /** 409 {@code state=...}: fingerprint 충돌·취소 중·정리된 record 등 contention 거부. */
    RUN_CONFLICT,
    /** 400 {@code invalid_request}: Brain이 계약 위반으로 거부했다. */
    INVALID_REQUEST,
    /** 413 {@code request_too_large}. */
    REQUEST_TOO_LARGE,
    /** 401/403: 서비스 인증 거부. */
    AUTH_REJECTED,
    /** Server가 서비스 credential(ID token·shared token)을 만들지 못했다. */
    CREDENTIALS_UNAVAILABLE,
    /** 그 밖의 예상하지 않은 HTTP status. */
    UNEXPECTED_STATUS,
    /** status·cancel·cleanup 응답 본문이 계약과 맞지 않는다. */
    MALFORMED_RESPONSE,
    /** stream frame이 brain-source-v1 계약과 맞지 않는다(run.canceled 포함, 다른 run_id 등). */
    MALFORMED_SOURCE,
    /** terminal 없이 stream이 끝났다(취소된 run·Brain 상한·cleanup). status로 저장 terminal을 확인한다. */
    STREAM_ENDED_WITHOUT_TERMINAL;

    public boolean isSafeToResend() {
        return switch (this) {
            case CONNECT_FAILED, CAPACITY, CREDENTIALS_UNAVAILABLE -> true;
            default -> false;
        };
    }

    public boolean isRecoverableByStatusQuery() {
        return switch (this) {
            case TRANSPORT_LOST, TIMEOUT, DEADLINE_EXCEEDED, UNAVAILABLE, STREAM_ENDED_WITHOUT_TERMINAL -> true;
            default -> false;
        };
    }
}
