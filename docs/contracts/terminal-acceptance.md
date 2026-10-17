# terminal 수락 검증 (task 1.81)

Brain terminal(`run.completed`·`run.failed`)을 저장하기 **전에** Server 기준으로 수락하거나 거부한다.
코드: `com.proovy.domain.run.terminal`. 저장·usage 확정·transaction 재확인은 1.82가 한다.

- 계약 기준: proovy-brain `303a9e4`의 terminal-v2(`terminal-fixtures-v2`)·response-v1(`response-fixtures-v1`)·
  publication-v1(`publication-fixtures-v1.2`). Brain `contracts/terminal.py`·`response.py`·`safe_markdown.py`·
  `response_serialization.py`의 구조 규칙을 Java로 옮겼다.
- 이 검사는 **metadata·구조 검사**다. 인용 의미 일치(grounding)와 수학 정확성은 판정하지 않는다(Brain 책임).
  provenance가 맞아도 답이 옳거나 그 page에 근거가 있다는 뜻이 아니다.

## 호출

```java
AcceptedTerminal accepted = TerminalAcceptance.accept(storedTerminalOrSourceEvent, scope); // 거부면 TerminalRejection
```

`TerminalAcceptanceScope`는 호출자가 사전 조회로 만든다.

| 값 | 원천 |
|---|---|
| `runId`·`attempt`·`fenceToken` | 현재 `ai_runs` (Brain에 보낸 wire run_id) |
| `userId`·`noteId` | run 소유자·Note |
| `assets[]` (`ScopedAsset`) | `run_asset_dependencies` + `assets`. `brainAssetId`·`brainContentVersion`·`pageUnderstandingVersion`은 이 run의 Brain 요청 snapshot이 보낸 wire 값을 그대로 넣는다(정수 ID·version의 wire 철자는 1.78 snapshot 매핑이 정한다). `ScopedAsset.of(dependency, asset, …)` |
| `solutions[]` (`ScopedSolution`) | 요청 `verified_solution_refs` + `verified_solutions` row. `ScopedSolution.of(row, publicId)` |
| `revisionLinks` | 같은 Note의 `solution_id → revises_solution_id` (순환 검사용, 아는 만큼) |

source 없는 텍스트 run은 `TerminalAcceptanceScope.textOnly(...)`.

## 검증된 저장 입력

`AcceptedCompletion`

- `responsePages`: canonical `response_pages[{response_page_id, presentation, title?, result_blocks}]`
  (Brain `model_dump(exclude_none)` 모양, 문자열 strip 정규화).
- `sourceReferences`: `[{source_ref_id, asset_id, page_start, page_end, display_label_snapshot}]`.
  `asset_id`는 **Server Note Asset ID(정수)**. label은 Brain publication이 정답 숨기기 검사를 한 `source_labels` 값이다.
- `plainText`: page title + block body 렌더(Brain `derive_plain_text`, fixture browser `plainText`와 같다).
  source label·artifact·summary는 넣지 않는다. 참고: plan 12는 "공개 block과 source label에서 파생"이라고 쓰지만
  Brain 구현·fixture는 label을 넣지 않는다. 이 구현은 fixture를 따랐다.
- `contentSha256`: `{response_pages, source_references, plain_text}`의 canonical JSON(`CanonicalJson`) sha256.
  이 태스크의 정의다. 1.82가 `chat_messages.content_sha256`으로 쓰거나 다른 정의를 정한다.
- `verificationSummary`: 공개 summary 배열(허용 문구만).
- `verifiedSolutions[]` (`VerifiedSolutionInput`): `verified_solutions` row 입력. `claims[{claim_id, statement, coverage}]`
  (V1 claims CHECK와 맞음), claim별 direct/supporting과 `overallCoverage` 보존. `contentSha256`은 artifact canonical
  JSON sha256. `revisesSolutionId`는 Server UUID로 해석된 값.
- `recheck` (`TerminalRecheck`): 1.82가 transaction 안에서 다시 확인할 항목(아래).

`AcceptedFailure`: code·failure_class·reason(invalid_attachment만)·고정 안전 message·recoverable.

## 거부 범주 (`TerminalRejectionReason`)

message는 범주·필드 경로·위반 종류만 담고 payload 값은 담지 않는다.

