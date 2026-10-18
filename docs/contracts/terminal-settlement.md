# terminal 확정 — 응답·solution·사용량의 원자적 저장 (task 1.82, #221)

현재 fence의 terminal 하나만 받아 run·assistant message·Verified Solution·page/완료 event·usage 보상을 **한 transaction**에서
저장하고 commit 뒤에만 공개한다. 수락 검증은 1.81 `TerminalAcceptance`, event 저장은 1.80 `RunEventStore`, 보상은 1.77
`RequestUsageService`를 그대로 쓴다. 새 coordinator·두 번째 usage 경로는 없다.

코드: `com.proovy.domain.run.terminal` — `RunTerminalService`(진입점), `TerminalSettlement`(결과), `TerminalReplay`(복원),
`TerminalScopeReader`(transaction 안 scope). schema: `V3__terminal_settlement.sql`.

## 진입점

| 메서드 | 호출자 | 조건부 전환 |
|---|---|---|
| `settle(SourceEvent)` | 1.83 coordinator (stream의 run.completed·run.failed) | `RUNNING|RETRYING` + 현재 attempt·fence + deadline 전 |
| `settle(StoredTerminal)` | 1.85 회수 (status 조회의 저장 terminal). `run.canceled`는 REJECTED(취소 경로 1.84) | 위와 같음 |
| `failByServer(runId, attempt, fence, code, recoverable)` | 1.83·1.85: 거부된 terminal, 실행 소실, 재시도 소진, dispatch 전 snapshot 손상 | `PENDING|RUNNING|RETRYING` + 현재 attempt·fence(PENDING은 `0, null`) |
| `expireDeadline(runId)` | deadline 조회(`idx_ai_runs_deadline`) 주기 작업 — 1.83/1.85 | `PENDING|RUNNING|RETRYING` + `request_deadline_at <= now` |

호출자 transaction 없이 부른다(자기 transaction, 직렬화·교착·연결 실패만 최대 3번 다시 연다). commit 응답 전 연결이 끊겨 실제로는
commit된 뒤 재시도해도 Brain terminal은 source·digest로, Server 실패는 같은 code(·attempt·fence)로 자기 결과를 알아보고 DUPLICATE를 돌려준다.
바깥 transaction 안에서 부르면 진입 때 EntityManager를 flush하고(JPA로 저장 중인 run을 JDBC 판정이 보게) 거기에 참여하며 재시도하지 않는다.

## 조건표 (판정 순서, 모두 run 잠금 뒤 DB 값)

| 순서 | 조건 | Outcome | 저장 |
|---|---|---|---|
| 1 | run_id가 소문자 UUID가 아님, run·Note 없음 | `UNKNOWN_RUN` | 없음 |
| 2 | run이 terminal, 같은 source_event_id(`terminal_source_event_id`)이고 `{type, attempt, fence_token, payload}` digest도 같음 | `DUPLICATE` (lastSeq 반환) | 없음 |
| 2 | run이 terminal, 같은 source_event_id로 다른 결과 (terminal-v2 `changed_outcome`) | `CONFLICTING_DUPLICATE` | 없음 |
| 2 | run이 terminal, 다른 source_event_id (예: deadline 실패 뒤 늦은 완료, 다른 attempt의 같은 고정 문구 실패) | `NOT_ACCEPTING` | 없음 |
| 2' | (Server 실패) 이미 같은 code의 Server 실패로 끝남 — `failByServer`는 같은 attempt·fence까지, `expireDeadline`은 timeout | `DUPLICATE` | 없음 |
| 3 | attempt·fence ≠ 현재 (이전 attempt, 아직 PENDING) | `STALE_FENCE` | 없음 |
| 4 | CANCELING | `NOT_ACCEPTING` | 없음 |
| 5 | `request_deadline_at <= now` | `DEADLINE_PASSED` → 호출자가 `expireDeadline` | 없음 |
| 6 | page_count 없는 PDF dependency, snapshot에 understanding version 없음 | `SCOPE_NOT_READY` (데이터 미준비) | 없음 |
| 7 | 1.81 수락 거부 또는 transaction 재확인 실패 | `REJECTED(reason, field)` | 없음 |
| - | 그 밖 | `COMMITTED` | 전부 |

