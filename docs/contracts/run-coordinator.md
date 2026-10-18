# run coordinator — 브라우저 독립 claim·전역 슬롯·bounded dispatch (task 1.83, #222)

접수(1.79)가 commit한 PENDING run을 브라우저 연결과 무관하게 단일 Server coordinator가 claim해 전역 유한 슬롯 안에서 Brain에 위임하고,
Activity는 1.80 `RunEventStore.appendActivity`, terminal은 1.82 `RunTerminalService`로 넘긴다. 저장·사용량·event 처리기는 새로 만들지 않았다.

코드: `com.proovy.domain.run.coordinator`. schema: `V4__run_coordinator_lease.sql`(`run_coordinator_leases`), `V5__run_recovery_count.sql`(`ai_runs.recovery_count`, 1.85).
stale 실행 회수(task 1.85, #225)는 아래 [stale 회수](#stale-회수-task-185-225) 절이다.

## 타입

| 타입 | 역할 |
|---|---|
| `RunCoordinator` (SmartLifecycle) | coordinator lease, claim loop, 전역 슬롯, heartbeat, deadline sweep, stale 회수(1.85), 종료 정리. hook: `isExecuting`·`release`·`isLeader`·`slotsInUse` |
| `RunExecution` | run 하나의 worker(stream 소비·실패 kind 처리·status 수습). `Mode`: `DISPATCH`(claim 직후) / `RECOVER`·`FINAL_CHECK`(stale 인수, 1.85) |
| `RunClaimStore` | DB 경계(coordinator lease, claim, run lease, RETRYING 표시, lease 만료, cleanup 예약, deadline 조회, stale 조회·인수·Brain 시작 증거). 각 호출은 짧은 단일 문장 또는 자기 transaction |
| `ClaimedRun` | 배정된 `(runId, attempt, fence)` |
| `RunAssetAccessProvider` | 전달마다 grant·read URL 발급. 기본값은 `RunAssetAccess.NONE`(S09/S10이 교체) |
| `RunCoordinatorProperties` | `proovy.run.coordinator.*` |

## 실행 설정 (`proovy.run.coordinator.*`)

| 키 | 기본 | 의미 |
|---|---|---|
| `enabled` | `false` (`RUN_COORDINATOR_ENABLED`) | 켜면 Brain client(`proovy.brain.base-url`)가 필요하다. 없으면 기동 실패 |
| `instance-id` | host-무작위 | lease owner |
| `max-concurrent-runs` | 4 | 전역 실행 슬롯. `brain-capacity.instances × per-instance`(3×4)를 넘으면 기동 실패 |
| `poll-interval` | 1s | PENDING 조회 주기. 슬롯이 반환되면 곧바로 한 번 더 조회한다 |
| `heartbeat-interval` / `lease-ttl` | 15s / 90s | lease-ttl ≥ 3 × heartbeat (docs/plan/09 시작값) |
| `deadline-sweep-interval` | 5s | `expireDeadline` 주기 |
| `min-dispatch-remaining` | 5s | 남은 deadline이 이보다 짧으면 claim하지 않는다(sweep이 timeout으로 끝낸다) |
| `redelivery.max-resends` / `max-status-checks` | 6 / 3 | 같은 attempt 재전달 합계 / 연속 status 조회 실패 한도 |
| `redelivery.initial-backoff` / `max-backoff` | 1s / 15s | 지수 backoff |
| `recovery.interval` / `max-recoveries` / `batch` | 5s / 3 / 8 | stale 회수 주기 / run 하나의 인수 허용 횟수(`recovery_count` 누적) / 주기당 인수 시도 수 (1.85) |
| `runtime-cleanup-delay` | 0s | terminal 뒤 `runtime_cleanup_at` 예약 지연 |
| `shutdown-timeout` | 8s | 종료 전체 대기 예산(control·worker 합계). Cloud Run 종료 유예(10s) 안에 끝나야 한다 — S14 배포 값과 함께 확정 |

스레드는 coordinator 내부 executor 두 개다(`runCoordinator-control-*` 2개, `runCoordinator-worker-*` 슬롯 수만큼; 대기열 길이는 semaphore가 0..max로 제한한다).
Spring `TaskScheduler`/`Executor` bean으로 노출하지 않는다. bean으로 두면 Boot 기본 `taskScheduler`·`applicationTaskExecutor`가 물러나 전역 기본값이 바뀐다.
SecurityConfig·SSE·hikari·`spring.task.*`·`server.*`는 바꾸지 않았다. DB connection은 heartbeat·claim·append마다 잠깐만 쓰고 stream 대기 중에는 쥐지 않는다.

## 단일 coordinator와 전역 슬롯

- **coordinator lease**: heartbeat마다 `run_coordinator_leases('run')`을 조건부 upsert한다(같은 owner면 갱신, 만료됐으면 인수). 보유자만 claim·deadline sweep을 하고,
  나머지 인스턴스는 passive로 남아(WARN 로그, gauge `leader=0`) 주기마다 다시 시도한다. claim transaction이 lease를 `FOR SHARE`로 다시 확인하므로
  lease가 넘어간 뒤의 claim은 없다. 종료하면 worker 대기 전에 lease를 먼저 내놓는다. lease 갱신 자체가 실패하면 `leader=false`로 내려 claim을 멈추고,
  run heartbeat는 따로 계속한다. **Cloud Run 다중 인스턴스에서도 coordinator는 하나만 활성이다.**
  다중 coordinator 동시 활성(공용 admission)은 S14 검증 범위이고 지금은 지원하지 않는다.
- **빈 슬롯** = min(로컬 semaphore, `max-concurrent-runs` − DB의 비terminal 실행 수(`attempt >= 1 AND status IN (RUNNING, RETRYING, CANCELING)`, **lease 무관**)).
  다른 인스턴스·이전 프로세스·heartbeat가 끊긴 실행도 Brain에서 아직 돌 수 있으므로 terminal(완료·deadline sweep·회수·취소 확정)이 될 때까지 슬롯을 차지한다.
  배포 교체 때도 새 coordinator가 Brain 동시 실행을 max 이상으로 올리지 않는다. 고아 run의 상한은 deadline sweep이 보장한다(CANCELING은 1.84 확정).
  dispatch는 한 번에 하나만 돈다(주기 dispatch와 슬롯 반환 뒤 dispatch가 같은 DB 수로 각자 claim하지 않게). 이미 돌고 있으면 기다리지 않고 재실행만 표시해 control thread(heartbeat·sweep)를 묶지 않는다.
  슬롯이 없으면 run은 PENDING으로 남는다(무한 in-memory queue 없음).
- Brain 503 capacity·429는 슬롯의 대체물이 아니다. 같은 요청을 bounded 재전송하는 transient다.

## claim과 attempt·fence

```sql
-- coordinator lease FOR SHARE 확인 뒤
WITH picked AS (SELECT run_id FROM ai_runs WHERE status = 'PENDING' AND request_deadline_at > now() + :minRemaining
                ORDER BY created_at, run_id LIMIT 1 FOR UPDATE SKIP LOCKED)
UPDATE ai_runs SET status = 'RUNNING', attempt = 1, fence_token = :newFence, started_at = now(), lease_owner = :owner,
       lease_expires_at = now() + :ttl, heartbeat_at = now()
FROM picked WHERE run_id = picked.run_id AND status = 'PENDING' AND attempt = 0 RETURNING run_id
```

- **attempt는 이 claim에서만 0 → 1로 오른다.** 재전달은 언제나 같은 attempt·fence·요청이다. Brain(run-decisions-v2)에서 같은 attempt 재전달은
  START(record 없음 = 미시작) / OBSERVE(같은 실행에 다시 붙기) / RETURN_TERMINAL만 만든다. 더 높은 attempt는 Brain이 새 graph epoch로 adopt하므로 보내지 않는다.
  그래서 소실된 graph를 새로 시작하지 않는다.
- 요청은 `RunSnapshotRestorer.delivery(run, access)`로만 만든다. 저장 snapshot, `request_deadline_at`, run 행 누적 usage(단조, DB trigger)를 그대로 쓴다.
  재전달·재조회가 deadline이나 예산을 초기화하지 않는다. 복원이 실패하면(`RunSnapshotIntegrityException`) `failByServer(INTERNAL_ERROR)`로 끝낸다.
- `SKIP LOCKED`이라 취소 transaction(1.84)이 잡은 PENDING run은 건너뛴다. 잠그는 것은 run 행 하나라 Note → run 순서와 교착하지 않는다.

## worker (RunExecution)

stream은 transaction 없이 소비한다(`toStream` + 멈춤 신호 `takeUntilOther`). frame마다 짧은 자기 transaction을 연다.

| 수신 | 처리 |
|---|---|
| `activity.*` | `appendActivity`. `isStreamObsolete()`(STALE_FENCE·UNKNOWN_RUN)면 소비를 멈춘다. NOT_ACCEPTING(CANCELING 등)은 계속 소비하고, MALFORMED·OUT_OF_ORDER는 WARN만 남긴다 |
| `run.completed`·`run.failed` | `settle(SourceEvent)` → 아래 settlement 처리 |

| settlement | 처리 |
|---|---|
| COMMITTED·DUPLICATE | `runtime_cleanup_at` 예약 → SETTLED |
| DEADLINE_PASSED | `expireDeadline` |
| REJECTED·SCOPE_NOT_READY | reason·field만 로그(payload 없음) → `failByServer(INTERNAL_ERROR, true)` |
| STALE_FENCE·NOT_ACCEPTING·UNKNOWN_RUN·CONFLICTING_DUPLICATE | OBSOLETE (다른 경로가 끝냈다) |

실패 kind(1.76 `BrainFailureKind`) 처리:

| 범주 | 처리 |
|---|---|
| `isSafeToResend` (CONNECT_FAILED·CAPACITY·CREDENTIALS_UNAVAILABLE) | `RUNNING|RETRYING → RETRYING`, `retry_count + 1` → backoff → 같은 요청. 다음 stream의 첫 frame에서 `RUNNING` |
| `isRecoverableByStatusQuery` (TRANSPORT_LOST·TIMEOUT·DEADLINE_EXCEEDED·UNAVAILABLE·STREAM_ENDED_WITHOUT_TERMINAL) | 같은 attempt·fence로 `status`: **absent**면 Brain 시작 증거가 없을 때만 같은 요청 재전송(#215), 증거가 있으면 실행 소실 → `failByServer(PROVIDER_ERROR)`(1.85, 아래), **같은 epoch executing**이면 같은 요청으로 다시 붙고, **terminal**이면 `settle(StoredTerminal)`, `run.canceled`·canceling·다른 epoch면 OBSOLETE, fingerprint 불일치는 `failByServer(INTERNAL_ERROR)`. status 실패는 `max-status-checks`까지 backoff |
| 그 밖(영구) | `failByServer(INTERNAL_ERROR, true)`. `REQUEST_ID_BOUND_TO_OTHER_RUN`은 ERROR 로그(Server 버그) |

- 재전송·재연결은 `max-resends` 합계와 고정 deadline 안에서만 한다(다음 backoff 뒤 시각이 deadline을 넘으면 시작하지 않는다). 한도를 다 쓰면 deadline 전에는
  `failByServer(PROVIDER_ERROR, true)`, 지났으면 `expireDeadline`(timeout)이다. 둘 다 1.82가 보상을 한 번 처리한다. **transport 종료는 성공이 아니다.**
- **재전달마다 자격증명 재발급**: 첫 전달 뒤 모든 재전달(capacity·status 뒤 재연결) 전에 `RunAssetAccessProvider.accessFor`로 grant·read URL을 새로 받아
  `withAssetAccess`로 갈아 끼운다(fingerprint 불변). 이전 전달의 값을 다시 보내지 않는다.
- **일시 DB 오류**(Activity·terminal 저장 중 `TransientDataAccessException`·`RecoverableDataAccessException`·`DataAccessResourceFailureException`·`CannotCreateTransactionException`):
  그 stream을 끊고 같은 재전달 한도 안에서 backoff 뒤 status 수습에 합류한다. Brain은 같은 요청에 OBSERVE·RETURN_TERMINAL로 답하고 Activity는 source ID로 dedup된다.
  수습 중에도 DB를 쓸 수 없거나 다른 예외면 LOCAL_ERROR다.
- deadline sweep은 `PENDING|RUNNING|RETRYING AND request_deadline_at <= now`를 `expireDeadline`으로 끝내고(슬롯 대기 중 PENDING 포함), cleanup을 예약하고,
  로컬 worker를 멈춘다. run별로 예외를 잡아 한 run의 실패가 batch를 막지 않는다.

## 슬롯 해제 조건

로컬 permit은 worker가 끝나면 **항상** 반환하고 곧바로 dispatch를 한 번 더 돌린다. 반환 직후 그 thread가 아직 비지 않았어도 다음 run은 거부되지 않고
pool 대기열에서 잠깐 기다린다(대기열 길이는 permit 수가 막는다). pool이 종료 중이라 거부되면 run을 실패시키지 않고 lease를 만료시켜 회수 대상으로 남긴다.
**전역 슬롯은 permit이 아니라 DB 비terminal 수로 센다** — 아래 OBSOLETE·STOPPED·LOCAL_ERROR에서 run이 아직 비terminal이면 terminal까지 슬롯을 계속 차지한다.

| 결과 | run 상태 |
|---|---|
| SETTLED | terminal(이 worker 또는 이미 같은 결과) |
| OBSOLETE | 다른 경로(취소·deadline·다른 fence)가 맡음. heartbeat를 멈추고 lease는 건드리지 않는다(TTL에 만료). CANCELING이면 슬롯은 1.84 확정까지 유지된다 |
| STOPPED | 종료(SHUTDOWN)·lease 상실(LEASE_LOST)·`release` 호출(FINISHED_ELSEWHERE). 진행 중으로 남는다. SHUTDOWN은 lease를 즉시 만료시킨다 |
| LOCAL_ERROR | 수습할 수 없는 로컬 오류(일시 DB 오류는 먼저 status 수습으로 합류, 아래). lease를 즉시 만료시킨다(회수 대상) |

heartbeat가 0 row면(terminal·fence 교체·lease 인수) 그 worker를 LEASE_LOST로 멈춘다. heartbeat 조건은 `RUNNING|RETRYING|CANCELING` + attempt·fence·owner다.
종료할 때는 하나의 예산(`shutdown-timeout`) 안에서 control task(heartbeat·claim)가 끝나기를 기다리고, coordinator lease를 내놓은 뒤 worker를 멈춘다. 그래야 늦게 도착한 heartbeat가 만료된 lease를 되살리지 않는다. Brain 실행은 취소하지 않는다.

## 관측

Micrometer(`proovy.run.coordinator.*`): gauge `slots.in_use`·`slots.max`·`leader`, counter `claims`·`redeliveries{kind}`·`status_checks{result}`·
`executions{result}`·`server_failures{code}`·`deadline_expired`·`heartbeat.lost`·`errors{stage}`. tag는 고정 enum 이름뿐이다.
로그에는 runId·attempt·kind·outcome·SQL 예외 class 이름만 남는다. payload·grant·URL·fence 외 요청 값은 남기지 않는다.

## 1.84·1.85 인계 (hook·제약)

- **1.84 취소**
  - PENDING 취소: claim이 `SKIP LOCKED` + `status = 'PENDING' AND attempt = 0` 조건부라 취소 transaction과 겹쳐도 하나만 이긴다.
  - RUNNING/RETRYING → CANCELING: worker는 계속 stream을 소비한다. `appendActivity`가 열린 Activity의 `activity.canceled`만 받고, terminal은 NOT_ACCEPTING → OBSOLETE로 worker가 끝난다.
    Brain cancel 뒤 stream이 terminal 없이 끝나면 status가 canceling·`run.canceled`를 보여 OBSOLETE가 된다.
  - **OBSOLETE 규칙(하나)**: worker가 끝나면 heartbeat가 멈추고 lease는 TTL에 만료된다. 그러나 CANCELING run은 비terminal이라 **전역 슬롯은 1.84가 CANCELED를 확정할 때까지 유지된다**
    (Brain 정리 전 슬롯을 풀지 않음). 그래서 1.84는 coordinator heartbeat·lease에 의존하지 않는다. worker를 먼저 끝내려면 `RunCoordinator.release(runId)`를 부른다.
  - Brain cancel 호출·CANCELED 확정·`run.canceled` 저장 terminal 처리는 1.84 몫이다. 1.84도 terminal 뒤 `RunClaimStore.reserveRuntimeCleanup`을 부른다.
- **1.85 회수** — 구현됨, 아래 [stale 회수](#stale-회수-task-185-225) 절. CANCELING run(lease 만료 포함)은 1.84 소유라 회수 대상이 아니다.
- **R7 자격증명**: S10 전에는 grant·viewer read URL을 넘기지 않는다(`RunAssetAccess.NONE`). Viewer Focus의 Page Understanding cache miss에서 Brain이 URL을 요구하면
  Brain terminal(실패)로 끝난다. S09/S10이 `RunAssetAccessProvider`를 교체하고 전달마다 새로 발급한다.
- **runtime cleanup**: `runtime_cleanup_at`(attempt ≥ 1인 terminal run)만 예약한다. Brain `cleanup` 호출·retention은 S13이다.

## stale 회수 (task 1.85, #225)

STOPPED(종료·lease 상실)·LOCAL_ERROR·이전 프로세스·다른 인스턴스가 남긴 run은 `RUNNING|RETRYING` + 만료 lease다. leader coordinator가
`recovery.interval`마다 이런 run을 같은 attempt·fence로 인수해 **status부터** 수습한다. graph 자동 resume은 없다(ADR 0008).

### 후보와 인수

```sql
-- 후보 (idx_ai_runs_stale_lease)
SELECT run_id FROM ai_runs WHERE status IN ('RUNNING','RETRYING') AND attempt >= 1
  AND lease_expires_at <= now() AND request_deadline_at > now() ORDER BY lease_expires_at LIMIT :batch
-- 인수: coordinator lease FOR SHARE 확인 뒤 (같은 transaction)
UPDATE ai_runs SET lease_owner = :owner, lease_expires_at = now() + :ttl, heartbeat_at = now(), recovery_count = recovery_count + 1
WHERE run_id = :id AND status IN ('RUNNING','RETRYING') AND attempt >= 1 AND lease_expires_at <= now() AND request_deadline_at > now()
RETURNING attempt, fence_token, recovery_count
```

- **attempt·fence를 바꾸지 않는다**(새 attempt 금지 전제 유지). 동시 인수·heartbeat 재개·terminal·취소 전환과 겹치면 조건부 UPDATE 하나만 이긴다.
- 이 인스턴스에 그 run의 worker가 있으면(`isExecuting`, heartbeat만 늦음) 건너뛴다. 인수 worker도 **로컬 permit**을 잡고(없으면 다음 주기),
  전역 슬롯은 그 run이 이미 비terminal 수로 차지하고 있어 새로 세지 않는다. 비PENDING run의 worker는 이 주기 작업만 띄우므로 같은 run의 로컬 worker가 겹치지 않는다.
- **CANCELING은 고르지 않는다**(1.84 소유). deadline이 지난 run은 deadline sweep이 timeout으로 끝낸다(회수 조회 없음).
- 인수한 worker는 일반 worker와 같아 heartbeat·`release`·deadline sweep 중단·종료 정리를 그대로 받는다.

### 상태별 결정 (`RunExecution` `RECOVER`)

| Brain status (같은 attempt·fence 조회) | 결정 |
|---|---|
| `terminal` (`run.completed`·`run.failed`), fingerprint = `ai_runs.request_fingerprint` | `settle(StoredTerminal)` — **새 stream·provider 실행 없음, snapshot 복원도 없음**. COMMITTED·DUPLICATE면 cleanup 예약, DEADLINE_PASSED면 `expireDeadline` |
| `terminal` `run.canceled`·`canceling`·다른 epoch | OBSOLETE (취소 경로 1.84·다른 fence). run은 그대로 |
| `executing`, 같은 epoch | 저장 snapshot에서 같은 요청을 복원해 다시 붙는다(Brain OBSERVE). 이후 일반 worker 규칙 |
| `absent`, Brain 시작 증거 없음 | **명확한 미시작**(Brain record는 Postgres 영속이라 absent = 등록된 적 없음) → 같은 요청이 Brain의 첫 START |
| `absent`, Brain 시작 증거 있음 | **실행 소실** → 재전송 없이 `failByServer(PROVIDER_ERROR)`(1.82가 한 번 보상). counter `execution_lost` |
| fingerprint 불일치 | `failByServer(INTERNAL_ERROR)` |
| status 실패 `max-status-checks`회 | 조회 한도 소진 → deadline 전 `failByServer(PROVIDER_ERROR)`, 지났으면 `expireDeadline` |

- **Brain 시작 증거** = 이 worker가 stream frame을 받았거나 `ai_run_events`에 이 run·attempt의 Brain source event(`source_event_id IS NOT NULL`)가 있다.
  이 규칙은 1.83 worker의 status 수습에도 적용된다(frame을 받은 뒤의 absent에 같은 요청을 보내면 Brain이 새 graph를 START한다).
- **미시작 PENDING**(attempt 0)은 회수 대상이 아니다. claim만 첫 전달을 만든다.

### 한도 (재시작·재조회가 초기화하지 않는다)

| 한도 | 저장 위치 | 소진 시 |
|---|---|---|
| 요청 deadline | `request_deadline_at`(불변) | `expireDeadline` timeout. 회수 후보에서도 빠진다 |
| 같은 attempt 재전달 `max-resends` | run 행 `retry_count`(단조)부터 이어서 센다 | 실패·보상 |
| 연속 status 실패 `max-status-checks` | worker 단위(인수마다 새로 센다 — 인수 횟수가 상한) | 실패·보상 |
| 인수 `max-recoveries` | `recovery_count`(V5, 단조 trigger) | 초과한 인수는 `FINAL_CHECK`: status 한 번으로 저장 terminal만 회수하고, executing·absent면 재전송 없이 `failByServer(PROVIDER_ERROR)` |

usage 예산(`used_*`)은 1.83과 같다(Brain 회신 계약 전까지 0, 새 attempt 금지 전제로 Brain record 단조 병합에 의존).
`canceling`·다른 epoch처럼 OBSOLETE로 끝난 인수는 lease가 TTL에 만료되면 다시 인수되고 `recovery_count`가 오른다 — 결국 deadline sweep이 끝낸다.

### 경합 방어 (기존 조건부 경계 재사용)

- 중복 recovery worker: 인수 UPDATE가 하나만 이긴다. 진 쪽(다른 인스턴스의 늦은 worker)은 owner가 바뀌어 heartbeat 0 row → LEASE_LOST로 멈춘다.
  같은 fence라 두 worker가 terminal을 동시에 내도 1.82 run 잠금·조건부 전환이 하나만 확정한다(DUPLICATE·NOT_ACCEPTING).
- deadline: 회수 후보·인수 조건에 deadline 전이 들어 있고, 회수한 terminal도 deadline 뒤면 1.82가 `DEADLINE_PASSED`로 버린다(사용자 결정 r182).
- cancel: CANCELING run은 후보가 아니고, 인수 뒤 1.84가 CANCELING으로 바꾸면 1.82가 terminal을 NOT_ACCEPTING → OBSOLETE. heartbeat 조건은 CANCELING도 포함하므로 lease는 1.84 확정 전까지 그대로다.
- 늦은 이전 attempt: attempt가 오르지 않으므로 없다. 끊긴 이전 stream의 frame은 구독이 끊겨 Server에 닿지 않고, 닿더라도 source ID dedup·조건부 terminal이다.

### 관측

counter `recoveries{result}`(TAKEN·LIMIT_EXCEEDED·NOT_TAKEN·NO_PERMIT), `recoveries.finished{result}`(인수 worker 종료 = 로컬 permit 반환), `execution_lost`,
`status_checks{result}`, `errors{stage=recovery}`. 로그: `stale run taken over: runId, attempt, recoveryCount, mode`, `brain status: runId, attempt, mode, state`,
`server failure: … reason=execution_lost|recoveries_exhausted|status_*`. payload·grant·URL·fence는 남기지 않는다.

### 1.87·S13 인계

- **재사용 경계**: `RunClaimStore.findStale`·`takeOver`(같은 attempt·fence 인수 + `recovery_count`), `RunExecution` `RECOVER`/`FINAL_CHECK`(status 먼저 → 1.82 `settle(StoredTerminal)`·`failByServer`·`expireDeadline`),
  `hasBrainEvents`(미시작 vs 실행 소실 판정). 새 terminal 처리기를 만들지 말고 이 경계를 부른다.
- **S13**: 회수·실패로 끝난 run도 `runtime_cleanup_at`을 예약한다(attempt ≥ 1). Brain `cleanup` 호출·retention·자산 deletion 연동은 S13이다.
  `FINAL_CHECK`가 executing 상태에서 실패시킨 run은 Brain record가 executing으로 남을 수 있다(cleanup은 executing을 REJECT) — S13이 Brain lease 만료 sweep·cleanup 재시도와 함께 다뤄야 한다.
- **1.87**: 상태별 결정표·한도는 위와 같다. fault injection은 실제 Postgres + 실제 `BrainRunClient` + loopback 가짜 Brain(`RunRecoveryPostgresTest`)이다.

## 검증하지 않은 부분

- 실제 Brain·Cloud Run 왕복, 다중 인스턴스 lease 인수 실측, 슬롯·timeout·회수 주기 수치(S14). 실제 Brain 프로세스 crash 뒤 Brain lease 만료 처리와
  status 응답(가짜 Brain은 absent·executing·terminal을 각본으로 준다), Cloud Run 종료 유예 안에서의 SIGTERM 회수(테스트는 같은 JVM의 `stop()`·`start()`). 테스트는 실제 `BrainRunClient` + loopback 가짜 Brain(HTTP/SSE) + 실제 Postgres다.
- **usage counter(`used_*`)**: Brain이 usage를 회신하는 계약(terminal·status·activity 어디에도 usage가 없다)이 생길 때까지 Server는 `used_*`를 0으로 둔다.
  누적 예산은 Brain record의 단조 병합에 의존하고, **새 attempt를 발급하지 않는다는 것**(1.83·1.85 공통 불변 전제)이 이를 지킨다. 회신 경로를 신설할지는 S07 밖 후속 결정이다.
