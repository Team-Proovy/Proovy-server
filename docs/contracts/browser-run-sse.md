# 브라우저 run SSE·조회 — replay/live 무유실 합류 (task 1.86, #223)

browser-run-v1 계약(`browser-run-v1.md`, pin된 Brain artifact가 판정 기준)의 public 경로 구현이다. 계약 의미는 바꾸지 않았다.

코드: `com.proovy.domain.run.controller.BrowserRunController`(경로), `com.proovy.domain.run.stream`(연결·복원·조회).

## 경로

| Method · Path | 구현 | 성공 | stream 전 오류 (JSON `ApiResponse`) |
|---|---|---|---|
| `POST /api/conversations` | `RunAdmissionService.admit`(1.79) → `RunStreamService.openAdmitted` | SSE seq 1 `run.created`부터. CREATED·REPLAYED 같은 처리 | 401 · RUN4001(구조·깨진 JSON·지원 안 하는 Content-Type) · RUN4002 · RUN4092 · RUN4041 · RUN4221 · RUN4091{activeRunId} · RUN4291(+Retry-After) · RUN4021 |
| `GET /api/ai-runs/{runId}/events` | `RunStreamService.openReplay` | SSE: cursor 없으면 seq 1부터, 있으면 seq+1부터 → terminal이 아니면 live | 401 · 404 RUN4041 · 400 RUN4003 · 400 RUN4004 |
| `GET /api/ai-runs/{runId}` | `RunReadService.status` | `RunStatusResponse` | 404 RUN4041 |
| `GET /api/conversations/{noteId}/messages?before&limit` | `RunReadService.conversation` | `ConversationPageResponse` | 404 RUN4041 · 400 RUN4001(before가 UUID 아님·limit 범위 밖) |

- 취소 `POST /api/ai-runs/{runId}/cancel`은 1.84가 같은 controller에 추가한다.
- stream 전 오류는 `Accept: text/event-stream`이어도 `application/json`이다(`BrowserRunExceptionHandler`, 접수 거부는 `RunAdmissionExceptionHandler`).
  예상 밖 실패는 500 COMMON500 JSON(ERROR + stack 로그), Spring MVC 요청 오류(406 등)는 그 4xx 상태 + COMMON400 JSON이다.
  전역 `GlobalExceptionHandler`는 SSE Accept면 오류를 `text/event-stream`으로 감싸므로 이 controller에는 쓰지 않는다.
- 404 RUN4041은 없는 run, 타인 run, 삭제 중 Note, 대문자·형식이 다른 runId, 타인 Note를 구분하지 않는다(같은 body).
- cursor 판정 순서: 인증 → run 접근 RUN4041 → 형식·범위 RUN4003 → 다른 run RUN4004 (`cursors/cases.json` precedence, `RunEventCursor.resolve`).
  header와 query가 둘 다 있고 다르면 RUN4003이다. 범위 판정은 연결 시점의 `ai_runs.last_event_seq`다.

## SSE wire (확정 — S08·1.87 소비)

frame 블록은 fixture `streams/valid/*.json`의 `sse`와 같은 바이트다(필드 순서 `event`·`id`·`data`, 콜론 뒤 공백 한 칸, data 한 줄):

```
event: run.created
id: 5118a0ec-5b14-5dc8-aead-81da16123ee1:1
data: {"contractVersion":"brain-source-v1","runId":"5118a0ec-5b14-5dc8-aead-81da16123ee1","seq":1,"occurredAt":"2026-10-17T09:00:00Z","type":"run.created","payload":{"requestId":"e7380af3-85cb-521a-a30b-77761495827b","noteId":"123","userMessageId":"df777a3a-c744-59f5-bb8b-2bf64e874b9c","assistantMessageId":"c505a965-c220-58e1-9ec0-f48018f1262d","status":"PENDING","requestedTasks":["solve"],"requestedOutputs":["text"]}}

```

heartbeat(15초, live 연결에서만):

```
: heartbeat

```

- heartbeat는 comment다. `id`·`event`·`data`가 없어 cursor를 바꾸지 않고, event row·seq를 만들지 않는다(테스트로 행 수·`last_event_seq` 불변 확인).
- 연결은 terminal frame(`run.completed`|`run.failed`|`run.canceled`)을 보낸 직후 서버가 닫는다. terminal run에 마지막 seq cursor로 붙으면
  0 frame 뒤 바로 닫는다.
