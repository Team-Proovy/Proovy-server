# run 취소 — 명시적 cancel·CANCELED 확정·상태별 사용량 (task 1.84, #224)

브라우저 `POST /api/ai-runs/{runId}/cancel`(browser-run-v1 `cancel/cases.json`)의 구현이다. 계약 의미는 바꾸지 않았다.
상태 조회 `GET /api/ai-runs/{runId}`는 1.86(`RunReadService.status`) 그대로다(`cancelable` = PENDING|RUNNING).

코드: `com.proovy.domain.run.cancel` — `RunCancelService`(진입점), `RunCancelStore`(잠금·조건부 전환), `RunCancelFinalizer`(CANCELED 확정),
`RunCancelProperties`(`proovy.run.cancel.*`). 경로는 1.86 `BrowserRunController`에 추가했다. **schema 변경 없음**(V1 cancel 열·trigger 그대로, migration 추가 없음 — V5는 1.85 `recovery_count`).

## 판정 순서 (`cancel/cases.json` precedence)

`401(filter) → body 있음 RUN4001(run 조회 전, `{}`·어떤 필드든) → run 접근 RUN4041 → 상태`

- body는 원문 `String`으로 받는다(Content-Type 무관). 비어 있지 않은 body면 거부한다.
- RUN4041: 없는 run·타인 run·대문자/형식이 다른 runId·Note DELETING을 구분하지 않는 같은 body(1.86 `accessibleRun` 재사용, 잠금 뒤 다시 확인).

| 잠금 뒤 상태 | 응답 `{status, outcome}` | 같은 transaction의 쓰기 |
|---|---|---|
| PENDING | `CANCELED`, `accepted` | `PENDING∧attempt=0 → CANCELED`, message CANCELED, `run.canceled`, 보상 `PENDING_CANCELED` |
| RUNNING·RETRYING | `CANCELING`, `accepted` | `→ CANCELING`(현재 attempt·fence 조건, `cancel_requested_from` = 이전 상태, grant 폐기), `run.canceling` |
| CANCELING | `CANCELING`, `already_canceling` | 없음 (확정 확인을 한 번 깨운다) |
| COMPLETED·FAILED·CANCELED | 그 상태, `already_terminal` | 없음 |

잠금: `notes FOR SHARE → ai_runs FOR UPDATE → credit_balance`(1.82와 같은 Note 우선 순서). 직렬화·교착·연결 실패만 최대 3번 다시 연다.
coordinator claim은 `SKIP LOCKED` + `status='PENDING' AND attempt=0`이라 PENDING 취소와 하나만 이긴다(claim이 이기면 RUNNING 취소 경로).

## CANCELED 확정 (`RunCancelFinalizer`)

취소 commit 직후 한 번, 그 뒤 `sweep-interval`마다 DB의 CANCELING run(오래된 요청부터 `batch-size`)을 확인한다. 재시작·다른 인스턴스의 취소도
이어받고, 확정은 `status='CANCELING'` 조건부라 여러 인스턴스가 겹쳐도 하나만 이긴다. run별 동시 확인은 하나다.

한 번의 확인 = 현재 attempt·fence·cause로 Brain `cancel`(멱등). 처음이면 취소 전달, 그 뒤로는 ack `state`가 정리 상태 조회다.

| ack state | 판정 |
|---|---|
| `terminal` (run.canceled 저장, 또는 취소 전 끝난 결과) | CANCELED |
| `absent` (record 없음 = 미시작) | run lease가 만료됐고 이 인스턴스에서 실행 중이 아니면 CANCELED. 살아 있는 worker가 아직 stream을 보낼 수 있으면 기다린다. 확정 transaction에서 run을 잠근 뒤 lease 만료를 **다시** 본다(Brain 호출 동안 늦은 heartbeat가 갱신했으면 기다림) |
| `canceling`·`executing`·호출 실패(5xx·timeout·409 등)·Brain client 없음 | 기다린다 |
| (모든 경우) `cancel_requested_at + cleanup-timeout` 경과 | WARN 뒤 CANCELED (안전 timeout) |

- timeout 시각은 **DB `now()` 기준**이다(`cancel_requested_at = now()`로 쓰고, 확인 SELECT가 남은 시간을 DB에서 계산). 인스턴스 사이 JVM 시계 차이가 확정 시점을 바꾸지 않는다.
- 이미 timeout이 지난 run은 **Brain을 부르지 않고** 확정한다. 남은 시간이 client `request-timeout`보다 짧으면 Brain 응답을 남은 시간까지만 기다린다(hang인 Brain이 확정을 늦추지 않음).

