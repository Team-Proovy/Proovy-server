package com.proovy.domain.run.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.admission.RunAdmissionService;
import com.proovy.domain.run.contract.ConversationPage;
import com.proovy.domain.run.contract.RunStatus;
import com.proovy.domain.run.stream.RunReadService;
import com.proovy.domain.run.stream.RunStreamService;
import com.proovy.global.response.ApiResponse;
import com.proovy.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 브라우저 run API (browser-run-v1). 경로·오류·판정 순서의 원본은 pin된 browser-run-v1 artifact다.
 * <p>
 * 스트림 시작 전 거부는 Accept가 {@code text/event-stream}이어도 JSON {@code ApiResponse}다
 * ({@link BrowserRunExceptionHandler}, 접수 거부는 {@code RunAdmissionExceptionHandler}). 시작 뒤에는 terminal event로 끝난다.
 * 취소({@code POST /api/ai-runs/{runId}/cancel})는 1.84가 이 controller에 추가한다.
 */
@RestController
@Tag(name = "Browser Run", description = "대화 요청 접수·SSE replay·run 상태·대화 조회 (browser-run-v1)")
public class BrowserRunController {

    /** GET 재접속 cursor header. 같은 값을 {@code lastEventId} query로도 받는다(fetch 기반 client·EventSource 공용). */
    static final String LAST_EVENT_ID = "Last-Event-ID";

    private final RunAdmissionService admission;
    private final RunStreamService streams;
    private final RunReadService reads;

    public BrowserRunController(RunAdmissionService admission, RunStreamService streams, RunReadService reads) {
        this.admission = admission;
        this.streams = streams;
        this.reads = reads;
    }

    @PostMapping("/api/conversations")
    @Operation(summary = "대화 요청 접수 (SSE)",
            description = "ConversationRunRequest를 접수하고 seq 1 run.created부터 SSE로 보낸다. 같은 requestId·같은 의미의 "
                    + "재전송은 기존 run을 seq 1부터 replay한다. terminal이 아니면 live로 이어진다.")
    public SseEmitter create(@Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
                             @RequestBody(required = false) JsonNode body) {
        // body는 바인딩 전 tree로 판정한다(1.79). 없는 body도 같은 판정기가 RUN4001로 거부한다.
        return streams.openAdmitted(admission.admit(principal.getUserId(), body));
    }

    @GetMapping("/api/ai-runs/{runId}/events")
    @Operation(summary = "run event 재접속 (SSE)",
            description = "cursor가 없으면 seq 1부터, Last-Event-ID header 또는 lastEventId query({runId}:{seq})가 있으면 "
                    + "그 다음 seq부터 replay한 뒤 live로 잇는다. heartbeat는 15초마다 `: heartbeat` comment다.")
    public SseEmitter events(@Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
                             @PathVariable String runId,
                             @RequestHeader(name = LAST_EVENT_ID, required = false) String lastEventIdHeader,
                             @RequestParam(name = "lastEventId", required = false) String lastEventIdQuery) {
        return streams.openReplay(principal.getUserId(), runId, lastEventIdHeader, lastEventIdQuery);
    }

    @GetMapping("/api/ai-runs/{runId}")
    @Operation(summary = "run 상태", description = "lastSeq는 그 시점 마지막 durable seq다. 내부 RETRYING은 RUNNING이다.")
    public ApiResponse<RunStatus> status(@Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable String runId) {
        return ApiResponse.success(reads.status(principal.getUserId(), runId));
    }

    @GetMapping("/api/conversations/{noteId}/messages")
    @Operation(summary = "대화 조회",
            description = "before(messageId)보다 오래된 메시지 중 최신 limit(1..100, 기본 50)개를 createdAt 오름차순으로. "
                    + "완료 assistant 본문은 SSE run.completed와 같은 canonical 응답이다.")
    public ApiResponse<ConversationPage> messages(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String noteId,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) String limit) {
        return ApiResponse.success(reads.conversation(principal.getUserId(), noteId, before, limit));
    }
}
