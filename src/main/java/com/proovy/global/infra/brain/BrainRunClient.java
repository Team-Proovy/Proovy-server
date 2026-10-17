package com.proovy.global.infra.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.proovy.domain.run.brain.InternalRunContractViolation;
import com.proovy.domain.run.brain.InternalRunRequest;
import com.proovy.domain.run.brain.InternalRunWire;
import com.proovy.domain.run.brain.RunCancelAck;
import com.proovy.domain.run.brain.RunCancelRequest;
import com.proovy.domain.run.brain.RunDecisions;
import com.proovy.domain.run.brain.RunOperation;
import com.proovy.domain.run.brain.RunRecordState;
import com.proovy.domain.run.brain.RunStatusQuery;
import com.proovy.domain.run.brain.RunStatusResponse;
import com.proovy.domain.run.brain.RuntimeCleanupRequest;
import com.proovy.domain.run.brain.RuntimeCleanupResponse;
import com.proovy.domain.run.brain.SourceEvent;
import com.proovy.domain.run.contract.BrowserRunContract;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.PrematureCloseException;

/**
 * Brain 내부 실행 API(internal-run-v1) client. 1.78·1.80·1.81·1.83이 이 경계로 Brain을 호출한다.
 * <ul>
 *   <li>{@link #stream}: {@code POST /internal/v1/runs/{run_id}/stream}. brain-source-v1 event를 순서대로 내보내고
 *       terminal({@code run.completed}·{@code run.failed})을 낸 뒤 끝난다. terminal 없이 끝나면
 *       {@link BrainFailureKind#STREAM_ENDED_WITHOUT_TERMINAL}. 구독을 취소하면 연결을 닫는다(Brain 실행은
 *       취소되지 않는다 — 실행 취소는 {@link #cancel}).</li>
 *   <li>{@link #status}: {@code GET /internal/v1/runs/{run_id}} 관찰 전용. 본문이 없고 실행을 시작하지 않는다.</li>
 *   <li>{@link #cancel}·{@link #cleanup}: 현재 fence 취소, terminal 저장 확인 뒤 runtime 정리.</li>
 * </ul>
 * 공통 규칙:
 * <ul>
 *   <li>서비스 인증 header 하나만 보낸다. 사용자 JWT·쿠키·브라우저 header를 전달할 경로가 없다.</li>
 *   <li>재시도·redirect 추적·새 run 생성을 하지 않는다. 실패는 {@link BrainCallException#kind()}로 구별해 넘긴다.</li>
 *   <li>stream은 frame idle 상한과 요청의 고정 {@code request_deadline_at} + grace 상한을 지킨다. client가
 *       deadline을 늘리지 않으며, deadline + grace가 지난 요청은 보내지 않는다. deadline은 지났지만 grace 안인
 *       전송은 의도된 것이다: 같은 fingerprint 재전달은 Brain이 저장 terminal을 돌려주므로(RETURN_TERMINAL)
 *       terminal 회수에 쓸 수 있고 새 실행을 만들지 않는다. 회수만 필요하면 {@link #status}가 더 가볍다.</li>
 *   <li>오류에는 본문·token·grant·URL을 담지 않는다.</li>
 * </ul>
 */
public class BrainRunClient {

    private static final String RUNS = "/internal/v1/runs/{runId}";
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final BrainServiceCredentials credentials;
    private final BrainClientProperties properties;
    private final Clock clock;

