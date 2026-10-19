package com.proovy.domain.run.admission;

import com.proovy.domain.run.contract.ActiveRunConflict;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 스트림 시작 전에 거부된 접수 (browser-run-v1 오류 envelope). 거부된 접수는 Note·메시지·run·차감·event를 남기지 않는다
 * (접수 transaction 전체 rollback). message는 code 고정 문구만 쓴다.
 * <ul>
 *   <li>RUN4091만 {@link ActiveRunConflict} result를 싣는다(상태 복구용).</li>
 *   <li>RUN4291은 {@code Retry-After}(초)를 싣는다.</li>
 *   <li>RUN4092·RUN4041은 기존 run·Note의 존재·상태를 드러내지 않는 같은 body다.</li>
 * </ul>
 */
public class RunAdmissionRejectedException extends BusinessException {

    private final ActiveRunConflict activeRun;
    private final Long retryAfterSeconds;

    private RunAdmissionRejectedException(ErrorCode errorCode, ActiveRunConflict activeRun, Long retryAfterSeconds) {
        super(errorCode);
        this.activeRun = activeRun;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static RunAdmissionRejectedException of(ErrorCode errorCode) {
        return new RunAdmissionRejectedException(errorCode, null, null);
    }

    static RunAdmissionRejectedException activeRun(UUID activeRunId) {
        return new RunAdmissionRejectedException(ErrorCode.RUN4091, new ActiveRunConflict(activeRunId.toString()), null);
    }

    static RunAdmissionRejectedException rateLimited(long retryAfterSeconds) {
        return new RunAdmissionRejectedException(ErrorCode.RUN4291, null, retryAfterSeconds);
    }

    /** RUN4091의 result. 다른 code에는 없다. */
    public Optional<ActiveRunConflict> activeRun() {
        return Optional.ofNullable(activeRun);
    }

    /** RUN4291의 {@code Retry-After} 초. 다른 code에는 없다. */
    public OptionalLong retryAfterSeconds() {
        return retryAfterSeconds == null ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
    }
}
