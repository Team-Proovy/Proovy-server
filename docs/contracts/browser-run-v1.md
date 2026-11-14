# 브라우저 run API 계약 browser-run-v1 (task 1.75, #214)

이 문서는 인계용 안내다. 계약의 판정 기준은 아래 artifact이고, 이 문서나 OpenAPI가 아니다.

## 어디에 무엇이 있나

| 무엇 | 위치 |
|---|---|
| 계약 원본(SSOT) — schema·정상/거부 예제·판정표·CATALOG | proovy-brain `tests/fixtures/contracts/browser-run-v1/` |
| Server가 pin한 version | `src/test/resources/contracts/brain/PIN.json` — commit `6b237b8`(2.07, proovy-brain #198), `browser-run-fixtures-v1.1` |
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

> 반영(1.86): 접수 SSE·재접속 SSE·상태·대화 조회 경로 — [`browser-run-sse.md`](browser-run-sse.md). 취소 경로는 1.84.

## Phase 2 확장 browser-run-fixtures-v1.1 (task 2.15, Brain 2.07)

pin: proovy-brain `6b237b8`(PR #198). `PIN.json`(browser-run-fixtures-v1.1)·`PIN-terminal-v2.json`(terminal-fixtures-v2.1)·
`PIN-activity-v1.json`(activity-fixtures-v1.1)을 같은 commit으로 맞췄다. 계약 설명은 proovy-brain `docs/plan/28`과 사본
`browser-run-v1/CATALOG.md`의 "Phase 2 확장"이다. 기존 fixture는 byte 그대로라 v1 응답 처리는 바뀌지 않는다.

| wire | Server 처리 | 저장 위치 |
|---|---|---|
| terminal `run.completed`의 `response_kind`·`assurance_scope`·`solution_checks`(snake_case, 함께 오거나 모두 없음) | `CompletionExtension.parse` — Brain `browser_solution_check.py`와 같은 규칙(단계 종류별 결과, ✓ 단계 수식, hide_answer 전 단계 withheld, hide_solution 단계 없음, 배지 집계, 재설명 not_applicable·검산 없음, 안내 응답 검산 없음) | `chat_messages.response_kind`·`assurance_scope`·`solution_checks`(V6) |
| verified 검산 | 모호하지 않을 때만(새 artifact 1·verified 검산 1·기존 참조 0) 새 Verified Solution과 짝지음. 연결 키가 wire에 없다 | 공개 view 그대로(`disclosure` 포함) → `verified_solutions.verification_summary.solution_check`. `step_marks`는 artifact chain mark 그대로 |
| browser `run.completed`·대화 조회 assistant의 `responseKind`·`assuranceScope`·`solutionChecks`(camelCase, null key 채움) | `TerminalReplay.completedPayload` 한 함수 — SSE replay·live·대화 조회가 같은 값 | — |
| Activity Kind `read_back`(label `reading_back_problems`, 완료 `problems[]`) | `SourceActivities` — 번호·본문·보기(0 또는 2~5)·그림 설명만, 탭·줄바꿈 밖 제어 문자 거부 | `ai_run_events` (다른 Activity와 같음, replay 대상) |

- Server가 Brain보다 엄격한 규칙: 재설명은 새 Verified Solution을 만들지 않는다, verified 검산 수 ≤ 새 artifact + 기존 참조,
  `step_index`는 0부터 빈틈없이(2.06 규칙).
- Read-back 완료 Activity만 저장 상한 256KiB(V6 `ck_ai_run_events_payload`), 나머지 event는 16KB 그대로.
- 작업 문서(2026-11-11 결정)와 다른 저장 위치 — 근거와 확인 필요 항목은 PR #247:
  미검증·검증 대상 아님 단계 검산은 `response_pages` result block이 아니라 `chat_messages.solution_checks`,
  단계는 `verified_solutions.step_marks`가 아니라 `verification_summary.solution_check`(disclosure 적용 view라 영구 mark를 덮지 않는다),
  `evidence_code`는 Brain이 보내지 않아 저장하지 않는다.
- 확장 없는 terminal(2.12·2.14 이전 Brain)은 `response_kind = 'standard'`, 배지·검산 NULL이고 browser 응답에 세 key가 없다.
- 독립 답·`check_reason`·`evidence_code`·problem id는 Brain이 source에도 싣지 않으므로 Server도 저장하지 않는다.

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
