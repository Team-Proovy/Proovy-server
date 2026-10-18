# internal-run-v1 — Server → Brain 내부 실행 client (task 1.76)

Server가 Brain 내부 실행 API를 부르는 경계다. 계약 원본은 proovy-brain이고 Server는 사본을 pin해 소비한다.

- **SSOT**: proovy-brain `src/proovy_brain/contracts/internal_run.py`·`internal_run_fingerprint.py`·`apps/api/app.py`·`apps/api/auth.py`,
  fixture `tests/fixtures/contracts/internal-run-v1/`
- **pin**: commit `303a9e45943207cb22fc555452cc781fb8117d21`, `consumer_version` `internal-run-fixtures-v2`
  (`src/test/resources/contracts/brain/PIN-internal-run-v1.json`, 갱신 절차는 같은 디렉터리 `README.md`)
- 이 문서는 Java 대응과 인계 메모다. 계약 의미를 새로 정하지 않는다.

## Java 대응

| 패키지 | 내용 |
|---|---|
| `com.proovy.domain.run.brain` | wire DTO(record)·fingerprint·판정표·snake_case mapper. 후속 태스크는 이 패키지 타입을 그대로 쓴다. |
| `com.proovy.global.infra.brain` | HTTP client·서비스 인증·오류 분류·설정 |

- 요청: `InternalRunRequest`(+ `RunInput`·`HistoryMessage`·`VerifiedSolutionRef(Claim)`·`TurnAttachmentRef`·`ViewerContext`(`ViewerSelection`)·`RetrievalScope`·`TurnAttachment`·`ExecutionOptions`(`ExecutionLimits`·`ExecutionUsage`))
- 조회·취소·정리: `RunStatusQuery`→`RunStatusResponse`, `RunCancelRequest`→`RunCancelAck`, `RuntimeCleanupRequest`→`RuntimeCleanupResponse`
- source: `SourceEvent`(stream union: `activity.*`·`run.completed`·`run.failed`), `StoredTerminal`(status 전용: 위 terminal + 저장 전용 `run.canceled`)
- `InternalRunFingerprint`: Brain `request_fingerprint_document`와 같은 projection → `CanonicalJson`(1.75) → sha256. `RunStatusResponse.matches(fingerprint)`로 비교
- `RunDecisions`: `decisions.json`(run-decisions-v2) 판정표와 binding override
- `InternalRunWire`: 이 wire 전용 Jackson 설정. Spring 기본 mapper(브라우저 camelCase)로 이 DTO를 (역)직렬화하지 않는다.

record 생성자가 Brain과 같은 규칙으로 정규화·검증한다(Python `str.strip()` 집합 strip, code point 길이, 식별자·sha256·version 패턴,
MIME별 media 사실, conversation = note, scope note, history 중복·참조 무결성). 잘못된 요청은 만들어지지 않고
`InternalRunContractViolation`(필드 이름만, 값 없음)이 난다.

### 재전달·deadline·누적 예산

- 같은 run의 재전달은 `InternalRunRequest.forRetry(nextAttempt, nextFence, accumulatedUsage)`로만 만든다.
  request_id·run_id·의미 snapshot·`request_deadline_at`·limits는 그대로이고, attempt는 커져야 하며 usage counter는 줄 수 없다.
- grant·read URL 재발급은 `withAssetAccess(...)`. fingerprint가 바뀌지 않는다.
- status는 본문 없는 GET(`attempt`·`fence_token` query)이라 실행을 시작할 수 없다.

## client (`BrainRunClient`)

| 메서드 | HTTP | 결과 |
|---|---|---|
| `stream(InternalRunRequest)` | `POST /internal/v1/runs/{run_id}/stream` | `Flux<SourceEvent>`. terminal을 낸 뒤 끝난다 |
| `status(RunStatusQuery)` | `GET /internal/v1/runs/{run_id}?attempt=&fence_token=` | `Mono<RunStatusResponse>` |
| `cancel(RunCancelRequest)` | `POST .../cancel` | `Mono<RunCancelAck>` |
| `cleanup(RuntimeCleanupRequest)` | `POST .../cleanup` | `Mono<RuntimeCleanupResponse>` (terminal 저장 뒤에만) |

