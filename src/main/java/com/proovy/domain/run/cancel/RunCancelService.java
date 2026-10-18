package com.proovy.domain.run.cancel;

import com.proovy.domain.run.cancel.RunCancelStore.LockedRun;
import com.proovy.domain.run.contract.CancelOutcome;
import com.proovy.domain.run.contract.PublicRunStatus;
import com.proovy.domain.run.contract.RunCancelResult;
import com.proovy.domain.run.entity.RunCancelCause;
import com.proovy.domain.run.stream.RunReadService;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 사용자 명시적 취소 {@code POST /api/ai-runs/{runId}/cancel}(task 1.84). 판정 순서는 browser-run-v1 {@code cancel/cases.json}
 * precedence다: 인증(filter) → body 있음 RUN4001(run 조회 전) → run 접근 RUN4041 → 상태.
 * <ul>
 *   <li>PENDING: 즉시 CANCELED + 한 번 보상, 같은 transaction. Brain을 부르지 않는다(claim과는 하나만 이긴다).</li>
 *   <li>RUNNING·RETRYING: CANCELING + {@code run.canceling}. commit 뒤 {@link RunCancelFinalizer}가 Brain cancel·정리 확인 또는
 *       안전 timeout 뒤 CANCELED로 확정한다. 차감은 유지된다.</li>
 *   <li>CANCELING: {@code already_canceling}, terminal: {@code already_terminal}(멱등, 아무것도 바꾸지 않는다).</li>
 * </ul>
 * 브라우저 연결 단절은 취소가 아니다 — 취소는 이 경로뿐이다. 취소 뒤 자동 재실행·서버 queue·draft 전송은 없다.
 */
@Service
public class RunCancelService {

    private final RunReadService reads;
    private final RunCancelStore store;
    private final RunCancelFinalizer finalizer;

    public RunCancelService(RunReadService reads, RunCancelStore store, RunCancelFinalizer finalizer) {
        this.reads = reads;
        this.store = store;
        this.finalizer = finalizer;
    }

    private record Decision(PublicRunStatus status, CancelOutcome outcome, boolean canceling) {
    }

    /**
     * @param body 요청 body 원문. 비어 있지 않은 body는 어떤 내용이든 RUN4001이다(cancel은 body가 없다, `{}`도 거부)
     */
    public RunCancelResult cancel(long userId, String runId, String body) {
        if (body != null) {
            throw new BusinessException(ErrorCode.RUN4001);
        }
        // 공개 경로의 접근 규칙(1.86): 소문자 UUID·소유자·Note ACTIVE. 없음·타인·삭제 중은 같은 RUN4041이다.
        if (reads.accessibleRun(userId, runId).isEmpty()) {
            throw new BusinessException(ErrorCode.RUN4041);
        }
        UUID id = UUID.fromString(runId);
        Decision decision = store.inTransactions(() -> {
            // 사전 조회와 잠금 사이에 Note 삭제가 시작됐으면 없는 run과 같다
            LockedRun run = store.lock(id).filter(locked -> locked.userId() == userId && locked.noteActive())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RUN4041));
            return switch (run.status()) {
                case PENDING -> {
                    store.cancelPending(run, RunCancelCause.USER_REQUESTED);
                    yield new Decision(PublicRunStatus.CANCELED, CancelOutcome.ACCEPTED, false);
                }
                case RUNNING, RETRYING -> {
                    store.markCanceling(run, RunCancelCause.USER_REQUESTED);
                    yield new Decision(PublicRunStatus.CANCELING, CancelOutcome.ACCEPTED, true);
                }
                case CANCELING -> new Decision(PublicRunStatus.CANCELING, CancelOutcome.ALREADY_CANCELING, true);
                case COMPLETED, FAILED, CANCELED ->
                        new Decision(PublicRunStatus.from(run.status()), CancelOutcome.ALREADY_TERMINAL, false);
            };
        });
        if (decision.canceling()) {
            // commit 뒤: Brain 호출은 transaction 밖이다. 실패해도 주기 확인이 이어받는다.
            finalizer.wake(id);
        }
        return new RunCancelResult(id.toString(), decision.status(), decision.outcome());
    }
}