- payload별 원천: activity.*·run.created·run.canceling·run.canceled·response.page.*·run.failed는 저장 payload 그대로,
  `run.completed`는 저장 최소 요약 + canonical assistant message(`TerminalReplay.completedPayload`, 1.82).
  `sourceReferences[].available`만 read-time(같은 Note asset이 지금 `ACTIVE`면 true)이고 label·page snapshot·ID·plainText는 불변이다.
- 전체 frame 예제: browser-run-v1 fixture `streams/valid/*.json`(10개 시나리오). 서버 출력이 그 형식과 S1~S7을 지키는지는
  `BrowserRunSsePostgresTest`가 실제 HTTP로 받은 frame에 `BrowserFrame` schema·S1~S7 검사기를 돌려 확인한다.

### 브라우저(S08) 사용 메모

- POST SSE는 body와 `Authorization`이 필요하므로 `EventSource`가 아니라 fetch 기반 SSE reader를 쓴다. 재접속도 같은 reader로
  `Last-Event-ID` header 또는 `?lastEventId=` query(`{runId}:{seq}`, 마지막으로 받은 frame의 `id`)를 보낸다.
- 재접속 cursor는 **마지막으로 처리한 frame의 `id`**다. heartbeat는 cursor가 아니다.
- 연결이 terminal 없이 끝나면(네트워크 단절, 서버 최대 유지 시간 `proovy.run.sse.timeout` 30분, 서버 재시작, 서버 내부 오류) GET으로
  재접속하되 **지수 backoff**를 둔다. 같은 seq에서 서버 오류가 반복되면 즉시 재접속은 루프가 된다. 재접속이 404 RUN4041이면 멈춘다(Note 삭제 등).
  단절은 실행 취소가 아니다 — 취소는 명시적 cancel API뿐이다.
- 409 RUN4091의 `activeRunId`로 `GET /api/ai-runs/{activeRunId}/events`에 붙으면 진행 중 run을 이어 볼 수 있다.

## 무유실·무중복 (`RunEventStream`)

1. 연결은 **신호 구독을 먼저** 걸고, 그다음 cursor 이후를 DB(`RunEventStore.findAfter`)에서 seq 순으로 읽는다.
2. 보낸 frame의 seq로 cursor를 올리고 `seq > cursor`만 보낸다. 1.80의 "commit 순서 = seq 순서"라 아직 commit되지 않은 앞 seq를 건너뛰지 않는다.
3. 신호(`RunEventSignals`)는 깨우기 힌트다. 신호가 유실되어도 `catch-up-interval`(기본 5초)마다 DB를 다시 보므로 그 안에 복구된다.
   다른 Server 인스턴스의 commit도 이 경로로 보인다(지연은 catch-up 주기).
4. 상태를 event보다 먼저 읽는다. 상태가 terminal이면 terminal event도 같은 transaction으로 이미 commit되어 이어지는 조회에 보인다.
5. 연결의 모든 쓰기(frame 전송과 heartbeat)는 쓰기 소유권 하나를 가진 쪽만 한다 → 연결당 쓰는 thread는 동시에 최대 하나다. 전송 중이면
   heartbeat는 건너뛴다(frame이 흐르고 있다). 전송 중 신호는 표시 하나로 합쳐진다 → 신호가 몰리거나 브라우저가 느려도 서버에 frame queue가
   쌓이지 않고, 신호 listener(append commit thread)는 기다리지 않는다.

연결은 저장 event를 읽고 구독할 뿐 run을 만들거나 dispatch·취소·차감하지 않는다. 재접속 반복이 run·worker·사용량을 늘리지 않는다(테스트로 행 수 확인).

## 구독 해제

| 원인 | 동작 |
|---|---|
| terminal frame 전송 | 정상 종료(서버가 닫음) |
| run 소멸(Note 삭제 cascade) 또는 Note가 DELETING | 정상 종료, 그 뒤 frame 없음(새 GET·조회의 RUN4041과 같은 의미) |
| 브라우저 단절 | 다음 쓰기(heartbeat·frame)에서 감지 → 구독·예약 작업 해제. run 상태는 그대로 |
| 최대 유지 시간(`timeout`) | 연결만 닫음 |
| 복원·조회 실패(내부 오류) | 오류 종료(`completeWithError`, ERROR + stack 로그). terminal 없이 끊김 → 브라우저는 **backoff 뒤** 재접속 |