- Authorization Bearer 하나만 보낸다. 사용자 JWT·쿠키·브라우저 header를 넘길 경로가 없다.
- 재시도·redirect 추적·run 생성을 하지 않는다. 판단은 호출자(coordinator)가 `BrainCallException.kind()`로 한다.
- stream의 attempt·fence는 요청과 다를 수 있다(Brain tail이 새 attempt를 따라간다). 현재 fence 판정은 소비자 몫이다.
- 구독을 취소하면 연결만 닫힌다. Brain 실행을 멈추려면 `cancel`을 부른다.

### timeout·상한

| 설정 | 기본 | 의미 |
|---|---|---|
| `connect-timeout` | 5s | TCP 연결 |
| `request-timeout` | 10s | status·cancel·cleanup 전체 |
| `stream-idle-timeout` | 30s | frame·keep-alive 사이 공백(Brain keep-alive 10s) |
| `stream-deadline-grace` | 3m | `request_deadline_at` 뒤 stream 상한(Brain tail은 deadline + 4 × lease TTL 30s) |
| `max-response-size` | 4MB | JSON 응답·SSE frame 하나 |

deadline + grace가 이미 지난 요청은 보내지 않는다(`DEADLINE_EXCEEDED`). grace는 stream 정리·status 확인 용도다: grace 안에서 회수한 terminal(`RETURN_TERMINAL`·`status`)도 deadline 이후면 1.82 terminal 확정이 `DEADLINE_PASSED`로 버리고 run은 `expireDeadline`의 timeout 실패로 끝난다(deadline은 도착 시각 기준 절대값 — 사용자 결정, r182). 같은 fingerprint 재전달이 새 실행을 만들지 않는다는 점은 그대로다.

credential 발급(ID token)은 `request-timeout` 안에서 끝나야 하고 넘으면 요청 전 `CREDENTIALS_UNAVAILABLE`이다. stream idle·status 응답 시간에는 들어가지 않는다.

### 실패 분류 (`BrainFailureKind`)

범주는 셋이다: **status 조회로 수습**(`isRecoverableByStatusQuery()`, 요청이 닿았을 수 있음), **같은 요청 재전송 가능**(`isSafeToResend()`, Brain record에 닿지 않음), 둘 다 아니면 **영구 실패**.

| kind | 원인 | 범주 |
|---|---|---|
| `CONNECT_FAILED` | 연결 수립 실패 | 재전송 |
| `TRANSPORT_LOST` | 응답·stream 중간 단절, 분류하지 못한 예외 | status |
| `TIMEOUT` | 응답·idle 상한 | status |
| `DEADLINE_EXCEEDED` | 고정 deadline + grace 초과 | status |
| `CAPACITY` | 503 `reason=capacity`, 429(Cloud Run 앞단) — record 생성 전 거부 | 재전송 |
| `UNAVAILABLE` | 그 밖의 5xx | status |
| `REQUEST_ID_BOUND_TO_OTHER_RUN` | 409 binding (`existingRunId()`) | 영구 (Server 버그) |
| `RUN_CONFLICT` | 409 contention (`conflictState()`) | 영구 |
| `INVALID_REQUEST` / `REQUEST_TOO_LARGE` | 400 / 413 | 영구 |
| `AUTH_REJECTED` | 401·403 | 영구 |
| `CREDENTIALS_UNAVAILABLE` | token 발급 실패·초과 | 재전송 |
| `UNEXPECTED_STATUS` | 그 밖의 status(3xx 포함) | 영구 |
| `MALFORMED_RESPONSE` / `MALFORMED_SOURCE` | 계약 밖 응답 / frame(`run.canceled`·다른 run_id 포함) | 영구 |
| `STREAM_ENDED_WITHOUT_TERMINAL` | terminal 없이 종료(취소된 run 등) | status |

재전송 횟수·간격과 deadline 안 여부는 호출자가 정한다. client는 재시도하지 않는다.

오류 message와 필드에는 kind·HTTP status·409의 state·existing_run_id만 들어간다(본문·token·grant·URL 없음, cause 없음).

## 서비스 인증과 설정