`isSettled()` = COMMITTED·DUPLICATE. `isObsolete()` = STALE_FENCE·UNKNOWN_RUN·NOT_ACCEPTING·CONFLICTING_DUPLICATE → 그 stream을 버리고
run을 다시 본다. REJECTED·SCOPE_NOT_READY는 run이 그대로라 **호출자가 다음 단계를 정한다**(아래 1.83 인계).

## transaction 경계와 잠금 순서

`notes FOR SHARE → ai_runs FOR UPDATE → assets FOR SHARE → verified_solutions FOR UPDATE(정정 대상) → credit_balance FOR UPDATE`.
접수(1.79: Note `FOR NO KEY UPDATE` → 잔액)·asset 삭제 guard와 같은 Note 우선 순서라 서로 기다릴 뿐 교착하지 않는다. Note를 잡은
동안 Note 삭제 cascade가 solution·asset을 지우지 못한다. **1.84 취소 전환도 Note → run 순서를 따른다.**

COMMITTED 한 transaction의 쓰기:

1. 조건부 전환 `UPDATE ai_runs SET status, disposition/clarification 또는 failure_code/class, completed_at, lease·grant 해제,
   terminal_source_sha256, last_event_seq = last_event_seq + n WHERE run_id AND <위 조건> RETURNING last_event_seq` — 0 row면 rollback.
2. assistant message `PENDING|STREAMING → COMPLETED|FAILED` (조건부, 1 row 필수).
3. 새 Verified Solution insert, 정정이면 대상을 `WHERE status = 'ACTIVE'`로 SUPERSEDED (1 row 필수).
4. `RunEventStore.appendTerminalEvents(runId, last, [page started/completed …, run.completed|run.failed], now)`.
5. `RequestUsageService.settle(userId, requestId, RunTerminalOutcome)` — 정책표(plan 05·07)의 보상 대상이면 한 번.

어느 단계든 실패하면 전부 rollback이고 신호도 없다(실패 주입 테스트: 보상·event·solution insert 실패).

### transaction 재확인 (1.81 `TerminalRecheck`, TOCTOU)

- scope는 사전 조회가 아니라 **잠금 뒤 DB 값**으로 만든다(`TerminalScopeReader`). asset은 `FOR SHARE`라 판정부터 commit까지
  DELETING·version 변경이 끼어들 수 없다. 삭제 transaction이 asset을 잡고 있으면 terminal이 기다렸다가 DELETING을 보고 `SOURCE_SCOPE`로 거부한다.
- `citedAssets`·`referencedSolutions`: scope 자체가 잠금 뒤 같은 transaction 값이라 그것으로 충족된다. cited asset은 이미 읽은 값으로
  방어적으로 한 번 더 비교한다(추가 쿼리 없음).
- `revisedSolutions`: `FOR UPDATE`로 잠그고 ACTIVE인지. 같은 대상의 동시 정정은 하나만 이긴다(Note당 Active Run 하나라 실제로는 겹치지 않는다).
- 다른 Note 참조: scope solution은 `(note_id, assistant_message_id, public_solution_id)`로만 찾으므로 다른 Note의 solution은 scope에
  들어가지 않아 `SOLUTION_REFERENCE_SCOPE`로 거부된다. 다른 Note asset은 dependency가 아니라 `SOURCE_SCOPE`다.

## 저장 형태

