# run durable event 저장소 (task 1.80, #218)

`ai_run_events`는 S07의 유일한 이벤트 저장소다. Brain Activity·Server 소유 event·terminal event와 replay가 모두
`com.proovy.domain.run.event.RunEventStore`를 쓴다. 별도 coordinator나 두 번째 저장소는 없다. schema 변경은 없다(V1 그대로).

## 타입

| 타입 | 역할 |
|---|---|
| `RunEventStore` | append(Activity·Server·terminal)·조회(`findAfter`·`lastSeq`) |
| `SourceActivities` | Brain source `activity.*` → activity-v1 검사 → browser camelCase payload (`DurableActivity`) |
| `ActivityAppendResult` | Activity 수락 결과(`Outcome`) |
| `TerminalRunEvent` | terminal transaction이 저장하는 page 경계·run.completed/failed/canceled |
| `StoredRunEvent` | commit된 event 하나. `toFrame()` → `BrowserRunFrame`, `cursor()` → `RunEventCursor` |
| `RunEventSignals` | commit 뒤 `(runId, lastSeq)` 깨우기 신호 (payload 없음) |

## seq·순서·dedup 불변조건

- 모든 append는 `ai_runs` row를 `FOR UPDATE`로 잠근 뒤 `last_event_seq = last_event_seq + n ... RETURNING`으로 seq를 배정하고
  같은 transaction에서 insert한다. run별 seq는 1부터 빈틈없이 +1이다.
- **commit 순서 = seq 순서.** 같은 run의 append가 row 잠금으로 직렬화되므로, seq N이 보이는 snapshot에는 N 이하가 모두 보인다.
  `seq > cursor` catch-up은 아직 commit되지 않은 앞 seq를 건너뛰지 않는다.
- rollback되면 seq 증가도 되돌아간다(같은 seq가 다음 append에 다시 배정된다).
- seq는 Server 수락 순서다. Brain source 순서나 `occurred_at`과 같다고 가정하지 않는다.
- dedup 키는 `(run_id, source_event_id)`다. 같은 source event 재전달은 row·seq·`last_event_seq`를 늘리지 않는다(DUPLICATE).
  128자를 넘는 Brain ID(brain-source-v1은 256자까지 허용, V1 column은 128자)는 결정적 `sha256:<hex>` 키로 저장한다.
- frame `occurredAt`은 `ai_run_events.created_at`이다. Activity는 source `occurred_at`(µs), Server event는 호출자가 넘긴 시각이다.

## Brain Activity 수락 — `appendActivity(SourceEvent)` (1.83 coordinator)

자기 짧은 transaction(REQUIRED)에서 실행한다. Brain HTTP/SSE를 기다리는 동안 transaction을 열어 두지 않는다. 판정 순서:

| 순서 | 조건 | Outcome | 저장 |
|---|---|---|---|
| 1 | activity-v1 계약 위반, run.completed/failed, run_id가 UUID 아님, 저장 한도 초과 | `MALFORMED` | 없음 |
| 2 | run 없음 | `UNKNOWN_RUN` | 없음 |
| 3 | event attempt·fence ≠ run 현재 값 | `STALE_FENCE` | 없음 |
| 4 | 같은 source_event_id 존재 | `DUPLICATE`(같은 내용)·`CONFLICTING_DUPLICATE` | 기존 row 유지, 기존 seq 반환 |
| 5 | 상태: RUNNING·RETRYING은 모두, CANCELING은 열린 Activity의 `activity.canceled`만, PENDING·terminal은 없음 | `NOT_ACCEPTING` | 없음 |
| 6 | started 없는 후속·두 번째 started·kind 불일치·끝난 Activity의 후속 (browser-run-v1 S6) | `OUT_OF_ORDER` | 없음 |
| - | 그 밖 | `APPENDED` | 새 seq |

- `isStored()`: APPENDED·DUPLICATE. `isStreamObsolete()`: STALE_FENCE·UNKNOWN_RUN → 그 stream 소비를 멈추고 run을 다시 본다.
- 예외는 저장 불변조건 위반(일어나지 않아야 함)에서만 난다.
- payload는 pin된 Brain `contracts/activity.py`(303a9e4)와 같은 규칙으로 검사한다: (type, kind)별 닫힌 필드, kind별 label allowlist,
  Python strip 집합·code point 길이, StrictInt, 측정 progress(`checked ≤ total`·`processed ≤ total`), target 중첩 금지,
  `limited_pages_unresolved`·`no_relevant_content` page 규칙, message의 URL·code fence·traceback·제어 문자 금지.
  code·stdout·step 판정·percent 같은 필드는 들어갈 자리가 없다. 저장·공개되는 값은 browser payload뿐이다.