확정 transaction(하나): `CANCELING → CANCELED`(lease·grant 해제), message CANCELED, `run.canceled{cause, message}`, 1.77 `settle(canceled(from, cause))`,
`RunClaimStore.reserveRuntimeCleanup`(attempt ≥ 1, 지연은 `proovy.run.coordinator.runtime-cleanup-delay`, coordinator가 꺼진 인스턴스는 0s).
cleanup 예약이 같은 transaction이라 commit 뒤 응답 유실·process 사망·예약 SQL 오류로 `runtime_cleanup_at`이 NULL로 남는 CANCELED는 없다(예약이 실패하면 확정 전체가 rollback되고 다시 확인한다).
commit 뒤 `RunCoordinator.release(runId)` — commit 여부를 모르는 재시도가 이미 CANCELED를 보더라도 부른다. coordinator 패키지는 고치지 않았다(공개 hook만).

- **실패 행**: 확인이 예외로 끝난 run은 `failure-backoff`(기본 30s) 동안 주기 확인 SQL에서 빠진다(`run_id <> ALL(…)`) — 계속 실패하는 오래된 행이
  oldest-first batch를 막지 않는다. 일시 DB 오류는 WARN, 그 밖(불변조건 위반: 열린 assistant placeholder 없음·seq 불일치 등)은 ERROR, counter
  `proovy.run.cancel.check_failures{kind=transient|invariant}`. 같은 run의 취소 API 재요청(`wake`)은 backoff와 무관하게 곧바로 확인한다.
  불변조건 위반으로 계속 실패하는 run은 timeout 확정도 같은 transaction을 타므로 CANCELING에 남고 슬롯·Note 자리를 쥔다 — **운영 수동 개입 대상**(ERROR·counter로 감지).
- counter `proovy.run.cancel.confirmed{reason=brain_terminal|brain_absent|cleanup_timeout}`. 주기 SELECT 자체가 실패하면 처음 한 번 WARN, 회복 시 INFO(DB 장애 중 로그 반복 없음).

- 확정 전까지 run은 비terminal이라 **전역 슬롯(1.83 DB 수)과 Note Active Run 자리(`ux_ai_runs_note_active`)를 계속 차지한다**. 같은 Note 새 요청은 RUN4091.
- CANCELING 동안: Activity는 열린 Activity의 `activity.canceled`만(1.80), terminal·`failByServer`·`expireDeadline`은 NOT_ACCEPTING(1.82), deadline sweep 대상 아님.
  worker는 stream을 마저 소비해 wind-down 표식을 저장하고 status `canceling`/`run.canceled`로 OBSOLETE로 끝난다(1.83).
- 스레드: 내부 executor 2개(`runCancel-*`), Spring `Executor`·`TaskScheduler` bean 아님. Brain 호출 중 transaction·row lock 없음.
  stop 때 `inFlight`·backoff를 비워 stop → start(context restart) 뒤에도 run이 거부되지 않는다.
- lifecycle phase는 web server start/stop·graceful shutdown보다 앞(`DEFAULT_PHASE − 4096`): 요청을 받기 전에 시작하고 요청이 멈춘 뒤에 멈춘다.
  그 밖 구간(executor 없음)의 `wake`는 버려지고 주기 확인(다른 인스턴스·재시작 포함)이 줍는다. **`enabled=false`만으로 구성한 배포에서는 그런 run이 CANCELING에 남는다** — 점검·테스트 전용.

## 사용량 (1.77 정책표 그대로)

| 취소 | 순사용량 |
|---|---|
| PENDING에서 (어떤 cause든) | 0 — `PENDING_CANCELED` 한 번 |
| RUNNING·RETRYING에서 `user_requested` | 1 — 차감 유지(Brain 정리 확인·timeout 무관) |

ledger PK(request_id)로 PENDING 취소 보상과 deadline·Server 실패 보상이 겹쳐도 한 번이다(경합 테스트).

## 설정 (`proovy.run.cancel.*`, `@DefaultValue` — application.yaml 변경 없음)