Note 삭제와의 관계: Note가 DELETING이 되면 열린 연결은 다음 catch-up(≤ `catch-up-interval`)에서 닫힌다. 진행 중 run의 취소·terminal
(`run.canceled` 등)은 1.84 Note 삭제 cancel 흐름의 몫이다. 그 terminal이 commit돼도 DELETING Note의 연결에는 보내지 않는다 — 브라우저는 이미
삭제 중인 Note를 떠난 상태이고, 재접속은 RUN4041이다.

`RunStreamService.openStreams()`는 열린 연결 수다(테스트가 단절 뒤 0으로 돌아오는지 확인한다).
브라우저 단절의 `AsyncRequestNotUsableException`은 오류가 아니라 `BrowserRunExceptionHandler`가 조용히 끝낸다.

## 대화 조회

- 메시지는 `createdAt` 오름차순, `before`(그 Note의 messageId)보다 오래된 것 중 최신 `limit`(1..100, 기본 50)개.
  더 오래된 메시지가 있고 페이지 맨 앞이 assistant면 그 assistant를 다음 페이지로 넘겨 한 turn이 갈리지 않게 한다.
  `nextBefore`는 더 오래된 메시지가 있을 때 이 페이지 맨 앞 messageId, 없으면 null.
- run과 묶인 메시지만 나온다(`ai_runs`의 user/assistant message). 신규 baseline에서는 모든 메시지가 run에 묶인다.
- user: `text`·`latex`는 `user_input`, `requestedTasks`·`requestedOutputs`는 그 run의 `run.created` payload(stream과 같은 값),
  `viewerFocus`는 run snapshot `viewer_context`(`noteAssetId` = Note Asset ID 문자열), `turnAttachments`는 `turn_attachments`(`displayName` = `display_label`).
- assistant: COMPLETED는 SSE `run.completed`와 같은 함수로 만든 `assistantResponse`·`verificationSummary`(조회·replay·live 동일, 차이는 `available`),
  FAILED는 `run.failed` payload의 `{code, reason, message, recoverable}`, CANCELED는 `cancel_cause`의 고정 문구, PENDING·STREAMING은 본문 없는 placeholder.
- `activeRun`은 그 Note의 PENDING|RUNNING(RETRYING 포함)|CANCELING run과 `lastSeq`. 페이지와 무관하다.
- 상태 응답 `failure`도 `run.failed` payload에서 읽는다. `responseDisposition`은 COMPLETED일 때만.

## 설정 (`proovy.run.sse.*`, 기본값은 `@DefaultValue` — application.yaml 변경 없음)

| key | 기본 | 의미 |
|---|---|---|
| `heartbeat` | 15s | 계약 값(D10). 테스트만 줄인다 |
| `catch-up-interval` | 5s | 신호 없이 DB 재확인 주기(연결당 `ai_runs` 한 행 조회) |
| `timeout` | 30m | 연결 최대 유지 시간 |
| `batch-size` | 100 | catch-up 한 번 조회 수 |
| `threads` | 8 | 연결 공용 전송 thread(`runSseScheduler`) |

`runSseScheduler`는 `Executor`·`ScheduledExecutorService`·`TaskScheduler` 타입 bean이 **아니다**. 그런 bean을 하나라도 등록하면 Spring Boot의
기본 `applicationTaskExecutor`(MVC async)·`taskScheduler`(@Scheduled)가 back-off되거나 @Scheduled가 그 pool을 고르게 된다(공통 설정 영향).

## 보안 설정 (SecurityConfig, 1.86 소유 규칙)

run SSE 경로(`POST /api/conversations`, `GET /api/ai-runs/*/events`)의 **ASYNC dispatch**만 permitAll이다. SSE 완료 시 재dispatch에는
JWT filter(OncePerRequestFilter)가 돌지 않아 인증이 없고, 규칙이 없으면 이미 commit된 응답에 401을 쓰려다 servlet 오류가 난다
(규칙을 끈 probe에서 2개 테스트 실패·오류 로그 56건 확인). 최초 요청의 인증·권한은 그대로다.

## 검증

- `RunEventStreamTest`(DB 없음): replay→live, cursor 재개, 0 frame 종료, 신호 유실 복구, 경계 append 무유실·무중복, 느린 구독(쓰기가 막힌 동안
  신호 1000개가 즉시 반환·queue 증가 없음·다른 연결 진행), 느린 연결 thread 하나(2-thread pool에서 다른 연결 진행·동시 쓰기 1),
  복원 실패 오류 종료, 단절 해제, run 소멸, heartbeat.
