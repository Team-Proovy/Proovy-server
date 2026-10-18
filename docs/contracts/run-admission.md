# run 접수 — 원자적 요청 접수와 requestId binding (task 1.79, #220)

브라우저 `POST /api/conversations` body(browser-run-v1 `ConversationRunRequest`)를 판정하고, 승인되면 Note·메시지·PENDING run·
최초 차감·첫 이벤트를 한 transaction으로 저장한다. 계약 의미의 원본은 browser-run-v1 artifact(`docs/contracts/browser-run-v1.md`)다.

## 타입 (`com.proovy.domain.run.admission`)

| 타입 | 역할 |
|---|---|
| `RunAdmissionService.admit(userId, JsonNode body)` | 진입점. JSON tree 판정 → 접수 transaction(경합 시 같은 body로 재시도) → `RunAdmission` |
| `RunAdmissionTransaction` | transaction 하나의 판정·저장 |
| `RunAdmission` | commit된 결과: `outcome`(CREATED·REPLAYED)·runId·requestId·noteId·userMessageId·assistantMessageId·공개 status. `REPLAY_FROM_SEQ = 1`, `thenLive()` |
| `RunAdmissionRejectedException` | 스트림 전 거부. `activeRun()`(RUN4091만)·`retryAfterSeconds()`(RUN4291만) |
| `RunAdmissionExceptionHandler` | 거부 → JSON `ApiResponse`(Accept가 SSE여도 JSON), RUN4291은 `Retry-After` |
| `RunRequestBinding` (`run_request_bindings`, V2) | requestId → run, browser 요청 fingerprint 고정 |
| `RunAssetDeletionGuard` | asset hard delete 전 Note 잠금 + run 의존 이력 검사 → ASSET4092 |
| `RunAdmissionProperties` (`proovy.run.admission.*`) | rate limit(`rate-limit.max-requests` 20 / `rate-limit.window` 1m), `max-transactions` 3 |

## 판정 순서 (`admission/decisions.json` precedence)

`401(filter) → RUN4001/4002(JSON tree, DB 전) → existing(같은 사용자·같은 fingerprint면 replay, 아니면 RUN4092)
→ note RUN4041 → references RUN4221 → activeRun RUN4091{activeRunId} → rateLimited RUN4291 → balance RUN4021 → accept`

- "같은 의미"는 browser-run-v1 `BrowserRequestFingerprint`(`browser-request-sha256-canonical-json-v1`)다. `ai_runs.request_fingerprint`는
  internal-run-v1 fingerprint(history·corpus 포함)라 재전송 body로 다시 계산할 수 없어서 `run_request_bindings`에 따로 고정한다.
- binding은 없는데 같은 requestId의 run이나 차감 row가 남아 있으면(Note 삭제로 run이 사라진 옛 ID) RUN4092다.
- RUN4092·RUN4041은 상대 run·Note의 존재·상태를 드러내지 않는 고정 body다.
- rate limit: 최근 window 안 사용자 run(상태·Note 무관, `ai_runs(user_id, created_at)`)이 상한 이상이면 거부. Retry-After는 상한번째로 최근 run이
  window를 벗어날 때까지(초, 올림, 최소 1). replay는 세지 않는다.
- 판정표 23 case 중 22개(인증 제외)를 `RunAdmissionPostgresTest`가 실제 Postgres에서 production 서비스로 돌리고 행 수·잔액을 검사한다.

## 저장 순서와 잠금 (한 transaction)

1. noteId가 있으면 소유자 조건으로 Note 행 `FOR NO KEY UPDATE`(asset 삭제와 같은 순서, Note를 참조하는 자식 insert는 막지 않음) → binding 조회
2. Note 판정(RUN4041) → 잔액 행 잠금(`lockBalanceInCurrentTransaction`, 같은 사용자 접수 직렬화) → **잠금 뒤 `now`를 읽는다**(대기 시간이 created_at·deadline·rate-limit 창을 밀지 않게)
3. noteId가 없으면 Note 생성(제목 = strip 집합으로 공백 정리한 질문 앞 30 code point, 플랜 Note 개수 한도는 적용하지 않음 — 아래 결정 1)
4. `RunSnapshotFactory.create(runId, userId, noteId, request, now)` (1.78) → Active Run 조회 → rate limit
5. user message(`created_at = now`) → assistant placeholder(`now + 1µs`) → `save(toPendingRun(..., now))`
6. `run_asset_dependencies` insert → 그 asset이 아직 같은 Note의 ACTIVE이고 content_version이 snapshot 값과 같은지 `FOR SHARE`로 재검사, VIEWER_FOCUS ⊂ CORPUS 검사(RUN4221)
7. `RequestUsageService.chargeRequest` (1.77, 부족 RUN4021)
8. `run_request_bindings` insert
9. `RunEventStore.appendServerEvent(runId, RUN_CREATED, RunCreatedPayload, now)` (1.80) → seq 1, `last_event_seq = 1`. store가 managed AiRun을 refresh한다

