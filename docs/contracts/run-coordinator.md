# run coordinator — 브라우저 독립 claim·전역 슬롯·bounded dispatch (task 1.83, #222)

접수(1.79)가 commit한 PENDING run을 브라우저 연결과 무관하게 단일 Server coordinator가 claim해 전역 유한 슬롯 안에서 Brain에 위임하고,
Activity는 1.80 `RunEventStore.appendActivity`, terminal은 1.82 `RunTerminalService`로 넘긴다. 저장·사용량·event 처리기는 새로 만들지 않았다.

코드: `com.proovy.domain.run.coordinator`. schema: `V4__run_coordinator_lease.sql`(`run_coordinator_leases`).

## 타입

| 타입 | 역할 |
|---|---|
| `RunCoordinator` (SmartLifecycle) | coordinator lease, claim loop, 전역 슬롯, heartbeat, deadline sweep, 종료 정리. hook: `isExecuting`·`release`·`isLeader`·`slotsInUse` |
| `RunExecution` | claim한 run 하나의 worker(stream 소비·실패 kind 처리·status 수습) |
| `RunClaimStore` | DB 경계(coordinator lease, claim, run lease, RETRYING 표시, lease 만료, cleanup 예약, deadline 조회). 각 호출은 짧은 단일 문장 또는 자기 transaction |
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
| `runtime-cleanup-delay` | 0s | terminal 뒤 `runtime_cleanup_at` 예약 지연 |
| `shutdown-timeout` | 30s | 종료 시 control·worker를 기다리는 시간 |

스레드는 coordinator 내부 executor 두 개다(`runCoordinator-control-*` 2개, `runCoordinator-worker-*` 슬롯 수만큼, queue 없음).
Spring `TaskScheduler`/`Executor` bean으로 노출하지 않는다. bean으로 두면 Boot 기본 `taskScheduler`·`applicationTaskExecutor`가 물러나 전역 기본값이 바뀐다.
SecurityConfig·SSE·hikari·`spring.task.*`·`server.*`는 바꾸지 않았다. DB connection은 heartbeat·claim·append마다 잠깐만 쓰고 stream 대기 중에는 쥐지 않는다.

## 단일 coordinator와 전역 슬롯

- **coordinator lease**: heartbeat마다 `run_coordinator_leases('run')`을 조건부 upsert한다(같은 owner면 갱신, 만료됐으면 인수). 보유자만 claim·deadline sweep을 하고,
  나머지 인스턴스는 passive로 남아(WARN 로그, gauge `leader=0`) 주기마다 다시 시도한다. claim transaction이 lease를 `FOR SHARE`로 다시 확인하므로
  lease가 넘어간 뒤의 claim은 없다. 종료하면 lease를 즉시 만료시킨다. **Cloud Run 다중 인스턴스에서도 coordinator는 하나만 활성이다.**
  다중 coordinator 동시 활성(공용 admission)은 S14 검증 범위이고 지금은 지원하지 않는다.
