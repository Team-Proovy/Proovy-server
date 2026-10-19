# Server ↔ Brain 로컬 실행 연동 인수 (task 1.87, #226)

S07 Server 구현 전체(1.74~1.86)를 **실제 Python Brain 내부 API·runtime**과 로컬에서 연결해 인수한다.
Brain 쪽 test bootstrap은 proovy-brain `testkit/`(PR #150, squash `7b8608ee345761526aee0c76186ca984b496f41e`)이고,
Server 쪽 검사는 `com.proovy.integration.brain.ServerBrainIntegrationTest`다.

## 실행

```bash
BRAIN_DIR=<proovy-brain checkout @ 7b8608e, uv sync --locked> \
PG_CONTAINER=proovy-postgres-brain-it GRADLE_IN_DOCKER=1 scripts/brain-integration/run-local.sh
```

순서: Gradle `testClasses` → 격리 Postgres(tmpfs, `postgres:15-alpine`) → Brain testkit supervisor(`uv run --locked python -m testkit.integration supervise`)
→ warm-up child → `./gradlew test --tests 'com.proovy.integration.brain.*'`(`PROOVY_REQUIRE_BRAIN_IT=true`) → 컨테이너·프로세스 정리.

- **Server CI는 실행하지 않는다**(사용자 결정: proovy-brain이 private, checkout token 없음). CI 로그에는 skip 이유(`NOT EXECUTED — real Brain integration …`)와
  `Report real-Brain integration tests NOT EXECUTED` step의 warning이 남는다. skip은 통과가 아니다.
- 한 database에 빈 제품 baseline(`proovy_server`가 Flyway 실행)과 Brain schema(`brain_migration`·`brain_runtime`, Brain bootstrap·migrate)를 두고
  `db/provisioning/role-boundary.sql`을 적용한다(운영 형태). Server datasource는 `proovy_server`다.
- 공용 호스트 부하 대응: supervisor의 child readiness 대기는 60초 고정이라, 스크립트가 child를 미리 한 번 띄워 page cache를 데우고
  Java client는 `did not become ready`일 때만 start·restart를 최대 3번 다시 보낸다(후속 메모: Brain readiness 대기 설정화).

## S07 Verification 대응

| Verification (spec 07) | 테스트 | 실제로 거친 경로 |
| --- | --- | --- |
| 1. 빈 DB·ChatSession/thread 없음, noteId 없는 중복·같은 Note 동시 전송이 하나만 남고 거부는 메시지 없음 | `firstTextQuestion…`, `concurrentDuplicateNotelessRequests…`, `concurrentSameNoteRequests…` (+ 단계 3 `RunPathEndToEndPostgresTest`·`LegacyExecutionPathGuardTest`) | HTTP 접수 → coordinator → Brain graph 1회 → terminal transaction. 409 RUN4091 + activeRunId, 메시지·run·차감 없음 |
| 2. SSE 단절·재접속 seq·canonical 복원, replay/live 무유실 | `firstTextQuestion…`(POST live = GET replay 바이트), `browserDisconnectAndInternalSseLoss…`, `storedTerminalLostInDelivery…` | 브라우저 Last-Event-ID 재접속, Brain 내부 SSE 첫 frame 직전 abort → status executing → 같은 attempt·fence OBSERVE 재부착, terminal 전 abort → status terminal 회수 |
| 3. 완료·취소·deadline·이전 attempt 경합에서 확정과 보상이 동시에 일어나지 않음 | `runningCancelBlocks…`, `pendingCancel…`, `deadlineRace…`, `brainSideDeadline…`(fake clock), `staleFenceTerminal…`, `serverRestart…`, `brainLostBeforeStorage…` | terminal event 1개·보상 ≤ 1·재실행 0을 run마다 검사 |
| 4. clarification·unsupported_only·혼합·no-match·최종 실패·PENDING/RUNNING 취소 순사용량 | `outcomeMatrix…`, `explainFollowUp…`, 취소 2종 | 정상 solve·check·explain 1, 미검증 공개 1, clarification 0, unsupported_only 0, 혼합 1, 자료 부재(scripted) 1, verification_failed 0, provider_error 0, PENDING 취소 0, RUNNING 취소 1, deadline 0, 저장 전 소실 0 |
| 5. Java DTO·fingerprint·source 변환이 공용 fixture와 일치, scope/provenance 위반 거부 | `javaAndPythonAgree…`, `outOfScopeSource…`, `forgedRunEnvelope…`, `staleFenceTerminal…` | Brain record fingerprint = `ai_runs.request_fingerprint`, Brain fixture sha256 = Server PIN 전부, scope 밖 asset(scripted) → FAILED internal_error·출처/solution 없음, 다른 run envelope(stream+status, scripted) → FAILED, 이전 fence(stream, scripted) → 버리고 저장 terminal 회수 |

보안 경계(`securityBoundariesHold`): Brain이 받은 호출은 전부 shared token이고 body에 사용자 JWT·`Bearer`·grant·`read_url`·서명 URL이 없다.
token 없음·사용자 JWT·틀린 token은 Brain이 401/403으로 거부한다. 브라우저 frame·대화 조회에 fence·grant·read URL·서비스 token이 없다.
같은 database에서 `brain_runtime` → `public.ai_runs` 42501, `proovy_server` → `brain_runtime.run_records` 42501.

## 구분 — fake·scripted·미검증

- **fake provider**: Brain 모델·Daytona·page VLM은 offline seat다. 결과 분류(검증/미검증/실패)는 scripted seat 판정이고 수학 품질을 인증하지 않는다.
- **scripted terminal/fault (그래프 미실행 또는 전달 변조)**: `no_match_notice`(자료 부재 안내 — Brain runtime에 문서 근거 no-match 경로가 아직 없다),
  `out_of_scope_source`, `rewrite_terminal`(+`rewrite_status`). 저장·전달은 실제 Brain store·SSE(PgRuntimeBackend)를 거친다.
- **미검증(S14)**: 실제 유료 모델 품질·비용, Cloud Run 서비스 계정 ID token·IAM, Supabase/GCS, 실제 자산 업로드·signed URL(자산 경로는 1.81 seeded 계약 자료로만 검수),
  부하·다중 인스턴스, 브라우저 화면.
- **Server CI**: skip(위). 결과는 이슈 #226·PR 본문에 Server head·Brain SHA와 함께 기록한다.

## S08이 쓸 외부 계약

- 브라우저 wire는 browser-run-v1 그대로다(변경 없음): `POST /api/conversations`(fetch 기반 SSE reader, body·Authorization 필요),
  `GET /api/ai-runs/{runId}/events`(Last-Event-ID 또는 `lastEventId` query), `GET /api/ai-runs/{runId}`, `POST /api/ai-runs/{runId}/cancel`(body 없음),
  `GET /api/conversations/{noteId}/messages`. 재접속 cursor는 마지막 처리 frame `id`.
- 실제 Brain에서 나오는 frame 순서(정상 solve): `run.created` → `activity.started/completed`(입력 분석·풀이·검증) → `response.page.started` →
  `response.page.completed` → `run.completed`. 취소는 `run.canceling` → `run.canceled`, 실패는 `run.failed`(보상된 run은 순사용량 0).
- 이월(계약 변경 필요, 이 태스크는 바꾸지 않음): superseded 재표현 표시 필드, 대화 조회 before/limit fixture, U+0000 RUN4001, `viewerFocus.page` 상한.

## S13이 재사용할 복구 경계

- `RunClaimStore.findStale`·`takeOver`(같은 attempt·fence 인수), `RunExecution` RECOVER/FINAL_CHECK(status 먼저 → `settle(StoredTerminal)`·`failByServer`·`expireDeadline`),
  `hasBrainEvents`(미시작 vs 실행 소실). 실제 Brain에서 확인한 결과:
  - Server 정지 중 Brain이 저장한 terminal → 재시작 뒤 같은 epoch로 회수, graph 1회.
  - Brain이 terminal 저장 전 SIGKILL → 재시작한 Brain record는 `executing`(terminal 없음, 재개·재실행 0). Server는 같은 요청 OBSERVE·status 수습 뒤
    infrastructure 실패로 한 번 보상한다. **Brain record는 executing으로 남는다** — Brain lease 만료 sweep·`cleanup` 호출은 S13.
- `runtime_cleanup_at` 예약까지가 Server 범위이고 Brain `cleanup` 호출·retention은 S13이다.

## 후속 메모

- testkit release·clock route가 잘못된 JSON body에 500을 돌려준다(테스트 도구 한정, r187 r2).
- supervisor child readiness 대기(60s)를 설정값으로 — 공용 호스트 부하에서 재시도에 의존하지 않게(Brain 후속).
- `deploy.yml`의 `PROOVY_AI_HOST`·`INTERNAL_API_TOKEN` secret 주입은 읽는 코드가 없다(S14 정리 후보, 파일 미변경).