어느 단계든 실패하면 전체 rollback이다. Brain 호출·네트워크 대기는 transaction 안에 없다.

## 경합과 재시도

- 원인 사슬의 SQLState가 unique 위반(23505: `uq_ai_runs_request`·`ux_ai_runs_note_active`·binding PK)이나 transient
  (40001·40P01·55P03·08xxx)면 rollback 뒤 **같은 body·같은 fingerprint**로 새 transaction을 연다(최대 `max-transactions`).
  번역되지 않은 JPA flush 예외도 같은 기준이다. CHECK·데이터 오류 같은 결정적 실패는 재시도하지 않고 바로 던진다. 새 시도는 먼저 commit된 binding을 찾아 replay하거나
  RUN4091/RUN4092로 답하므로 잘못된 재전송이 새 run이 되지 않는다. commit 여부를 모르는 실패 뒤 재시도도 binding 조회가 먼저다.
- noteId 없는 같은 요청 6건 동시 → Note·run·차감 1개, 모두 같은 식별자. 같은 Note 다른 요청 5건 동시 → 1건 접수, 4건 RUN4091(승자 runId), 메시지 2개.
- 접수와 asset 삭제(단건·일괄): 서로 Note 잠금을 기다린다. 삭제가 먼저면 접수 snapshot corpus에서 빠지고, 접수가 먼저면 삭제가 ASSET4092.
  일괄 삭제의 GCS 삭제는 단건처럼 commit 뒤(`afterCommit`)라 Note 잠금을 쥔 채 네트워크를 기다리지 않는다.

## 후속 태스크 인계

- **1.86 (SSE transport·controller)**: controller는 body를 `JsonNode`로 받아 `RunAdmissionService.admit`을 부른다(바인딩·`@Valid` 금지 — 판정이 tree 기반).
  성공이면 `RunAdmission.runId()`의 event를 seq 1부터 replay(`RunEventStore.findAfter(runId, 0, ...)`)하고 `thenLive()`면 live로 잇는다.
  CREATED·REPLAYED 모두 같은 처리다. 거부는 예외를 그대로 던지면 `RunAdmissionExceptionHandler`가 JSON으로 답한다.
  아직 `POST /api/conversations` 경로는 없다.
- **1.83 (dispatch)**: 접수는 PENDING run만 남긴다(attempt 0, fence 없음). dispatch 트리거·큐는 없다 — PENDING 조회(`idx_ai_runs_pending_dispatch`)로 가져간다.
- **1.84 (취소)**: `cancel/cases.json` 판정표를 production 취소 판정기로 다시 돌리는 테스트는 1.84 몫이다(이 PR은 admission만).
- **1.82**: 접수는 `RunCreatedPayload.requestedTasks/Outputs`에 요청 값을 그대로(없으면 `[]`) 싣는다.

## 결정 (r179 사용자 결정)

1. **자동 생성 Note에 플랜 Note 개수 한도를 적용하지 않는다(현행 유지).** FREE `noteLimit = 2`(STANDARD 10, PRO 20)를 noteId 없는 접수
   경로가 우회한다 — S14 재검토. 적용하려면 browser-run-v1 계약 변경(새 code·fixture case)이 필요하다.
2. rate limit 기본값(1분 20건)은 임시값이다 — S14 운영 결정. Note 삭제로 run이 사라지면 그만큼 카운트에서 빠진다.
3. **run이 의존한 asset은 단건·일괄 삭제가 ASSET4092(409)다(임시 동작 수용).** 이전에는 같은 삭제가 commit 시 deferred FK 위반 500이었다.
   화면 문구는 S08, 정식 삭제(DELETING 전환으로 삭제 성공)는 S13으로 인계한다.
4. 자동 Note 제목은 질문 앞 30 code point(현행 유지). Gemini 제목 생성은 접수 transaction 밖이고, 후속 제목 생성 담당 태스크는 **미정**이다.

## 후속 메모

- **1.75 계약 후속 (r179 R2)**: `ConversationRunRequestValidator`는 U+0000을 거부하지 않는다. Postgres text·jsonb가 받지 않아(22P05) 접수가
  매핑되지 않은 500으로 끝난다(재시도는 하지 않음, 부작용 없음). 다음 consumer_version에서 NUL(필요하면 다른 제어 문자)을 RUN4001로 고정한다.
- **S09/S13 (r179 R6)**: 의존성 재검사가 content_version을 비교하므로, 재처리·교체로 version을 올리는 기능이 생기면 겹친 접수는 RUN4221로 거부된다.
  version 변경 경로도 접수와 같은 Note 잠금 순서를 따를지 그때 정한다.
