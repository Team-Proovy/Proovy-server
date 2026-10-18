package com.proovy.domain.run.stream;

import com.proovy.domain.run.admission.RunAdmission;
import com.proovy.domain.run.contract.RunEventCursor;
import com.proovy.domain.run.event.RunEventSignals;
import com.proovy.global.exception.BusinessException;
import com.proovy.global.response.ErrorCode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 브라우저 SSE 연결을 연다. 판정은 모두 stream 시작 전이라 거부는 JSON 오류다.
 * <p>
 * 연결은 저장된 event를 읽고 신호를 구독할 뿐이다. run을 만들거나 dispatch·취소·차감하지 않으므로 몇 번을 다시 연결해도
 * run·worker·사용량은 늘지 않는다. 연결 종료(브라우저 단절·timeout)는 구독 해제이고 실행 취소가 아니다(취소는 명시적 cancel API).
 */
@Service
public class RunStreamService {

    private final RunReadService reads;
    private final RunFrameSource frames;
    private final RunEventSignals signals;
    private final RunSseScheduler scheduler;
    private final RunSseProperties properties;
    private final AtomicInteger openStreams = new AtomicInteger();

    public RunStreamService(RunReadService reads, RunFrameReader frames, RunEventSignals signals,
                            RunSseScheduler scheduler, RunSseProperties properties) {
        this.reads = reads;
        this.frames = frames;
        this.signals = signals;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /** POST /api/conversations 접수 결과: CREATED·REPLAYED 모두 seq 1 run.created부터, terminal이 아니면 live로 잇는다. */
    public SseEmitter openAdmitted(RunAdmission admission) {
        return open(admission.runId(), RunAdmission.REPLAY_FROM_SEQ - 1);
    }

    /**
     * GET /api/ai-runs/{runId}/events. 판정 순서(cursors/cases.json precedence): run 접근 RUN4041 → cursor 형식·범위 RUN4003 →
     * 다른 run RUN4004. 없는 run·타인 run·삭제 중 Note는 같은 404다.
     */
    public SseEmitter openReplay(long userId, String runId, String lastEventIdHeader, String lastEventIdQuery) {
        RunReadService.RunRow run = reads.accessibleRun(userId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN4041));
        RunEventCursor.ReplayStart start = RunEventCursor.resolve(run.runId().toString(), run.lastSeq(),
                lastEventIdHeader, lastEventIdQuery);
        if (start.isRejected()) {
            throw new BusinessException(start.error());
        }
        return open(run.runId(), start.fromSeq() - 1);
    }

    /** 지금 열린 SSE 연결 수. 브라우저 단절·terminal 종료·timeout 뒤에는 줄어든다. */
    public int openStreams() {
        return openStreams.get();
    }

    private SseEmitter open(UUID runId, long afterSeq) {
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        openStreams.incrementAndGet();
        RunEventStream stream = new RunEventStream(runId, afterSeq, frames, new SseEmitterSink(emitter), signals,
                scheduler, properties, openStreams::decrementAndGet);
        emitter.onCompletion(stream::close);
        emitter.onError(error -> stream.close());
        emitter.onTimeout(() -> {
            // 최대 유지 시간: 연결만 닫는다. 브라우저는 마지막 event ID로 GET 재접속한다.
            stream.close();
            emitter.complete();
        });
        stream.start();
        return emitter;
    }
}