- **빈 슬롯** = min(로컬 semaphore, `max-concurrent-runs` − DB의 live lease 실행 수(`RUNNING|RETRYING|CANCELING AND lease_expires_at > now`)).
  다른 인스턴스·이전 프로세스의 실행도 lease가 만료될 때까지 슬롯을 차지한다. 슬롯이 없으면 run은 PENDING으로 남는다(in-memory queue 없음).
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
| `isRecoverableByStatusQuery` (TRANSPORT_LOST·TIMEOUT·DEADLINE_EXCEEDED·UNAVAILABLE·STREAM_ENDED_WITHOUT_TERMINAL) | 같은 attempt·fence로 `status`: **absent**면 같은 요청 재전송(#215), **같은 epoch executing**이면 같은 요청으로 다시 붙고, **terminal**이면 `settle(StoredTerminal)`, `run.canceled`·canceling·다른 epoch면 OBSOLETE, fingerprint 불일치는 `failByServer(INTERNAL_ERROR)`. status 실패는 `max-status-checks`까지 backoff |
| 그 밖(영구) | `failByServer(INTERNAL_ERROR, true)`. `REQUEST_ID_BOUND_TO_OTHER_RUN`은 ERROR 로그(Server 버그) |

- 재전송·재연결은 `max-resends` 합계와 고정 deadline 안에서만 한다(다음 backoff 뒤 시각이 deadline을 넘으면 시작하지 않는다). 한도를 다 쓰면 deadline 전에는
  `failByServer(PROVIDER_ERROR, true)`, 지났으면 `expireDeadline`(timeout)이다. 둘 다 1.82가 보상을 한 번 처리한다. **transport 종료는 성공이 아니다.**
- deadline sweep은 `PENDING|RUNNING|RETRYING AND request_deadline_at <= now`를 `expireDeadline`으로 끝내고(슬롯 대기 중 PENDING 포함), cleanup을 예약하고,
  로컬 worker를 멈춘다.

## 슬롯 해제 조건

worker가 끝나면 **항상** 반환한다. 반환하면 곧바로 dispatch를 한 번 더 돌린다.

| 결과 | run 상태 |
|---|---|
| SETTLED | terminal(이 worker 또는 이미 같은 결과) |
| OBSOLETE | 다른 경로(취소·deadline·다른 fence)가 맡음 |
| STOPPED | 종료(SHUTDOWN)·lease 상실(LEASE_LOST)·`release` 호출(FINISHED_ELSEWHERE). 진행 중으로 남는다. SHUTDOWN은 lease를 즉시 만료시킨다 |
| LOCAL_ERROR | DB 오류 등. lease를 즉시 만료시킨다(회수 대상) |

heartbeat가 0 row면(terminal·fence 교체·lease 인수) 그 worker를 LEASE_LOST로 멈춘다. heartbeat 조건은 `RUNNING|RETRYING|CANCELING` + attempt·fence·owner다.
종료할 때는 control task(heartbeat·claim)가 끝나기를 기다린 뒤 worker를 멈춘다. 그래야 늦게 도착한 heartbeat가 만료된 lease를 되살리지 않는다. Brain 실행은 취소하지 않는다.

## 관측

Micrometer(`proovy.run.coordinator.*`): gauge `slots.in_use`·`slots.max`·`leader`, counter `claims`·`redeliveries{kind}`·`status_checks{result}`·
`executions{result}`·`server_failures{code}`·`deadline_expired`·`heartbeat.lost`·`errors{stage}`. tag는 고정 enum 이름뿐이다.
로그에는 runId·attempt·kind·outcome·SQL 예외 class 이름만 남는다. payload·grant·URL·fence 외 요청 값은 남기지 않는다.

## 1.84·1.85 인계 (hook·제약)

- **1.84 취소**
  - PENDING 취소: claim이 `SKIP LOCKED` + `status = 'PENDING' AND attempt = 0` 조건부라 취소 transaction과 겹쳐도 하나만 이긴다.
  - RUNNING/RETRYING → CANCELING: worker는 계속 stream을 소비한다. `appendActivity`가 열린 Activity의 `activity.canceled`만 받고, terminal은 NOT_ACCEPTING → OBSOLETE로 슬롯을 반환한다.
    Brain cancel 뒤 stream이 terminal 없이 끝나면 status가 canceling·`run.canceled`를 보여 OBSOLETE가 된다.
  - CANCELING 동안 lease를 유지하면 heartbeat가 계속 갱신하고 전역 슬롯도 그만큼 유지된다(Brain 정리 전 슬롯을 풀지 않음). worker를 먼저 끝내려면 `RunCoordinator.release(runId)`를 부른다.
  - Brain cancel 호출·CANCELED 확정·`run.canceled` 저장 terminal 처리는 1.84 몫이다. 1.84도 terminal 뒤 `RunClaimStore.reserveRuntimeCleanup`을 부른다.
- **1.85 회수**
  - STOPPED·LOCAL_ERROR와 이전 프로세스의 run은 `RUNNING|RETRYING` + 만료 lease로 남는다(`idx_ai_runs_stale_lease`). 지금은 deadline sweep이 결국 timeout으로 끝낼 뿐 status 회수는 없다.
  - 1.85는 `RunCoordinator.isExecuting(runId)`가 false인 stale run만 다룬다. 같은 attempt·fence로 lease를 인수한 뒤(새 attempt 금지) `RunExecution`의 status 수습 규칙을 재사용하는 것을 권장한다.
    인수한 run도 live lease로 전역 슬롯을 차지해야 한다. Server 재시작 직후에는 이전 프로세스의 live lease가 TTL 동안 슬롯을 차지한다(Brain이 아직 실행 중일 수 있음).
- **R7 자격증명**: S10 전에는 grant·viewer read URL을 넘기지 않는다(`RunAssetAccess.NONE`). Viewer Focus의 Page Understanding cache miss에서 Brain이 URL을 요구하면
  Brain terminal(실패)로 끝난다. S09/S10이 `RunAssetAccessProvider`를 교체하고 전달마다 새로 발급한다.
- **runtime cleanup**: `runtime_cleanup_at`(attempt ≥ 1인 terminal run)만 예약한다. Brain `cleanup` 호출·retention은 S13이다.

## 검증하지 않은 부분

- 실제 Brain·Cloud Run 왕복, 다중 인스턴스 lease 인수 실측, 슬롯·timeout 수치(S14). 테스트는 실제 `BrainRunClient` + loopback 가짜 Brain(HTTP/SSE) + 실제 Postgres다.
- usage counter(`used_*`)는 Server에서 올리지 않는다(Brain이 단조 병합). 누적 값을 Server에 반영하는 경로가 필요하면 별도 태스크로 정한다.