- Brain parser와 차분 비교했다(fixture 변형 2,754건): 차이는 2^63 이상 정수를 Java가 거부하는 것뿐이다(fail-closed).

## Server 소유 event — `appendServerEvent(runId, ServerFrameType, payload, occurredAt)` (MANDATORY)

| type | 호출자 | 조건 |
|---|---|---|
| `RUN_CREATED` | 1.79 접수 transaction | PENDING이고 첫 event(seq 1). AiRun을 JPA로 저장했으면 store가 flush한다 |
| `RUN_CANCELING` | 1.84 취소 transaction | 같은 transaction에서 CANCELING으로 바꾼 뒤, run당 한 번 |

조건이 맞지 않으면 `IllegalStateException`(호출자 transaction rollback). page 경계·run.canceled는 이 경로가 아니다.
payload는 1.75 record(`RunCreatedPayload` 등)나 JSON object이고 Spring ObjectMapper로 직렬화한다.
native UPDATE라 영속성 context의 `AiRun.lastEventSeq`는 갱신되지 않는다. seq는 반환값이나 `lastSeq`로 읽는다.

## terminal event — `appendTerminalEvents(runId, lastEventSeq, events, occurredAt)` (MANDATORY, 1.82·1.84)

terminal 뒤에는 `last_event_seq`를 바꿀 수 없으므로(DB trigger) 호출자의 terminal 전환 UPDATE 하나에서 seq를 함께 배정한다.

```sql
UPDATE ai_runs SET status = 'COMPLETED', ..., last_event_seq = last_event_seq + :n
WHERE run_id = :runId AND attempt = :attempt AND fence_token = :fence AND status IN ('RUNNING', 'RETRYING')
RETURNING last_event_seq
```

그 반환값과 n개 `TerminalRunEvent`를 넘기면 `last-n+1 .. last`로 insert한다. 검사:

- run이 terminal이고 `last_event_seq`가 넘긴 값과 같으며 앞 seq가 모두 저장되어 있다.
- 마지막은 상태에 맞는 terminal 하나(COMPLETED→run.completed, FAILED→run.failed, CANCELED→run.canceled).
- 그 앞에는 run.completed일 때만 `pageStarted`/`pageCompleted` 쌍이 pageIndex 0..k-1 순서로, 같은 `responsePageId`로 온다.
- page·run.completed payload와 canonical page의 일치, payload 최소 요약 구성은 1.82가 정한다(replay 1.86이 message로 채운다).
- run.completed·run.failed는 Brain terminal의 source_event_id·attempt를 남길 수 있다(`runCompleted(payload, sourceEventId, attempt)`).

## 조회·알림 (1.86 replay/live)

- `findAfter(runId, afterSeq, limit)`: commit된 event를 seq 오름차순으로. cursor 없는 replay는 `afterSeq = 0`.
- `lastSeq(runId)`: `RunEventCursor.resolve(runId, lastSeq, header, query)`의 범위 판정용.
- `RunEventSignals.subscribe(runId, listener)`: commit 뒤 `(runId, lastSeq)`. rollback·중복은 신호가 없다.
  신호는 유실될 수 있다 → **구독을 먼저 걸고** cursor 이후를 DB에서 catch-up하고, 신호마다 다시 cursor 이후를 읽는다.
  listener는 commit thread에서 동기로 불리므로 빨리 반환한다. 다른 Server 인스턴스의 commit은 신호가 없다(다중 인스턴스는 S14).
- heartbeat는 저장 type이 없다. source comment는 1.76 client가 버리고 browser heartbeat는 1.86이 연결에서만 보낸다.

## 후속 메모

- **Activity 의미 검사 소유:** `internal-run-v1.md` 메모는 "activity-v1 pin과 의미 검사는 1.83"이라고 적었지만 원본 task 1.80이
  kind별 제한 payload와 fake source 검증을 요구해 이 저장소가 소유한다. 1.83은 `appendActivity`만 호출한다.
- **CANCELING:** Brain wind-down의 `activity.canceled`만 받는다. 표식이 유실되면(stream 단절) 열린 Activity가 남을 수 있다.
  replay에서 run terminal 뒤 열린 Activity를 끝난 것으로 그리는 것은 reducer(S08)·1.86에서 정한다.
- **저장 한도:** payload `jsonb::text` 16384 byte를 넘는 Activity(예: 긴 asset ID 200 page)는 MALFORMED로 버린다.
  Brain 쪽 상한과 맞출 필요가 생기면 다음 consumer_version에서 다룬다.