| key | 기본 | 의미 |
|---|---|---|
| `enabled` | true | 주기 확인. 끄면 취소 직후 한 번만 확인(점검·테스트용) |
| `sweep-interval` | 2s | CANCELING 재확인 주기(run당 Brain cancel 1회) |
| `cleanup-timeout` | 2m | Brain 정리 확인 상한. 근거: Brain wind-down 15s, 끊긴 wind-down sweep은 writer lease 만료 뒤, Brain tail 4 × lease TTL 30s. **S14 확정** |
| `batch-size` | 20 | 주기 한 번 확인 수 |
| `failure-backoff` | 30s | 확인이 실패한 run을 주기 확인에서 빼 두는 시간 |

**부하(S14)**: 주기 확인은 leader·lease 게이팅 없이 모든 인스턴스가 돈다 — N 인스턴스 × batch × (1/`sweep-interval`)만큼 Brain cancel(멱등)이 나간다
(기본 20행·2s면 인스턴스당 최대 초당 10회). 정확성 문제는 없고(확정은 조건부), coordinator lease 보유자 전용 sweep 또는 `FOR UPDATE SKIP LOCKED` 분할은
S14 부하 인수에서 정한다.

## 반복 요청 제한 (1.79 재사용 — 사용자 결정: 추가 상한은 후속 태스크)

이 태스크 범위는 1.79 RUN4291(`proovy.run.admission.rate-limit`, 기본 20건/1분, 임시값) 재사용뿐이다. 테스트로 고정한 성질:
새 requestId마다 세고, **취소·실패·clarification으로 순사용량 0이 된 run도 센다**(취소로 우회 불가), 같은 requestId 재조회(replay)는 세지 않고,
다른 사용자에 영향 없음.

**후속 태스크(S14 수치와 함께)** — task 84 문구 대비 남은 차이와 제안:

1. 반복 실패/clarification 남용을 "구분"하는 별도 제한이 없다 — 모든 새 요청을 같은 창으로 센다. 무료 결과(보상된 run) 누적 상한(예: 1시간 N건)이 없어
   20건/분 안에서는 무료 Brain 실행을 계속 만들 수 있다.
2. "입력·준비 상태 보완 요청" 구별이 없다 — 보완 요청도 같은 창으로 센다(더 엄격한 제한이 없으므로 부당하게 막히지도 않는다).
3. 제안: 보상된 run을 browser fingerprint(`run_request_bindings`) 기준으로 세는 추가 상한(같은 fingerprint 반복만 제한 → 보완 요청은 fingerprint가 달라 제외).
   browser-run-v1 오류 code는 RUN4291 그대로 쓸 수 있다. 창·상한 수치는 기존 20건/1분과 함께 S14에서 정한다.

## 1.85 회수 결정표 중 1.84 소유 항목