```yaml
proovy:
  brain:
    base-url: ${BRAIN_BASE_URL:}          # 비면 client bean을 만들지 않는다
    auth:
      mode: ${BRAIN_AUTH_MODE:shared-token}  # prod profile은 id-token으로 고정
      audience: ${BRAIN_ID_TOKEN_AUDIENCE:}  # id-token: Brain 서비스 URL
      shared-token: ${BRAIN_SHARED_TOKEN:}   # local/test 전용
```

- `id-token`: ADC(Cloud Run 서비스 계정)로 고정 audience의 Google ID token을 발급·캐시한다. base-url은 https만.
  Brain은 audience와 서비스 계정 email을 검증한다(`internal_api_auth_mode=oidc`).
- `shared-token`: Brain `internal_api_shared_token`과 같은 값. prod profile에서는 기동이 실패한다.
- 기존 `internal.api.token`은 Server로 들어오는 내부 API용이고 이 client와 무관하다.
- **미확인(S14)**: 실제 Cloud Run 서비스 계정의 ID token 발급, Brain의 audience·email 검증 통과, IAM invoker, ingress는
  배포 환경에서 확인해야 한다. 이 태스크는 fake provider·fake Brain으로 동작만 검사했다.

## 교체 경계

- 구형 `ProovyAiRequest.authToken`·thread ID·`/stream/v2` 호출 코드는 1.74(#213)에서 이미 제거됐다. adapter·dual-auth를 두지 않았다.
- 기존 `WebClientConfig.webClient` bean(10분 response timeout)은 바꾸지 않았다. Brain client는 자기 WebClient를 따로 만든다.

## 후속 태스크 메모

- **1.78 (snapshot)**: 브라우저 `ConversationRunRequest` → `InternalRunRequest` 매핑. 첫 전달은 `ExecutionUsage.ZERO`.
  history는 공개 Result Block plain text, 현재 input과 같은 user text를 넣지 않는다(생성자가 거부).
- **1.80 (coordinator)**: `stream` 실패 kind별 처리. `isRecoverableByStatusQuery()`면 같은 attempt·fence로 `status`, `isSafeToResend()`면 deadline 안에서 같은 요청 재전송.
  재전달은 `forRetry`. `RUN_CONFLICT`·`REQUEST_ID_BOUND_TO_OTHER_RUN`는 재전달하지 않는다. 현재 fence의 event만 저장한다.
- **1.81 (terminal)**: `SourceEvent`·`StoredTerminal`의 completed·failed payload는 JSON 그대로다. terminal-v2 계약
  (provenance·Verified Solution) 검사는 1.81이 terminal-v2 fixture를 pin해 한다.
- **1.83 (activity)**: activity payload도 JSON 그대로다. activity-v1 fixture pin과 의미 검사는 1.83 몫이다.
- `RunCancelCause`(1.74 entity enum)를 cancel 요청·`run.canceled` cause로 그대로 쓴다. 1.74 enum은 고치지 않고
  `InternalRunWire`의 mix-in으로 wire 철자를 쓴다.
- `withAssetAccess(grant, viewerReadUrl, urls)`의 null은 모두 "기존 값 유지"다. 자격증명을 지우는 API는 없다.
- 필수 배열(history·참조·첨부·claims·requested_*)에 JSON `null`이 오면 wire 읽기에서 거부한다(Brain closed model과 같다).
  코드로 만들 때 생성자의 null은 빈 목록이다.
- `request_deadline_at`은 µs로 자른다(1.75 `wireTime` 관례, Brain pydantic 보존 정밀도).

## 리뷰 r176 후속 메모

- **Brain fixture 추가 요청 (다음 consumer_version)**: fingerprint fixture 27개가 모두 `latex`·`viewer_context`·`selection`을 가진다.
  latex·viewer가 없는 가장 흔한 요청 형태의 canonical·sha256이 바이트 단위로 고정돼 있지 않다(`requests/minimal.json`에는
  expected fingerprint가 없다). 코드 대조로는 같지만, proovy-brain에 그 형태의 `fingerprint/` 케이스를 추가해 달라고 요청하고 다음 pin 때 소비한다.
  Server 사본은 손으로 고치지 않는다.
- **credential 호출 비용 (1.80 이후 측정)**: shared token·캐시된 ID token도 호출마다 `boundedElastic`으로 옮기고
  `GoogleIdTokenCredentials`의 monitor를 잡는다. 정확성 문제는 없다. 동시 실행이 늘면 측정해서 줄인다.