**assistant message (COMPLETED)** — `response_pages`·`source_references`·`plain_text`·`content_sha256`은 1.81 `AcceptedCompletion`
그대로(`content_sha256` = `{response_pages, source_references, plain_text}` canonical JSON sha256, 1.81 정의 채택).
`verification_summary`(V3)는 공개 summary 배열(snake_case). FAILED message는 본문이 없다(V1 CHECK).
source snapshot은 message JSON 안에만 있고 asset FK가 없어 rename·DELETING·삭제가 JSON·plain text·hash를 바꾸지 않는다(테스트).

**ai_run_events** (page 경계·terminal은 browser-run-v1 frame payload 모양):

| type | 저장 payload |
|---|---|
| `response.page.started` | `{assistantMessageId, responsePageId, presentation, title?, pageIndex}` |
| `response.page.completed` | `{assistantMessageId, responsePageId, pageIndex}` |
| `run.completed` | **최소 요약** `{assistantMessageId, responseDisposition, clarificationReason?, model, tier}` — 본문 없음 |
| `run.failed` | browser payload 그대로 `{code, reason?, message, recoverable, assistantMessageId}` |

Brain terminal은 `source_event_id`·`attempt`를 남긴다(Server 실패는 없음). 같은 키가 이미 Activity에 쓰였으면 source ID 없이 저장한다(1.80 인계).
page 경계·terminal은 한 commit 시각(`occurredAt`)을 쓴다.

**ai_runs.terminal_source_event_id · terminal_source_sha256** (V3) — 확정한 Brain terminal의 저장 source 키와 `{type, attempt, fence_token, payload}`
canonical JSON sha256. 재전달 판정용이고 Server 실패는 둘 다 NULL. run 행에 있으므로 event가 source ID 없이 저장된 경우에도 changed_outcome을 가른다.

## 보상 (1.77 정책표 그대로)

| 결과 | 순사용량 | 보상 사유 |
|---|---|---|
| COMPLETED substantive (혼합·inconclusive·자료 부재 안내 포함) | 1 | — |
| COMPLETED clarification_only / unsupported_only | 0 | CLARIFICATION_ONLY / UNSUPPORTED_ONLY |
| FAILED verification / budget / attachment / infrastructure (deadline timeout 포함) | 0 | VERIFICATION_FAILED / BUDGET_EXCEEDED / INVALID_ATTACHMENT / INFRASTRUCTURE_FAILURE |

run 잠금으로 terminal 전환이 직렬화되므로 **답변 확정과 실패 보상이 함께 생기지 않는다**(완료↔Server 실패, 완료↔CANCELING, 완료↔deadline 경합 테스트).
ledger PK(request_id)가 마지막 방어다. clarification run은 COMPLETED라 다음 turn history에 남는다(1.78 결정 2).

## 공개·복원 경계

- commit 전에는 다른 연결에 message 본문·page/완료 event·solution이 보이지 않고 신호가 없다. commit 뒤 `RunEventSignals`가 `(runId, lastSeq)`를
  한 번 알린다(1.80). rollback은 신호가 없다.
- 신호 유실·broadcaster 장애 뒤: `RunEventStore.findAfter(runId, cursor, …)` + `TerminalReplay.findCompleted(runId)`로 같은 frame을 만든다.
  `TerminalReplay.completedPayload(storedSummary, message, available)`가 terminal-v2 pair·browser-run-v1 stream의 `run.completed` payload와
  같다(테스트가 모든 pair와 `substantive-completed` stream으로 비교).

## 후속 인계