1.85(#225, `docs/contracts/run-coordinator.md` stale 회수 절)가 1.84에 넘긴 두 항목의 판정이다.

- **`FINAL_CANCEL_PENDING`(Brain canceling인데 Server RUNNING|RETRYING)** — 1.84 흐름에서는 생기지 않는다. Brain `cancel`을 부르는 곳은
  `RunCancelFinalizer` 하나이고, 대상은 `status = 'CANCELING'` 행만 읽어 고른다(취소 API는 CANCELING을 commit한 **뒤** `wake`, sweep도 같은 조건).
  CANCELING → RUNNING|RETRYING 전이는 없으므로 Server가 Brain보다 늦게 취소 상태가 되는 순서가 없다. 그래서 1.84는 이 경우를 수습하지 않는다
  (coordinator 패키지 변경 금지이기도 하다). 이 counter·WARN이 보이면 Server 밖에서 Brain cancel이 들어온 것(운영 수동 호출, S13 `asset_deleting` 연결 오류 등)이고,
  run은 1.85 규칙대로 deadline sweep이 timeout으로 끝낸다.
- **`FINAL_CHECK`·재전달 소진 실패 직전 Brain cancel** — **이 PR에서 하지 않는다.** 실패시키는 곳이 `RunExecution`(coordinator 패키지, 이 태스크에서 수정 금지)이고,
  internal-run-v1 `CancelCause`(`user_requested`·`asset_deleting`·`budget_exhausted`)에 Server 실패를 뜻하는 값이 없어 계약 추가가 먼저다.
  그동안 슬롯 over-commit은 1.85 r185-3대로 Brain 자체 상한에 기댄다. 후속(S13, Brain cleanup·lease 만료 sweep과 함께): coordinator가 실패 직전
  `BrainRunClient.cancel`(1.84가 쓰는 client 그대로, 멱등)을 best-effort로 부르고, 응답과 무관하게 `failByServer`한다 — 확정을 Brain 응답에 묶지 않는다.
- 1.85 merge 뒤 확인: 회수 후보·인수는 RUNNING|RETRYING만이고 heartbeat 조건은 CANCELING을 포함하므로, 인수 worker가 도는 동안 취소된 run은
  lease가 살아 있어 `absent` 확정이 기다리고(위 표), worker는 OBSOLETE로 끝난다. 1.84 코드 변경은 필요 없었다.

## 검증

- `RunCancelPostgresTest`(실제 Postgres + 실제 HTTP + 실제 `BrainRunClient`·loopback 가짜 Brain, coordinator 끔): `cancel/cases.json` 9 case를 production
  endpoint로(fixture body 일치, 404 body 동일), 대문자 ID·DELETING Note·401, PENDING 취소(payload = `canceled-pending` fixture, 보상 1회, Brain 미호출, 슬롯 해제),
  동시 cancel 6건, PENDING 취소 ↔ claim 8회, PENDING 취소 ↔ deadline·Server 실패, RUNNING 취소(늦은 terminal·Activity·deadline 차단, RUN4091, 슬롯 계산,
  Brain cancel 인자, 확정 뒤 차감 유지·cleanup 예약), cleanup timeout(정리 미확인·Brain 503), absent + lease, 완료 ↔ 취소, deadline ↔ 취소, SSE 단절 ≠ 취소,
  반복 요청 제한, absent 확정의 잠금 뒤 lease 재확인(Brain 호출 중 heartbeat 갱신), timeout이 지난 run의 Brain 미호출·hang Brain의 남은 시간 상한,
  확정 실패 run의 backoff(확정·cleanup 예약 전체 rollback, 재요청 `wake`는 즉시 확인). 두 class는 `@DirtiesContext`로 끝난 context의 sweep을 닫는다.
- `RunCancelCoordinatorPostgresTest`(실제 coordinator worker, 슬롯 1): wind-down `activity.canceled` 저장, worker OBSOLETE 뒤에도 CANCELING이 슬롯을 잡아
  다음 run이 PENDING, Brain 정리 확인 뒤 CANCELED → 다음 run dispatch, 취소 run 재전달 없음. event 순서는 `canceled-running` fixture와 같다.

## 범위 밖·인계

- **1.87**: 경로 `POST /api/ai-runs/{runId}/cancel`(body 없음, `ApiResponse<RunCancelResult>`). 실제 Brain cancel 왕복·wind-down 시간 실측, timeout 수치 확인.
- **1.85**: CANCELING run(lease 만료 포함)은 이 경로 소유다. 1.85 회수는 RUNNING|RETRYING만 다룬다.
- **S13**: `asset_deleting` cause 취소 연결(이 store의 `cancelPending`·`markCanceling`이 cause를 받는다), Brain runtime `cleanup` 호출,
  `RunCancelStore.inTransactions`는 호출자 transaction이 열려 있으면 거기 참여하고 **재시도하지 않는다**(RunTerminalService와 같은 규칙) — 재시도가 필요하면
  S13 쪽 transaction 밖에서 부른다. commit 뒤 `RunCancelFinalizer.wake`도 S13이 부른다.
  1.85 `FINAL_CHECK`·재전달 소진 실패 직전 best-effort Brain cancel(위 1.85 절, `CancelCause` 계약 추가 필요).
- **후속 태스크(S14 수치와 함께)**: 보상된 run의 같은 fingerprint 반복 추가 상한(위 반복 요청 제한 절). 수치 확정 대상: `cleanup-timeout` 2m,
  `sweep-interval` 2s, 1.79 rate limit 20건/1분, 추가 상한의 창·건수.
- **S08**: 정지 버튼·draft 유지(서버는 자동 재실행·queue·draft 전송을 만들지 않는다).
- Brain cancel 409(fence 불일치·다른 epoch)는 기다림으로 처리해 timeout 뒤 확정된다(새 attempt 금지 전제상 발생하지 않아야 한다).
- 후속 정리: 재시도 SQLSTATE 판정(`isRetryable`)이 RunTerminalService·RunAdmissionService·RunCancelStore 세 곳에 있다 — 공통 helper(추가형 파일)로 모으는 후속을 둔다.
  `cancel_cause` 해석은 `RunCancelCause.Converter`를 재사용한다.