| 범주 | 예 |
|---|---|
| `NOT_PUBLICATION_TERMINAL` | activity event, 저장 전용 `run.canceled`(취소 경로가 처리) |
| `MALFORMED` | 알 수 없는 필드(code·stdout·metadata·video_target 등), 타입·길이·형식·enum(`inconclusive` coverage 포함) |
| `UNSAFE_MARKDOWN` | raw HTML·code·embed·위험 scheme·제어문자·math·marker 형식 |
| `RESPONSE_INTEGRITY` | ID 중복, main page 개수, phantom marker, anchor 범위·marker 분할, 같은 asset 범위 겹침 |
| `DISPOSITION_STRUCTURE` | clarification/unsupported에 block 추가·출처·summary, reason 짝, unsupported 고정 본문, 혼합 substantive의 clarification 위장 |
| `VERIFICATION_INTEGRITY` | 배지·mark·summary·artifact 불일치, 허용 밖 문구, reject(failed step)·summary 밖 claim이 든 artifact, **검증 배지 block이 공개하지 않은 artifact** |
| `SOURCE_CATALOG` | label과 catalog 불일치, artifact source ID가 catalog 밖 |
| `PROVENANCE` | 인용 범위 page provenance 누락(비연속 근거의 범위 합침 포함), 범위 밖·중복 provenance, asset version 불일치, guidance의 provenance |
| `FAILURE_PAYLOAD` | code·class·message·reason 불일치(내부 원문 message) |
| `FORBIDDEN_CONTENT` | 공개 텍스트·label·artifact의 signed URL 흔적(`X-Goog-Signature` 등) |
| `RUN_MISMATCH` | 다른 run_id·attempt·fence |
| `SOURCE_SCOPE` | dependency 밖 asset, 다른 user/Note, ACTIVE 아님, run 시작 뒤 content version 변경, page_count 초과, provenance version·sha256·understanding version 불일치 |
| `SOLUTION_REFERENCE_SCOPE` | 승인 안 된 existing reference, version·claim coverage 불일치, 다른 Note |
| `REVISION` | 승인 밖 대상, superseded 대상, 자기 자신, 같은 terminal artifact 대상, 한 대상 두 번 정정, 순환 이력 |

Brain보다 엄격한 Server 규칙은 두 가지다. 하나는 artifact 공개 요구다. Brain은 inconclusive 공개와 artifact가 함께 와도 거부하지 않는다. 다른 하나는 signed URL 검사다.
나머지는 fixture로 Brain 판정과 같음을 검사한다. 정수·bool은 JSON 정수·bool만 받는다(문자열 coercion 거부, fail-closed).

## 1.82 인계 — transaction 재확인 (TOCTOU)

수락 판정은 사전 조회 scope 기준이다. 이 태스크만으로 원자성을 보장하지 않는다. 저장 transaction에서 다음을 확인한다.

1. 현재 fence 조건부 terminal UPDATE가 1 row인지(run이 아직 비terminal이고 CANCELING이 아닌지).
2. `recheck.citedAssets`의 asset이 아직 ACTIVE(DELETING 아님)이고 `content_version`이 같은지. 같은 Note인지는 FK가 보장한다.
3. `recheck.revisedSolutions`가 아직 ACTIVE인지 확인한다. 같은 transaction에서 superseded로 바꾼다(`superseded_by`·`superseded_at`). 동시에 두 run이 같은 대상을 정정하면 하나만 이겨야 한다(조건부 UPDATE `WHERE status='ACTIVE'`).
4. `recheck.referencedSolutions`가 같은 Note에 남아 있는지 확인한다(Note 삭제 경합).
5. 같은 `source_event_id`에 다른 outcome이 오면 거부한다(terminal-v2 `redelivery.json` `changed_outcome`). event dedup(1.80)과 함께 처리한다.
   같은 의미의 재전달은 같은 `AcceptedTerminal`(content hash 포함)을 만든다.
6. Verified Solution `public_solution_id`는 run 안에서만 unique다(`uq_verified_solutions_run_public`).

## 검증하지 않은 부분

- 실제 S09 업로드·GCS·page metadata 경로: asset sha256·page_count·Page Understanding version은 seeded/fake 값으로만 검사했다.
- asset ID·content version의 Brain wire 철자: 1.78 snapshot 매핑에 달려 있고, 이 태스크는 scope가 그 값을 받는다고 가정한다.
- 인용 의미·정답 grounding(S11·Brain), 수학 재판정(Brain), browser projection(`available` hydration, 1.86).
- catalog entry를 아무 marker도 인용하지 않아도 수락한다(Brain과 같음).