- `BrowserRunSsePostgresTest`(실제 Postgres + 실제 HTTP client): 최초 POST live·완료 종료·같은 바이트 replay·조회/상태 일치와 schema,
  같은 requestId 재전송, 단절 뒤 Last-Event-ID·query 재접속(구독자 없을 때 commit=방송 유실 포함), 동시 append 중 임의 단절·재접속 2 client,
  신호 전부 유실 시 DB 복구, heartbeat 행·seq 불변, terminal commit 전 stream·상태·조회 비공개, 타인·없는 run 404 동일 body, cursor 판정표 전체,
  stream 전 JSON 거부·RUN4091, 예상 밖 실패 COMMON500 JSON, Note DELETING 시 열린 연결 종료, asset 삭제 뒤 `available`만 변경, 대화 조회 페이지.
- cursor 판정표의 공백·줄바꿈 case(`trailing-newline`·`surrounding-space`)는 HTTP header가 앞뒤 공백을 잘라내고 줄바꿈을 담을 수 없어
  같은 원문을 `lastEventId` query로 보낸다.

## 범위 밖·인계

- **1.87**: 실제 coordinator(1.83) 실행과 연결해 end-to-end로 같은 stream을 확인한다. 이 태스크의 producer는 production 저장 경로
  (`appendActivity`·`RunTerminalService.settle`)를 직접 부르는 fake다.
- **S08**: reducer·renderer. 열린 Activity가 terminal 뒤 남는 경우의 표시(1.80 메모), SafeMarkdown `\r` 렌더 방어(1.81 메모),
  superseded solution 재표현의 "정정된 풀이" 표시(1.81 결정). 마지막 항목은 현재 wire에 solution status가 없어 화면이 판단할 값이 없다 —
  필요하면 browser-run-v1 새 consumer_version으로 필드를 추가한다(계약 변경, 미착수).
- **S14**: 다중 인스턴스 신호(지금은 catch-up 주기로 복구), 프록시 buffering, 대규모 연결. 느린 브라우저의 쓰기는 pool thread 하나를 막는다
  (연결당 최대 하나). 한계: 수신을 멈춘 연결이 pool `threads`(기본 8)개 생기면 Tomcat write timeout까지 **모든 SSE 연결의 전송이 멈춘다**.
  쓰기 timeout·연결 수 상한·non-blocking 전송은 S14 결정이다.
- **SecurityConfig 공통 후속 제안**: 지금 ASYNC 규칙은 run SSE 경로 문자열만 본다(1.86 소유 범위). 1.84 cancel은 동기라 추가가 필요 없지만, 이후
  async endpoint마다 경로를 더해야 한다. Spring Security 권장 방식인 전역 `dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` 전환은
  SecurityConfig 공통 담당에게 제안한다(ASYNC dispatch는 이미 인가된 요청에서만 생긴다).

## 계약 후속 (browser-run-v1 다음 consumer_version, Brain fixture로 고정)

이 PR은 계약을 바꾸지 않았다. 아래 두 가지는 Server 이슈에서 의미를 합의한 뒤 proovy-brain `tests/fixtures/contracts/browser-run-v1`의
새 consumer_version으로 고정하고 Server·Front가 pin을 올린다(`browser-run-v1.md` "계약을 바꿀 때"). 담당: 1.87 또는 별도 Brain PR(오케스트레이터 결정).

1. **대화 조회 `before`/`limit` 의미** — artifact에는 경로·응답 schema만 있다. 이 PR의 구현값: `before` = 그 Note의 messageId(UUID 아님 RUN4001,
   그 Note 메시지 아님 RUN4041), `limit` 1..100·기본 50(밖은 RUN4001), 더 오래된 메시지가 있으면 페이지 맨 앞 assistant를 다음 페이지로
   (turn 보존), `nextBefore` = 페이지 맨 앞 messageId. fixture case(경계·오류)로 고정한다.
2. **superseded solution 재표현의 "정정된 풀이" 표시**(1.81 결정, 1.86/S08) — 현재 wire(`run.completed`·대화 조회)에 solution status가 없어
   화면이 판단할 값이 없다. 표시하려면 필드(예: block·summary의 solution 상태)를 additive로 추가해야 한다. replay/live 차이 규칙
   ("유일한 차이는 `available`")과의 관계(read-time 값인지)도 함께 정한다.

## 효율 메모

- `run.completed` hydration은 Note ID를 한 번 더 조회한다(연결당 terminal 한 번). `TerminalReplay.CompletedMessage`(1.82)에 note_id를 싣는 것은
  1.82 타입 변경이라 하지 않았다.