- **1.83 coordinator**
  - terminal은 `settle(SourceEvent)`. Activity는 1.80 `appendActivity`.
  - `REJECTED`·`SCOPE_NOT_READY`는 run이 그대로다. 권장: 같은 attempt·fence로 `failByServer(…, INTERNAL_ERROR, true)`(보상 포함).
    공개할 결과를 검증하지 못했으므로 답을 확정하지 않는다. REJECTED의 `rejection()`·`rejectionField()`만 로그에 남기고 payload는 남기지 않는다.
  - `DEADLINE_PASSED`면 `expireDeadline`. deadline 조회 주기 작업도 `expireDeadline`이다(CANCELING은 대상이 아니다).
  - deadline + grace(1.76 `stream-deadline-grace`)는 stream 정리·status 확인 용도다. grace 안에서 받은 terminal도 deadline 이후면 `DEADLINE_PASSED`다.
  - 실행 소실·재시도 소진은 `failByServer`. `runtime_cleanup_at` 예약(Brain runtime cleanup)은 이 경로가 하지 않는다 — 1.83/1.85가 terminal 뒤 따로 기록한다.
- **1.84 취소**: CANCELING 전환과 CANCELED 확정은 1.84가 한다. 잠금 순서 Note → run을 따르고, `CANCELING`이면 이 서비스는 어떤 terminal도
  받지 않는다(늦은 run.completed 차단). PENDING 취소 보상과 terminal 보상은 ledger PK로 한 번만이다.
- **1.85 회수**: `settle(StoredTerminal)` — 재전달이면 DUPLICATE. `run.canceled` 저장 terminal은 1.84 경로로 넘긴다.
  grace 안에서 회수(`RETURN_TERMINAL`·status)한 terminal도 deadline 이후면 `DEADLINE_PASSED` → `expireDeadline`이다(Brain은 COMPLETED여도 Server는 timeout).
- **1.86 SSE·대화 조회**: page·run.failed는 저장 payload 그대로, run.completed는 `TerminalReplay.completedPayload`로 hydration한다.
  `available`은 read-time(asset DELETING·DELETED면 false). 대화 조회의 `verificationSummary`는 `chat_messages.verification_summary`(NULL = `[]`),
  실패 message의 `failure{code, reason, message, recoverable}`는 그 run의 `run.failed` event payload, status 응답의 `failure`도 같은 payload에서 읽는다.
  `ChatMessage` JPA 엔티티에는 V3 열을 매핑하지 않았다(1.74 소유, 필요하면 1.86이 추가형으로 매핑).

## 검증하지 않은 부분·결정

- **provenance `content_version` = asset content sha256 (사용자 결정, r182)**: pin 303a9e4 Brain 구현과 같다. `TerminalScopeReader`가
  `brainContentVersion`에 asset sha256을 넣는다. sha256이 없는 asset은 `SCOPE_NOT_READY`다. 정수 version은 dependency·현재 version 비교가 맡는다.
- **Page Understanding version**: Viewer Focus asset은 `viewer_context.page_understanding_version`, 나머지는 `policy_snapshot.asset_versions`
  값(접수 시 고정). S09/S10의 asset별 version이 생기면 그때 바꾼다.
- **deadline은 도착 시각 기준 절대값 (사용자 결정, r182)**: deadline이 지난 뒤 도착한 완료는 저장하지 않는다(`DEADLINE_PASSED`, 유예 없음).
  경계에서는 run 잠금 순서가 승자를 정한다. 1.76 grace 서술도 같은 의미로 고쳤다(`internal-run-v1.md`·`BrainRunClient`).
- **V3 schema (1.82 점유, 후속은 V4부터)**: `chat_messages.verification_summary`(summary는 claim 수에 비례해 16KB event 상한을 넘을 수 있다)와
  `ai_runs.terminal_source_event_id`·`terminal_source_sha256`.
- **scope 규칙 중복**: page_count 없는 PDF 규칙·저장 claim coverage 해석은 1.81 `ScopedAsset.of`·`ScopedSolution.of`와 같은 규칙을 JDBC row에
  다시 적었다(그 factory는 엔티티를 받는다). 규칙을 바꾸면 두 곳을 함께 바꾼다.
- 실제 Brain 왕복·S09 업로드 자산·다중 Server 인스턴스(S14)는 검증하지 않았다. 테스트는 fixture를 테스트 asset ID로 묶은 seeded run이다.
