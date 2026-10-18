# run 취소 — 명시적 cancel·CANCELED 확정·상태별 사용량 (task 1.84, #224)

브라우저 `POST /api/ai-runs/{runId}/cancel`(browser-run-v1 `cancel/cases.json`)의 구현이다. 계약 의미는 바꾸지 않았다.
상태 조회 `GET /api/ai-runs/{runId}`는 1.86(`RunReadService.status`) 그대로다(`cancelable` = PENDING|RUNNING).

코드: `com.proovy.domain.run.cancel` — `RunCancelService`(진입점), `RunCancelStore`(잠금·조건부 전환), `RunCancelFinalizer`(CANCELED 확정),
`RunCancelProperties`(`proovy.run.cancel.*`). 경로는 1.86 `BrowserRunController`에 추가했다. **schema 변경 없음**(V1 cancel 열·trigger 그대로, V5 미사용).

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
| `absent` (record 없음 = 미시작) | run lease가 만료됐고 이 인스턴스에서 실행 중이 아니면 CANCELED. 살아 있는 worker가 아직 stream을 보낼 수 있으면 기다린다 |
| `canceling`·`executing`·호출 실패(5xx·timeout·409 등)·Brain client 없음 | 기다린다 |
| (모든 경우) `cancel_requested_at + cleanup-timeout` 경과 | WARN 뒤 CANCELED (안전 timeout) |

확정 transaction: `CANCELING → CANCELED`(lease·grant 해제), message CANCELED, `run.canceled{cause, message}`, 1.77 `settle(canceled(from, cause))`.
commit 뒤 `RunClaimStore.reserveRuntimeCleanup`(attempt ≥ 1, 지연은 `proovy.run.coordinator.runtime-cleanup-delay`, coordinator가 꺼진 인스턴스는 0s) →
`RunCoordinator.release(runId)`. coordinator 패키지는 고치지 않았다(공개 hook만).

- 확정 전까지 run은 비terminal이라 **전역 슬롯(1.83 DB 수)과 Note Active Run 자리(`ux_ai_runs_note_active`)를 계속 차지한다**. 같은 Note 새 요청은 RUN4091.
- CANCELING 동안: Activity는 열린 Activity의 `activity.canceled`만(1.80), terminal·`failByServer`·`expireDeadline`은 NOT_ACCEPTING(1.82), deadline sweep 대상 아님.
  worker는 stream을 마저 소비해 wind-down 표식을 저장하고 status `canceling`/`run.canceled`로 OBSOLETE로 끝난다(1.83).
- 스레드: 내부 executor 2개(`runCancel-*`), Spring `Executor`·`TaskScheduler` bean 아님. Brain 호출 중 transaction·row lock 없음.

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

## 반복 요청 제한 (1.79 재사용 — 차이 보고)

새 구현 없이 1.79 RUN4291(`proovy.run.admission.rate-limit`, 기본 20건/1분, 임시값)을 그대로 쓴다. 테스트로 고정한 성질:
새 requestId마다 세고, **취소·실패·clarification으로 순사용량 0이 된 run도 센다**(취소로 우회 불가), 같은 requestId 재조회(replay)는 세지 않고,
다른 사용자에 영향 없음. task 84 문구 대비 차이(구현하지 않음, 결정 필요):

1. 반복 실패/clarification 남용을 "구분"하는 별도 제한이 없다 — 모든 새 요청을 같은 창으로 센다. 무료 결과(보상된 run) 누적 상한(예: 1시간 N건)이 없어
   20건/분 안에서는 무료 Brain 실행을 계속 만들 수 있다.
2. "입력·준비 상태 보완 요청" 구별이 없다 — 보완 요청도 같은 창으로 센다(더 엄격한 제한이 없으므로 부당하게 막히지도 않는다).
3. 수치(20/1분)는 S14 운영 결정.

제안: 보상된 run을 browser fingerprint(`run_request_bindings`) 기준으로 세는 추가 상한(같은 fingerprint 반복만 제한 → 보완 요청은 fingerprint가 달라 제외).
browser-run-v1 오류 code는 RUN4291 그대로 쓸 수 있다.

## 검증

- `RunCancelPostgresTest`(실제 Postgres + 실제 HTTP + 실제 `BrainRunClient`·loopback 가짜 Brain, coordinator 끔): `cancel/cases.json` 9 case를 production
  endpoint로(fixture body 일치, 404 body 동일), 대문자 ID·DELETING Note·401, PENDING 취소(payload = `canceled-pending` fixture, 보상 1회, Brain 미호출, 슬롯 해제),
  동시 cancel 6건, PENDING 취소 ↔ claim 8회, PENDING 취소 ↔ deadline·Server 실패, RUNNING 취소(늦은 terminal·Activity·deadline 차단, RUN4091, 슬롯 계산,
  Brain cancel 인자, 확정 뒤 차감 유지·cleanup 예약), cleanup timeout(정리 미확인·Brain 503), absent + lease, 완료 ↔ 취소, deadline ↔ 취소, SSE 단절 ≠ 취소,
  반복 요청 제한.
- `RunCancelCoordinatorPostgresTest`(실제 coordinator worker, 슬롯 1): wind-down `activity.canceled` 저장, worker OBSOLETE 뒤에도 CANCELING이 슬롯을 잡아
  다음 run이 PENDING, Brain 정리 확인 뒤 CANCELED → 다음 run dispatch, 취소 run 재전달 없음. event 순서는 `canceled-running` fixture와 같다.

## 범위 밖·인계

- **1.87**: 경로 `POST /api/ai-runs/{runId}/cancel`(body 없음, `ApiResponse<RunCancelResult>`). 실제 Brain cancel 왕복·wind-down 시간 실측, timeout 수치 확인.
- **1.85**: CANCELING run(lease 만료 포함)은 이 경로 소유다. 1.85 회수는 RUNNING|RETRYING만 다룬다.
- **S13**: `asset_deleting` cause 취소 연결(이 store의 `cancelPending`·`markCanceling`이 cause를 받는다), Brain runtime `cleanup` 호출.
- **S08**: 정지 버튼·draft 유지(서버는 자동 재실행·queue·draft 전송을 만들지 않는다).
- Brain cancel 409(fence 불일치·다른 epoch)는 기다림으로 처리해 timeout 뒤 확정된다(새 attempt 금지 전제상 발생하지 않아야 한다).
