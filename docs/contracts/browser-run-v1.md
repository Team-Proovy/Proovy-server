# 브라우저 run API 계약 browser-run-v1 (task 1.75, #214)

이 문서는 인계용 안내다. 계약의 판정 기준은 아래 artifact이고, 이 문서나 OpenAPI가 아니다.

## 어디에 무엇이 있나

| 무엇 | 위치 |
|---|---|
| 계약 원본(SSOT) — schema·정상/거부 예제·판정표·CATALOG | proovy-brain `tests/fixtures/contracts/browser-run-v1/` |
| Server가 pin한 version | `src/test/resources/contracts/brain/PIN.json` — commit `303a9e4`, `browser-run-fixtures-v1` |
| 검증된 사본과 갱신 절차 | `src/test/resources/contracts/brain/` (`README.md`) |
| Java 계약 타입 | `com.proovy.domain.run.contract` |
| 소비 검사 | `src/test/java/com/proovy/domain/run/contract/` |

경로·오류 code·판정 순서·cursor·stream 규칙(S1~S7)은 Brain `CATALOG.md`가 정리한다. 단계 2에서 합의한
결정 D1~D10은 그 artifact와 #214에 기록되어 있다.

## Java 타입과 artifact 대응

| artifact | Java |
|---|---|
| `ConversationRunRequest` (closed) | `ConversationRunRequest` + `ConversationRunRequestValidator` (RUN4001/RUN4002) |
| 요청 fingerprint | `BrowserRequestFingerprint`, `CanonicalJson` (internal-run-v1 canonical JSON) |
| event id / cursor `{runId}:{seq}` | `RunEventCursor` (`resolve` → replay 시작점 또는 RUN4003/RUN4004) |
| durable frame / Server 소유 payload | `BrowserRunFrame`, `ServerFrameType`, `RunCreatedPayload` 등 |
| 상태·취소·대화 조회 응답 | `RunStatus`, `RunCancelResult`, `ConversationPage` 등 (`ApiResponse`로 감싼다) |
| 오류 envelope | `ErrorCode.RUN4001`~`RUN4291`, RUN4091만 `ApiResponse.failure(code, ActiveRunConflict)` |
| 공개 상태 투영 | `PublicRunStatus.from(AiRunStatus)` (RETRYING → RUNNING) |
| 고정 값(limits·heartbeat·frame version) | `BrowserRunContract` |

## 아직 없는 것

이 태스크는 계약 타입과 소비 검사만 추가했다. 아래는 후속 태스크가 이 계약대로 구현한다.

- controller·접수 transaction·SSE 연결·replay 저장소 (1.79·1.86 등)
- Brain 내부 client (1.76)
- Front 화면 (S08)

OpenAPI에는 component schema만 등록했고 경로는 없다.

## 후속 메모 (PR #229 리뷰 r175-server)

- **1.79 (접수·취소):** `admission/decisions.json`과 `cancel/cases.json` 판정표 fixture를 production admission·cancel
  판정기로 다시 돌리는 테스트를 둔다. 지금 `BrowserRunDecisionTableContractTest`는 테스트 안의 참조 판정이어서
  production 구현이 같은 순서로 판정하는지까지는 보장하지 않는다.
- **schema와 Java 판정이 갈리는 경계 (fixture 밖):** `viewerFocus.page`가 `2.0`처럼 정수값 실수이거나 int 범위를 넘으면
  schema는 통과하지만 Java 판정은 RUN4001이다. 브라우저(`JSON.stringify`)는 이런 값을 보내지 않는다.
  다음 consumer_version에서 schema에 `maximum: 2147483647`과 정수값 실수 정책을 넣고 fixture로 고정한다.
- **`RunStatus.cancelable`·`ActiveRunSummary.status`:** 상태에서 파생되는 값인데 생성자가 막지 않는다. 응답을 만드는
  구현(1.79·1.81)이 `PublicRunStatus.isCancelable()`로 채우고, activeRun에는 terminal 상태를 넣지 않는다. 필요하면 factory로 강제한다.
- **OpenAPI 등록과 springdoc 비활성:** `BrowserRunContractOpenApi`는 `SpringDocConfigProperties` bean을 주입받는다. springdoc을 끄는 profile에서는
  bean이 없어 기동이 실패할 수 있다. 그런 설정을 도입할 때 `ObjectProvider` 또는 `@ConditionalOnBean`으로 바꾼다.
- **wire 값 원천 정리:** 아래는 같은 wire 값을 두 곳에서 정의한다. 계약 의미의 결함은 아니므로 후속 구현 태스크에서 enum 파생이나
  대응 테스트로 묶는다.
  - `ServerFrameType` ↔ `AiRunEventType`(serverOwned)
  - `CancelCause` ↔ `RunCancelCause`(대응 테스트는 있음)
  - 신규 계약 enum의 `WireValue` 미구현

## 계약을 바꿀 때

1. Server 이슈에서 의미 변경을 합의한다. 계약 의미의 소유자는 Server다.
2. proovy-brain artifact를 새 `consumer_version`과 `change_history` 항목으로 올리고 merge한다.
3. Server는 사본을 새 commit에서 다시 복사하고 `PIN.json`을 갱신한다(`README.md` 절차). Java 타입과 검사를 함께 고친다.
4. Front(S08)도 같은 commit과 version을 pin한다.

pin을 올리지 않고 사본이나 Java 상수만 고치면 `BrainContractMirrorTest`가 실패한다.