    public BrainRunClient(BrainClientProperties properties, BrainServiceCredentials credentials, Clock clock) {
        this.properties = properties;
        this.credentials = credentials;
        this.clock = clock;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .followRedirect(false);
        int maxBytes = Math.toIntExact(properties.maxResponseSize().toBytes());
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxBytes))
                        .build())
                .build();
    }

    /**
     * 실행을 등록(또는 같은 fingerprint면 기존 실행에 합류·저장 terminal 재전달)하고 source event를 받는다.
     * 같은 run의 재전달은 {@link InternalRunRequest#forRetry}로 만든 요청으로만 한다.
     */
    public Flux<SourceEvent> stream(InternalRunRequest request) {
        RunOperation operation = RunOperation.STREAM;
        Instant bound = request.executionOptions().requestDeadlineAt().toInstant()
                .plus(properties.streamDeadlineGrace());
        byte[] body = InternalRunWire.write(request);
        return Flux.defer(() -> {
            if (!clock.instant().isBefore(bound)) {
                return Flux.error(BrainCallException.of(operation, BrainFailureKind.DEADLINE_EXCEEDED));
            }
            AtomicBoolean terminal = new AtomicBoolean();
            return bearer(operation)
                    .flatMapMany(token -> webClient.post()
                            .uri(RUNS + "/stream", request.runId())
                            .headers(headers -> serviceHeaders(headers, token, MediaType.TEXT_EVENT_STREAM))
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .exchangeToFlux(response -> response.statusCode().value() == 200
                                    ? sseBody(response)
                                    : BrainRunClient.<ServerSentEvent<String>>failure(operation, response).flux())
                            .timeout(Mono.defer(() -> idleOrDeadline(bound)), frame -> idleOrDeadline(bound))
                            .onErrorMap(error -> mapError(operation, error, bound)))
                    .concatMap(frame -> parseFrame(frame, request.runId()))
                    .takeUntil(SourceEvent::isTerminal)
                    .doOnNext(event -> {
                        if (event.isTerminal()) {
                            terminal.set(true);
                        }
                    })
                    .concatWith(Mono.defer(() -> terminal.get()
                            ? Mono.empty()
                            : Mono.error(BrainCallException.of(operation,
                                    BrainFailureKind.STREAM_ENDED_WITHOUT_TERMINAL))));
        });
    }

    /** record 상태와 저장 terminal 조회. 관찰 전용이다. */
    public Mono<RunStatusResponse> status(RunStatusQuery query) {
        RunOperation operation = RunOperation.STATUS;
        return bearer(operation)
                .flatMap(token -> webClient.get()
                        .uri(builder -> builder.path(RUNS)
                                .queryParam("attempt", query.attempt())
                                .queryParam("fence_token", "{fence}")
                                .build(query.runId(), query.fenceToken()))
                        .headers(headers -> serviceHeaders(headers, token, MediaType.APPLICATION_JSON))
                        .exchangeToMono(response -> json(operation, response, RunStatusResponse.class))
                        .timeout(properties.requestTimeout())
                        .onErrorMap(error -> mapError(operation, error, null)))
                .flatMap(response -> sameRun(operation, response.runId(), query.runId(), response));
    }

    /** 현재 fence의 명시적 취소. 정리는 Brain에서 계속되고 결과는 {@link #status}로 확인한다. */
    public Mono<RunCancelAck> cancel(RunCancelRequest request) {
        RunOperation operation = RunOperation.CANCEL;
        return postJson(operation, RUNS + "/cancel", request.runId(), request, RunCancelAck.class)
                .flatMap(response -> sameRun(operation, response.runId(), request.runId(), response));
    }

    /** terminal을 제품 DB에 저장한 뒤에만 부른다. 이 호출이 저장 확인으로 기록된다. */
    public Mono<RuntimeCleanupResponse> cleanup(RuntimeCleanupRequest request) {
        RunOperation operation = RunOperation.CLEANUP;
        return postJson(operation, RUNS + "/cleanup", request.runId(), request, RuntimeCleanupResponse.class)
                .flatMap(response -> sameRun(operation, response.runId(), request.runId(), response));
    }

    private <T> Mono<T> postJson(RunOperation operation, String path, String runId, Object message, Class<T> type) {
        byte[] body = InternalRunWire.write(message);
        return bearer(operation)
                .flatMap(token -> webClient.post()
                        .uri(path, runId)
                        .headers(headers -> serviceHeaders(headers, token, MediaType.APPLICATION_JSON))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchangeToMono(response -> json(operation, response, type))
                        .timeout(properties.requestTimeout())
                        .onErrorMap(error -> mapError(operation, error, null)));
    }

    /** credential 발급은 request-timeout 안에서 끝나야 한다. 실패·초과는 요청을 보내기 전의 CREDENTIALS_UNAVAILABLE이다. */
    private Mono<String> bearer(RunOperation operation) {
        return Mono.fromCallable(credentials::bearerToken)
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.requestTimeout())
                .onErrorMap(error -> BrainCallException.of(operation, BrainFailureKind.CREDENTIALS_UNAVAILABLE));
    }

    private static void serviceHeaders(HttpHeaders headers, String token, MediaType accept) {
        headers.setBearerAuth(token);
        headers.setAccept(List.of(accept));
    }

    private static Flux<ServerSentEvent<String>> sseBody(ClientResponse response) {
        MediaType type = response.headers().contentType().orElse(null);
        if (type == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)) {
            return response.releaseBody().thenMany(Flux.error(
                    BrainCallException.of(RunOperation.STREAM, BrainFailureKind.MALFORMED_SOURCE, 200)));
        }
        return response.bodyToFlux(SSE);
    }

    private Mono<Long> idleOrDeadline(Instant bound) {
        Duration remaining = Duration.between(clock.instant(), bound);
        Duration idle = properties.streamIdleTimeout();
        return Mono.delay(remaining.isNegative() ? Duration.ZERO : remaining.compareTo(idle) < 0 ? remaining : idle);
    }

    /** keep-alive comment는 idle 상한만 갱신하고 버린다. 그 밖의 frame은 event 이름과 data가 모두 있어야 한다. */
    private static Flux<SourceEvent> parseFrame(ServerSentEvent<String> frame, String runId) {
        if (frame.event() == null && frame.data() == null) {
            return Flux.empty();
        }
        SourceEvent event;
        try {
            if (frame.event() == null || frame.data() == null) {
                throw new InternalRunContractViolation("frame needs event and data");
            }
            event = InternalRunWire.read(frame.data(), SourceEvent.class);
        } catch (InternalRunContractViolation violation) {
            return Flux.error(BrainCallException.of(RunOperation.STREAM, BrainFailureKind.MALFORMED_SOURCE, 200));
        }
        if (!frame.event().equals(event.type().wireValue()) || !event.runId().equals(runId)) {
            return Flux.error(BrainCallException.of(RunOperation.STREAM, BrainFailureKind.MALFORMED_SOURCE, 200));
        }
        return Flux.just(event);
    }

    private static <T> Mono<T> json(RunOperation operation, ClientResponse response, Class<T> type) {
        if (response.statusCode().value() != 200) {
            return failure(operation, response);
        }
        return response.bodyToMono(byte[].class)
                .switchIfEmpty(Mono.error(() -> BrainCallException.of(operation,
                        BrainFailureKind.MALFORMED_RESPONSE, 200)))
                .map(bytes -> {
                    try {
                        return InternalRunWire.read(bytes, type);
                    } catch (InternalRunContractViolation violation) {
                        throw BrainCallException.of(operation, BrainFailureKind.MALFORMED_RESPONSE, 200);
                    }
                });
    }

    private static <T> Mono<T> sameRun(RunOperation operation, String actual, String expected, T response) {
        return actual.equals(expected)
                ? Mono.just(response)
                : Mono.error(BrainCallException.of(operation, BrainFailureKind.MALFORMED_RESPONSE, 200));
    }

    /** 오류 응답 본문에서 allowlist 값(error·reason·state·existing_run_id)만 읽는다. */
    private static <T> Mono<T> failure(RunOperation operation, ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .map(body -> classify(operation, status, safeTree(body)))
                .onErrorResume(error -> Mono.just(classify(operation, status, null)))
                .defaultIfEmpty(classify(operation, status, null))
                .flatMap(error -> Mono.<T>error(error));
    }

    static BrainCallException classify(RunOperation operation, int status, JsonNode body) {
        String reason = text(body, "reason");
        return switch (status) {
            case 400 -> BrainCallException.of(operation, BrainFailureKind.INVALID_REQUEST, status);
            case 401, 403 -> BrainCallException.of(operation, BrainFailureKind.AUTH_REJECTED, status);
            case 413 -> BrainCallException.of(operation, BrainFailureKind.REQUEST_TOO_LARGE, status);
            case 409 -> {
                if (RunDecisions.REQUEST_ID_BOUND_TO_OTHER_RUN.equals(reason)) {
                    String existing = text(body, "existing_run_id");
                    yield new BrainCallException(operation, BrainFailureKind.REQUEST_ID_BOUND_TO_OTHER_RUN, status,
                            null, BrowserRunContract.isOpaqueId(existing) ? existing : null);
                }
                yield new BrainCallException(operation, BrainFailureKind.RUN_CONFLICT, status,
                        recordState(text(body, "state")), null);
            }
            // 429는 Cloud Run 앞단의 instance·동시성 상한 거부다. Brain record가 생기기 전이라 capacity와 같게 다룬다.
            case 429 -> BrainCallException.of(operation, BrainFailureKind.CAPACITY, status);
            case 503 -> BrainCallException.of(operation,
                    "capacity".equals(reason) ? BrainFailureKind.CAPACITY : BrainFailureKind.UNAVAILABLE, status);
            default -> BrainCallException.of(operation, status >= 500
                    ? BrainFailureKind.UNAVAILABLE : BrainFailureKind.UNEXPECTED_STATUS, status);
        };
    }

    Throwable mapError(RunOperation operation, Throwable error, Instant streamBound) {
        if (error instanceof BrainCallException) {
            return error;
        }
        Function<BrainFailureKind, Throwable> as = kind -> BrainCallException.of(operation, kind);
        if (error instanceof TimeoutException) {
            return as.apply(streamBound != null && !clock.instant().isBefore(streamBound)
                    ? BrainFailureKind.DEADLINE_EXCEEDED : BrainFailureKind.TIMEOUT);
        }
        if (error instanceof DataBufferLimitException || error.getCause() instanceof DataBufferLimitException
                || error instanceof InternalRunContractViolation) {
            return as.apply(operation == RunOperation.STREAM
                    ? BrainFailureKind.MALFORMED_SOURCE : BrainFailureKind.MALFORMED_RESPONSE);
        }
        if (error instanceof WebClientRequestException request) {
            Throwable cause = request.getCause();
            return as.apply(cause instanceof ConnectException || cause instanceof ConnectTimeoutException
                    || cause instanceof UnknownHostException
                    ? BrainFailureKind.CONNECT_FAILED : BrainFailureKind.TRANSPORT_LOST);
        }
        if (error instanceof PrematureCloseException || error instanceof IOException
                || error instanceof WebClientResponseException
                || error.getCause() instanceof PrematureCloseException || error.getCause() instanceof IOException) {
            return as.apply(BrainFailureKind.TRANSPORT_LOST);
        }
        // 분류하지 못한 예외(codec·pool 등)도 kind로 감싸고 원래 message·cause(URL·fence_token이 들어갈 수 있다)는 버린다.
        // 요청이 닿았는지 알 수 없으므로 status 조회로 수습하는 TRANSPORT_LOST로 둔다.
        return as.apply(BrainFailureKind.TRANSPORT_LOST);
    }

    private static JsonNode safeTree(String body) {
        try {
            return InternalRunWire.readTree(body);
        } catch (InternalRunContractViolation malformed) {
            return null;
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static RunRecordState recordState(String value) {
        for (RunRecordState state : RunRecordState.values()) {
            if (state.wireValue().equals(value)) {
                return state;
            }
        }
        return null;
    }
}
