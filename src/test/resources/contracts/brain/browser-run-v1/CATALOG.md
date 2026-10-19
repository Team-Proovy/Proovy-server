# browser-run-v1 fixture catalog

> 이 파일은 `build_fixtures.py`가 생성한다 — 직접 편집하지 않는다.

브라우저 run API(접수·재조회·SSE replay·상태·취소·대화 조회)의 wire 계약 artifact다
(task 1.75, Team-Proovy/Proovy-server#214). **계약 의미는 Proovy-server가 소유**하고
Brain은 versioned artifact만 보관한다 — `src/`는 이 디렉터리를 import하지 않으며
제품 API·제품 DB 소유권을 Brain으로 가져오지 않는다. 평가 정답·oracle과 무관하다.

## 버전

- `consumer_version`: `browser-run-fixtures-v1` (2026-10-17 최초 고정, 단계 2 합의 D1~D10)
- `contract_version`: `browser-run-v1`
- durable frame `contractVersion`: `brain-source-v1` (D1 — terminal-v2 browser schema의 `const`와 같은 값을 Server 소유 frame에도 사용)
- 참조 계약: terminal-fixtures-v2 · activity-fixtures-v1 · assistant-response-v1 · internal-run-fixtures-v2(canonical JSON) · page-selection-v1
- 요청 fingerprint: `browser-request-sha256-canonical-json-v1`

## 소비 규칙

- 소비자(Server Java DTO 검사, Front reducer/MSW)는 **merge된 proovy-brain commit + `consumer_version`**을 pin한다.
- 사본은 검증된 mirror로만 허용한다: `manifest.json`의 `files`(이 디렉터리 전체)와 `dependencies`
  (`terminal-v2/browser.schema.json`)의 sha256이 모두 일치해야 하고, 소비자는 pin에 manifest 자체의
  sha256도 기록한다. 사본을 손으로 고치지 않는다 — SSOT는 Brain이다.
- 변경: 의미가 바뀌면 새 `consumer_version`(새 디렉터리 또는 버전업)과 `change_history` 항목을 남긴다.
  이 CATALOG를 포함한 모든 파일은 `build_fixtures.py`가 생성한다 — builder를 고친 뒤
  `uv run python tests/fixtures/contracts/browser-run-v1/build_fixtures.py`로 재생성한다.
  `tests/contracts/test_browser_run_fixtures.py`가 builder 출력·digest 불일치에서 실패한다.
- schema 검증은 `manifest.schema_registry`처럼 `https://proovy.invalid/contracts/terminal-v2/browser.schema.json`을
  `terminal-v2/browser.schema.json`에 매핑한 registry로 한다(`$ref: ../../terminal-v2/browser.schema.json`).
- 요청 body는 **closed**(알 수 없는/금지 필드 = `RUN4001`). 응답·이벤트는 additive-tolerant(알 수 없는 추가 필드 무시,
  알려진 필드 검증)이지만, 내부 키(`attempt`·`fenceToken`·`sourceEventId`·`failureClass`·`requestFingerprint`·`usage`·
  `deadline`과 snake_case 형)는 frame·payload와 Server 소유 응답 객체 모두에서 schema가 거부한다.

## 경로와 규칙 요약

| Method · Path | 성공 | 주요 오류 |
|---|---|---|
| `POST /api/conversations` (body `ConversationRunRequest`, `Accept: text/event-stream`) | 200 SSE seq 1 `run.created`부터. 같은 requestId·같은 사용자·같은 의미는 기존 run을 seq 1부터 replay | 401 · 400 `RUN4001`/`RUN4002` · 409 `RUN4092` · 404 `RUN4041` · 422 `RUN4221` · 409 `RUN4091{activeRunId}` · 429 `RUN4291`(+`Retry-After`) · 402 `RUN4021` |
| `GET /api/ai-runs/{runId}/events` (`Last-Event-ID` header 또는 `lastEventId` query) | 200 SSE: cursor 없으면 seq 1부터, 있으면 seq+1부터 replay 후 live | 404 `RUN4041` · 400 `RUN4003`(형식·범위·header/query 불일치) · 400 `RUN4004`(다른 run) |
| `GET /api/ai-runs/{runId}` | `RunStatusResponse` | 404 `RUN4041` |
| `POST /api/ai-runs/{runId}/cancel` (body 없음, 멱등) | `RunCancelResponse` (accepted·already_canceling·already_terminal) | 400 `RUN4001`(body 있음) · 404 `RUN4041` |
| `GET /api/conversations/{noteId}/messages?before&limit` | `ConversationPageResponse` | 404 `RUN4041` |

- 판정 순서: `admission/decisions.json`·`cancel/cases.json`·`cursors/cases.json`의 `precedence`. 같은 의미 재전송(replay)은
  Note·Active Run·rate limit·잔액보다 먼저다. 구조 검사는 대상 조회보다 먼저다.
- `RUN4092`·`RUN4041`은 다른 사용자/다른 의미, 없음/타인/삭제 중을 구분하지 않는 동일 body다(존재 oracle 방지).
- 스트림 시작 전 오류만 JSON `ApiResponse`(`isSuccess/code/message/result`), 시작 후에는 terminal 이벤트로 끝난다. code별 message는 고정이다.
- 공개 run status는 `PENDING|RUNNING|CANCELING|COMPLETED|FAILED|CANCELED` — 내부 `RETRYING`은 `RUNNING`으로 투영한다.
- 브라우저가 결정하지 못하는 값(scope·usage·attempt·fence·history·참조·corpus·conversationId·authToken·locale 등)은 요청 필드가 없다.
- 상태 응답 `lastSeq`는 그 시점 마지막 durable seq다. terminal 상태 fixture는 `stream`에 적은 `streams/valid` fixture와 같은 run이고
  `lastSeq`가 그 stream의 frame 수와 같다(그대로 cursor로 보내면 0 frame 후 종료).
- heartbeat는 15초마다 `: heartbeat` comment(id·data·event·seq·저장 없음, `heartbeat.txt`; 위반 예제 `heartbeat-invalid.json`).
- **runId = 시나리오.** 같은 run을 설명하는 fixture(stream·상태·취소·대화 조회·cursor `runs`)는 runId·requestId·
  userMessageId·assistantMessageId를 공유하고, 다른 시나리오는 다른 runId를 쓴다. 따라서 소비자가 valid fixture를 runId로
  교차 색인해도 충돌하지 않는다(`test_fixtures_describing_one_run_agree_by_run_id`). 진행 중 run은 시점 snapshot이라
  non-terminal 상태가 서로 다를 수 있다(예: `status-running` RUNNING → `cancel-accepted-running` CANCELING). invalid fixture는 색인 대상이 아니다.
- stream 규칙 S1~S7: `manifest.stream_rules`. replay와 live의 유일한 허용 차이는 read-time `sourceReferences[].available`.

## 형식·경계 판정

- **패턴은 입력 전체와 일치해야 한다.** schema 패턴은 `$` 대신 절대 끝 `(?![\s\S])`로 끝난다 — Python `re`·Java의 `$`는
  끝 줄바꿈 하나 앞에서도 매치되므로 `"<uuid>\n"`을 통과시킨다. 소비자 구현은 full match(Java `matches()`, Python `fullmatch`)로 한다.
- ID: `requestId`·`runId`·`messageId`·`attachmentId`는 소문자 canonical UUID. `noteId`는 선행 0 없는 10진 문자열 1..9223372036854775807
  (Java `Long.MAX_VALUE`, 초과는 404가 아니라 `RUN4001`). `noteAssetId`는 opaque `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`.
- event id/cursor: `{runId}:{seq}` — 소문자 UUID, seq 1..9007199254740991(2^53-1), 선행 0 없음. 상한은 schema 패턴 자체에 들어 있다.
  `cursors/cases.json`의 `schemaRejects`가 true인 값은 `EventId` schema가 거부하고, false인 400은 범위(마지막 seq 초과)·header/query 불일치다.
  `thenLive`는 `runs.<run>.status`가 terminal이 아닐 때만 true다(`thenLiveRule`).
- **길이(D6)**: `text`·`latex` 각각 **strip 후 1~10,000 code point**(합의 초안 §3.2 문구). 원문 기준이 아니다 — 양끝 공백이 붙어 원문이
  10,000을 넘어도 strip 후 10,000이면 접수한다(`text-at-limit-surrounded-by-blanks`). schema는 비어 있지 않음·공백만이 아님만 검사하고
  상한은 계약 검사(`RUN4001`, `schema_rejects: false`)다. 단위는 code point다 — Java `String.length()`·JS `.length`(UTF-16 단위)로
  세면 astral 문자 fixture(`text-at-limit-astral`·`text-too-long-astral`)에서 틀린다.
- **strip 집합**: internal-run-v1과 같은 Python `str.strip()` 집합 — `\t \n \v \f \r U+001C~U+001F space U+0085 U+00A0 U+1680
  U+2000~U+200A U+2028 U+2029 U+202F U+205F U+3000`. Java `String.strip()`/`trim()`은 U+00A0·U+202F 등을 남기므로 이 집합을 직접 구현한다
  (`fingerprint/same-non-java-whitespace`).
- **request fingerprint**: `requestId` 제외, `text`·`latex` strip, 비순서 배열 정렬, `[]`·`null`은 부재와 같음, `noteId` 부재/존재는 다름
  → internal-run-v1 `canonical_json` → sha256. **fingerprint 전에 기본값을 채우지 않는다** — `requestedOutputs: []`(Brain 추론)를
  `["text"]`로 바꿔 정규화하면 안 된다(`fingerprint/different-outputs-explicit-vs-inferred`).
- `turnAttachmentIds` wire 상한 10(실제 개수·용량은 Server policy → `RUN4221`).

## 기존 계약과의 일관성

- `streams/`의 activity frame은 activity-v1 source fixture를 Brain `to_browser_event_payload`로 변환한 값이고,
  `run.completed`/`run.failed` payload는 terminal-v2 `pairs/*.json`의 browser payload와 `assistantMessageId`만 다르다(테스트가 byte 비교).
  terminal-v2 pair는 publication-v1 valid에서 조립되므로 clarification/unsupported_only/substantive의 공개 구조도 같다.
- 대화 조회의 `assistantResponse`는 terminal-v2 `AssistantResponseV1`을 그대로 `$ref`한다. 대화 조회 예제는 메시지가 `createdAt`
  오름차순이고 assistant마다 run이 다르며, 각 assistant 앞에 같은 run의 user 메시지가 있다.
- 기존 fixture의 의미·파일은 바꾸지 않았다. 참고: terminal-v2 browser schema의 opaque ID 패턴은 `$`로 끝난다(이 artifact 밖,
  terminal-v2 변경 대상).

## 합의 초안 §11.1 예제와의 대응

- `replay-from-cursor` → `cursors/cases.json`(`valid-middle`·`valid-active` 등 — stream 사본을 두지 않는다).
- `retrying-projected-running` → `responses/invalid/status-retrying`(RETRYING 노출 거부)과 `status-running`.
- `events/invalid` → `streams/invalid`. `heartbeat-with-id` → `heartbeat-invalid.json`(frame이 아니라 SSE comment라 따로 둔다).
- cancel body 있음 → `cancel/cases.json`(`empty-object-body`·`reason-body`, 400 `RUN4001`).

## 파일
### requests/valid

| 파일 | 판정 근거 |
|---|---|
| `empty-arrays` | 빈 requestedTasks/Outputs·turnAttachmentIds — Brain이 문장에서 추론한다. |
| `existing-note-full` | 모든 선택 필드를 채운 기존 Note 요청. |
| `first-no-note` | noteId 없는 홈 첫 질문 — Server가 Note를 자동 생성한다. |
| `latex-at-limit-surrounded-by-blanks` | latex도 strip 후 10000 code point로 판정한다. |
| `natural-language-variant` | 자연어 미지원 의도(변형 문제 생성)는 wire 오류가 아니다 — 접수되어 unsupported_only로 COMPLETED 된다(streams/unsupported-only-completed). |
| `note-id-long-max` | noteId 상한 = Java Long.MAX_VALUE(BIGINT). |
| `text-at-limit-astral` | astral 문자(U+1D465) 10000개 — 길이는 UTF-16 단위가 아니라 code point. |
| `text-at-limit-surrounded-by-blanks` | strip 후 10000 code point — 상한은 strip 후 길이로 판정한다(원문은 상한 초과). |
| `text-at-limit` | text 10000 code point 경계값. |
| `viewer-focus-no-selection` | selection 없는 Viewer Focus(page 전체). |

### requests/invalid

| 파일 | 기대 | schema 거부 | 근거 |
|---|---|---|---|
| `attachment-not-uuid` | 400 `RUN4001` | 예 | turnAttachmentIds 항목은 UUID. |
| `blank-text` | 400 `RUN4001` | 예 | strip 집합 공백만 있는 text. |
| `duplicate-task` | 400 `RUN4001` | 예 | requestedTasks 중복. |
| `empty-latex` | 400 `RUN4001` | 예 | latex는 있으면 비어 있을 수 없다(null은 부재와 같음). |
| `forbidden-attempt` | 400 `RUN4001` | 예 | 브라우저는 attempt를 결정·전달하지 않는다. |
| `forbidden-auth-token` | 400 `RUN4001` | 예 | 브라우저는 authToken를 결정·전달하지 않는다. |
| `forbidden-budget` | 400 `RUN4001` | 예 | 브라우저는 budget를 결정·전달하지 않는다. |
| `forbidden-conversation-id` | 400 `RUN4001` | 예 | 브라우저는 conversationId를 결정·전달하지 않는다. |
| `forbidden-corpus-version` | 400 `RUN4001` | 예 | 브라우저는 corpusVersion를 결정·전달하지 않는다. |
| `forbidden-credit` | 400 `RUN4001` | 예 | 브라우저는 credit를 결정·전달하지 않는다. |
| `forbidden-deadline` | 400 `RUN4001` | 예 | 브라우저는 deadline를 결정·전달하지 않는다. |
| `forbidden-fence-token` | 400 `RUN4001` | 예 | 브라우저는 fenceToken를 결정·전달하지 않는다. |
| `forbidden-history` | 400 `RUN4001` | 예 | 브라우저는 history를 결정·전달하지 않는다. |
| `forbidden-input-scope` | 400 `RUN4001` | 예 | 브라우저는 inputScope를 결정·전달하지 않는다. |
| `forbidden-locale` | 400 `RUN4001` | 예 | 브라우저는 locale를 결정·전달하지 않는다. |
| `forbidden-retrieval-scope` | 400 `RUN4001` | 예 | 브라우저는 retrievalScope를 결정·전달하지 않는다. |
| `forbidden-run-id` | 400 `RUN4001` | 예 | 브라우저는 runId를 결정·전달하지 않는다. |
| `forbidden-scope` | 400 `RUN4001` | 예 | 브라우저는 scope를 결정·전달하지 않는다. |
| `forbidden-turn-attachment-refs` | 400 `RUN4001` | 예 | 브라우저는 turnAttachmentRefs를 결정·전달하지 않는다. |
| `forbidden-usage` | 400 `RUN4001` | 예 | 브라우저는 usage를 결정·전달하지 않는다. |
| `forbidden-user-id` | 400 `RUN4001` | 예 | 브라우저는 userId를 결정·전달하지 않는다. |
| `forbidden-verified-solution-refs` | 400 `RUN4001` | 예 | 브라우저는 verifiedSolutionRefs를 결정·전달하지 않는다. |
| `latex-too-long` | 400 `RUN4001` | 아니오(계약 검사) | latex strip 후 10001 code point — schema는 통과, 계약 검사(RUN4001). |
| `missing-request-id` | 400 `RUN4001` | 예 | requestId는 body 필수. |
| `missing-text` | 400 `RUN4001` | 예 | text 필수. |
| `non-uuid-request-id` | 400 `RUN4001` | 예 | UUID가 아닌 requestId. |
| `note-asset-id-trailing-newline` | 400 `RUN4001` | 예 | viewerFocus.noteAssetId 끝 줄바꿈. |
| `note-id-leading-zero` | 400 `RUN4001` | 예 | noteId 선행 0 금지. |
| `note-id-number` | 400 `RUN4001` | 예 | noteId는 JSON number가 아니라 10진 문자열. |
| `note-id-over-long-max` | 400 `RUN4001` | 예 | noteId가 Java Long.MAX_VALUE 초과 — 404가 아니라 형식 오류. |
| `note-id-trailing-newline` | 400 `RUN4001` | 예 | noteId 끝 줄바꿈. |
| `request-id-trailing-newline` | 400 `RUN4001` | 예 | requestId 끝 줄바꿈 — 패턴은 입력 끝까지 고정한다($의 줄바꿈 허용 금지). |
| `selection-inverted` | 400 `RUN4001` | 아니오(계약 검사) | left<right 위반 — schema는 통과하지만 계약 검사(RUN4001)가 거부한다. |
| `selection-out-of-range` | 400 `RUN4001` | 예 | selection 좌표는 [0,1]. |
| `text-too-long-astral` | 400 `RUN4001` | 아니오(계약 검사) | astral 문자 10001개 — UTF-16 길이로 세면 잘못 판정한다. |
| `text-too-long` | 400 `RUN4001` | 아니오(계약 검사) | text strip 후 10001 code point — schema는 통과, 계약 검사(RUN4001). |
| `too-many-attachments-wire` | 400 `RUN4001` | 예 | wire 상한 10개 초과(policy 한도와 별개). |
| `unknown-field` | 400 `RUN4001` | 예 | 요청은 closed — 알 수 없는 필드. |
| `unknown-task-variant` | 400 `RUN4001` | 예 | variant는 v1 wire enum이 아니다(자연어 의도와 구분). |
| `uppercase-request-id` | 400 `RUN4001` | 예 | requestId는 소문자 canonical UUID만 허용. |
| `video-output` | 400 `RUN4002` | 아니오(계약 검사) | video는 알려진 값이지만 Phase 1 미지원 — schema는 통과, RUN4002. |
| `viewer-focus-missing-page` | 400 `RUN4001` | 예 | viewerFocus.page 필수. |
| `viewer-page-zero` | 400 `RUN4001` | 예 | page는 1-based. |

### fingerprint

| 파일 | 관계 | 근거 |
|---|---|---|
| `different-attachment` | different | 첨부 변경. |
| `different-inner-whitespace` | different | strip은 양끝만 — 내부 공백은 의미 차이다. |
| `different-latex` | different | latex 변경. |
| `different-note-id-added` | different | noteId 부재와 존재는 다르다 — 받은 run.created.noteId를 넣어 재전송하면 RUN4092. |
| `different-outputs-explicit-vs-inferred` | different | ['text'] 명시와 [](추론)은 다른 의미다 — fingerprint 전에 기본값을 채우지 않는다. |
| `different-selection` | different | selection 변경. |
| `different-tasks` | different | requestedTasks 변경. |
| `different-text` | different | text 변경. |
| `different-viewer-page` | different | Viewer Focus page 변경. |
| `same-array-order` | same | 비순서 배열은 정렬된다. |
| `same-empty-array-vs-absent` | same | 빈 배열과 부재는 같다. |
| `same-non-java-whitespace` | same | U+00A0·U+0085·U+202F 양끝 공백도 strip된다 — Java String.strip()은 U+00A0·U+202F를 남기므로 strip 집합을 직접 구현해야 한다. |
| `same-null-selection` | same | selection null과 부재는 같다. |
| `same-null-vs-absent` | same | null과 부재는 같다. |
| `same-other-request-id` | same | requestId는 의미 문서에서 제외된다. |
| `same-surrounding-whitespace` | same | text·latex 양끝 공백은 strip된다. |

### admission/decisions.json cases

| case | 기대 | 근거 |
|---|---|---|
| `new-accepted` | 200 stream_new | 정상 신규 접수 — seq 1 run.created부터. |
| `new-without-note` | 200 stream_new | noteId 없는 첫 요청 — Note 자동 생성. |
| `duplicate-same-user-running` | 200 stream_replay | 같은 사용자·같은 의미 재전송 — 기존 run replay 후 live 합류. |
| `duplicate-same-user-completed` | 200 stream_replay | terminal run 재전송 — terminal까지 replay 후 종료. |
| `duplicate-different-meaning` | 409 RUN4092 | 같은 사용자, 다른 의미의 requestId 재사용. |
| `duplicate-other-user` | 409 RUN4092 | 다른 사용자의 requestId — 상태·runId·noteId 비노출, 다른 의미와 동일 body. |
| `duplicate-with-note-id-added` | 409 RUN4092 | run.created.noteId를 추가해 재전송 — 다른 의미. |
| `duplicate-wins-over-active-run` | 200 stream_replay | 이미 접수된 요청의 재전송은 자기 자신 때문에 409 Active Run을 받지 않는다. |
| `duplicate-not-rate-limited` | 200 stream_replay | 재조회는 rate limit·잔액 대상이 아니다. |
| `active-run-conflict` | 409 RUN4091 | 같은 Note에 RUNNING run — activeRunId가 담긴 409. |
| `active-run-canceling-conflict` | 409 RUN4091 | CANCELING도 Active Run이다. |
| `note-missing` | 404 RUN4041 | 없는 Note. |
| `note-other-user` | 404 RUN4041 | 타인 Note — 존재 비노출. |
| `note-deleting` | 404 RUN4041 | 삭제 중 Note. |
| `note-check-before-active-run` | 404 RUN4041 | Note 권한 검사가 Active Run보다 먼저 — 타인 Note의 activeRunId를 노출하지 않는다. |
| `attachment-other-note` | 422 RUN4221 | 다른 Note의 첨부. |
| `attachment-policy-exceeded` | 422 RUN4221 | policy 개수/용량 한도 초과. |
| `rate-limited` | 429 RUN4291 | 사용자별 반복 요청 제한. |
| `active-run-before-rate-limit` | 409 RUN4091 | Active Run 409가 rate limit보다 먼저(상태 복구에 필요). |
| `insufficient-balance` | 402 RUN4021 | 잔액 부족. |
| `schema-invalid` | 400 RUN4001 | 구조 위반은 requestId 조회보다 먼저. |
| `unsupported-output` | 400 RUN4002 | video output. |
| `unauthenticated` | 401 AUTH4010 | 인증 실패가 가장 먼저. |

### streams/valid

| 파일 | 최종 상태 | frame 수 | 내용 |
|---|---|---|---|
| `canceled-asset-deleting` | CANCELED | 6 | 의존 asset 삭제로 인한 취소 — cause=asset_deleting. |
| `canceled-pending` | CANCELED | 2 | PENDING 취소 — run.canceling 없이 run.canceled. |
| `canceled-running` | CANCELED | 8 | RUNNING 취소 — canceling 뒤 진행 Activity만 canceled. |
| `clarification-completed` | COMPLETED | 7 | clarification_only — 1 page, source 없음, completed(보상은 Server 정책). |
| `failed-invalid-attachment` | FAILED | 2 | invalid_attachment — reason sub-code만 추가. |
| `failed-verification` | FAILED | 8 | verification_failed — page 이벤트·풀이 없음. |
| `replay-source-unavailable` | COMPLETED | 12 | 같은 run을 asset 삭제 후 replay — read-time available만 다르고 id·seq·label snapshot·plainText는 같다. |
| `substantive-completed` | COMPLETED | 14 | substantive 2-page 완료 — page 경계 2쌍 뒤 run.completed(terminal-v2 multi-page). |
| `substantive-with-source` | COMPLETED | 12 | source chip이 있는 substantive(terminal-v2 direct-verified, available=true). |
| `unsupported-only-completed` | COMPLETED | 7 | 자연어 변형 요청의 unsupported_only 완료. |

### streams/invalid

| 파일 | 위반 규칙 | schema 거부 | 근거 |
|---|---|---|---|
| `activity-without-start` | S6 | 아니오(순서 규칙) | started 없는 activity 후속 이벤트. |
| `assistant-message-id-mismatch` | S5 | 아니오(순서 규칙) | assistantMessageId가 run.created와 다름. |
| `attempt-in-frame` | S7 | 예 | frame에 attempt. |
| `cancel-message-mismatch` | S7 | 예 | cause별 고정 message와 다름. |
| `cancel-unknown-cause` | S7 | 예 | 허용되지 않은 cancel cause. |
| `event-after-terminal` | S2 | 아니오(순서 규칙) | terminal 뒤 activity. |
| `failure-class-in-run-failed` | S7 | 예 | run.failed에 failureClass. |
| `fence-token-in-payload` | S7 | 예 | payload에 fenceToken. |
| `first-not-run-created` | S1 | 아니오(순서 규칙) | 첫 frame이 run.created가 아님. |
| `non-uuid-run-id` | S7 | 예 | Server 소유 frame runId는 UUID. |
| `page-after-canceling` | S3 | 아니오(순서 규칙) | run.canceling 뒤 page 경계. |
| `page-id-not-in-completed` | S4 | 아니오(순서 규칙) | page 경계 ID가 run.completed에 없음. |
| `page-in-failed-run` | S4 | 아니오(순서 규칙) | failed run에 page 경계. |
| `page-order-mismatch` | S4 | 아니오(순서 규칙) | pageIndex 순서가 run.completed pages와 다름. |
| `page-unpaired` | S4 | 아니오(순서 규칙) | page.started만 있고 completed 없음. |
| `retrying-status-in-run-created` | S7 | 예 | 내부 RETRYING/비PENDING status 노출. |
| `run-created-missing-note-id` | S7 | 예 | run.created에 noteId 누락. |
| `run-id-mismatch` | S5 | 아니오(순서 규칙) | frame runId가 섞임. |
| `seq-gap` | S1 | 아니오(순서 규칙) | seq 빈틈. |
| `source-event-id-in-frame` | S7 | 예 | frame에 sourceEventId. |
| `two-terminals` | S2 | 아니오(순서 규칙) | terminal 두 개. |

### cursors/cases.json

runs: `terminal` = `5e186280-06ec-5099-8087-8de875b2b83b` COMPLETED, lastDurableSeq 14, `active` = `eeff258f-0aa4-5728-a27a-3c05ac927a57` RUNNING, lastDurableSeq 4

| case | run | header | query | schema 거부 | 기대 |
|---|---|---|---|---|---|
| `no-cursor` | terminal | – | – | 아니오 | 200 replay seq 1~ 후 종료 |
| `no-cursor-active` | active | – | – | 아니오 | 200 replay seq 1~ + live |
| `valid-middle` | terminal | `{runId}:3` | – | 아니오 | 200 replay seq 4~ 후 종료 |
| `valid-active` | active | `{runId}:2` | – | 아니오 | 200 replay seq 3~ + live |
| `valid-query` | active | – | `{runId}:2` | 아니오 | 200 replay seq 3~ + live |
| `header-and-query-equal` | active | `{runId}:2` | `{runId}:2` | 아니오 | 200 replay seq 3~ + live |
| `last-seq-terminal` | terminal | `{runId}:14` | – | 아니오 | 200 replay seq 15~ 후 종료 (0 frame) |
| `last-seq-active` | active | `{runId}:4` | – | 아니오 | 200 replay seq 5~ + live (0 frame) |
| `uppercase-uuid` | terminal | `5E186280-06EC-5099-8087-8DE875B2B83B:3` | – | 예 | 400 RUN4003 |
| `seq-zero` | terminal | `{runId}:0` | – | 예 | 400 RUN4003 |
| `seq-leading-zero` | terminal | `{runId}:03` | – | 예 | 400 RUN4003 |
| `seq-negative` | terminal | `{runId}:-1` | – | 예 | 400 RUN4003 |
| `seq-over-2-53` | terminal | `{runId}:9007199254740992` | – | 예 | 400 RUN4003 |
| `seq-16-digit-max` | terminal | `{runId}:9999999999999999` | – | 예 | 400 RUN4003 |
| `seq-at-max-beyond-last` | terminal | `{runId}:9007199254740991` | – | 아니오 | 400 RUN4003 |
| `trailing-newline` | terminal | `{runId}:3\n` | – | 예 | 400 RUN4003 |
| `no-separator` | terminal | `{runId}` | – | 예 | 400 RUN4003 |
| `surrounding-space` | terminal | ` {runId}:3` | – | 예 | 400 RUN4003 |
| `seq-only` | terminal | `3` | – | 예 | 400 RUN4003 |
| `beyond-last-seq` | terminal | `{runId}:15` | – | 아니오 | 400 RUN4003 |
| `header-query-mismatch` | active | `{runId}:2` | `{runId}:3` | 아니오 | 400 RUN4003 |
| `other-run` | terminal | `fc745f72-47d7-5710-9d85-8ec947f08e4e:3` | – | 아니오 | 400 RUN4004 |
| `other-run-query` | active | – | `fc745f72-47d7-5710-9d85-8ec947f08e4e:1` | 아니오 | 400 RUN4004 |
| `run-missing` | missing | `{runId}:3` | – | 아니오 | 404 RUN4041 |
| `run-other-user` | other_user | `{runId}:3` | – | 아니오 | 404 RUN4041 |

### cancel/cases.json

| case | body | run | 기대 | 근거 |
|---|---|---|---|---|
| `no-body` | – | running | 200 OK | body 없음 — 정상 취소. |
| `empty-object-body` | `{}` | running | 400 RUN4001 | cancel은 body가 없다 — `{}`도 closed 위반. |
| `reason-body` | `{"reason":"user"}` | running | 400 RUN4001 | 취소 사유 등 어떤 필드도 받지 않는다(무시하지 않는다). |
| `repeat` | – | canceling | 200 OK | 멱등 — 이미 CANCELING이면 already_canceling. |
| `terminal` | – | completed | 200 OK | terminal run은 already_terminal. |
| `pending` | – | pending | 200 OK | PENDING은 즉시 CANCELED(run.canceling 없이 run.canceled). |
| `run-missing` | – | missing | 404 RUN4041 | 없는 run. |
| `run-other-user` | – | other_user | 404 RUN4041 | 타인 run — 없는 run과 같은 404 body(존재 비노출). |
| `body-before-run-lookup` | `{}` | missing | 400 RUN4001 | body 구조 검사가 run 조회보다 먼저(admission과 같은 원칙). |

### responses/valid

| 파일 | schema | HTTP | 같은 run의 stream |
|---|---|---|---|
| `cancel-accepted-pending` | `RunCancelResponse` | 200 | – |
| `cancel-accepted-running` | `RunCancelResponse` | 200 | – |
| `cancel-already-canceling` | `RunCancelResponse` | 200 | – |
| `cancel-already-terminal` | `RunCancelResponse` | 200 | – |
| `conversation-active-run` | `ConversationPageResponse` | 200 | – |
| `conversation-clarification-failed-canceled` | `ConversationPageResponse` | 200 | – |
| `conversation-completed` | `ConversationPageResponse` | 200 | – |
| `error-auth4010` | `ErrorResponse` | 401 | – |
| `error-auth4012` | `ErrorResponse` | 401 | – |
| `error-auth4013` | `ErrorResponse` | 401 | – |
| `error-run4001` | `ErrorResponse` | 400 | – |
| `error-run4002` | `ErrorResponse` | 400 | – |
| `error-run4003` | `ErrorResponse` | 400 | – |
| `error-run4004` | `ErrorResponse` | 400 | – |
| `error-run4021` | `ErrorResponse` | 402 | – |
| `error-run4041` | `ErrorResponse` | 404 | – |
| `error-run4091` | `ErrorResponse` | 409 | – |
| `error-run4092` | `ErrorResponse` | 409 | – |
| `error-run4221` | `ErrorResponse` | 422 | – |
| `error-run4291` | `ErrorResponse` | 429 | – |
| `status-canceled` | `RunStatusResponse` | 200 | `canceled-pending` |
| `status-canceling` | `RunStatusResponse` | 200 | – |
| `status-completed-clarification` | `RunStatusResponse` | 200 | `clarification-completed` |
| `status-completed-substantive` | `RunStatusResponse` | 200 | `substantive-completed` |
| `status-failed-invalid-attachment` | `RunStatusResponse` | 200 | `failed-invalid-attachment` |
| `status-failed` | `RunStatusResponse` | 200 | `failed-verification` |
| `status-pending` | `RunStatusResponse` | 200 | – |
| `status-running` | `RunStatusResponse` | 200 | – |

### responses/invalid

| 파일 | schema | 근거 |
|---|---|---|
| `cancel-outcome-mismatch` | `RunCancelResponse` | already_terminal인데 CANCELING. |
| `cancel-with-fence-token` | `RunCancelResponse` | 취소 응답에 fenceToken. |
| `conversation-active-run-deadline` | `ConversationPageResponse` | activeRun에 deadline. |
| `conversation-active-run-terminal-status` | `ConversationPageResponse` | activeRun은 PENDING\|RUNNING\|CANCELING만. |
| `conversation-completed-without-response` | `ConversationPageResponse` | COMPLETED assistant는 assistantResponse 필수. |
| `conversation-failure-class` | `ConversationPageResponse` | assistant failure에 failureClass. |
| `conversation-streaming-with-response` | `ConversationPageResponse` | STREAMING placeholder는 본문이 없다(Candidate 비공개). |
| `error-active-run-without-result` | `ErrorResponse` | RUN4091은 activeRunId 필수. |
| `error-message-mismatch` | `ErrorResponse` | code별 고정 message와 다름. |
| `error-result-on-request-id-conflict` | `ErrorResponse` | RUN4092는 기존 run 정보를 노출하지 않는다. |
| `status-completed-without-disposition` | `RunStatusResponse` | COMPLETED는 responseDisposition 필수. |
| `status-failed-without-failure` | `RunStatusResponse` | FAILED는 failure 필수. |
| `status-retrying` | `RunStatusResponse` | 내부 RETRYING은 RUNNING으로 투영해야 한다. |
| `status-running-not-cancelable` | `RunStatusResponse` | RUNNING은 cancelable=true. |
| `status-with-attempt` | `RunStatusResponse` | 상태 응답에 attempt. |
| `status-with-usage` | `RunStatusResponse` | 상태 응답에 내부 usage — 응답도 additive-tolerant이지만 내부 키는 금지. |

### heartbeat-invalid.json

| case | 근거 |
|---|---|
| `heartbeat-with-id` | heartbeat는 id가 없다 — cursor를 전진시키면 안 된다. |
| `heartbeat-with-data` | heartbeat는 data가 없다 — frame으로 저장·replay되지 않는다. |
| `heartbeat-as-event` | heartbeat는 event가 아니라 comment다. |

### 기타

| 파일 | 내용 |
|---|---|
| `schemas/browser-run.schema.json` | 모든 요청·응답·Server 소유 frame·`BrowserFrame` 정의 |
| `heartbeat.txt` | live heartbeat comment 원문 |
| `manifest.json` | 버전·registry·limits·stream_rules·파일 digest |
| `build_fixtures.py` | 이 CATALOG를 포함한 모든 fixture 생성기 |
