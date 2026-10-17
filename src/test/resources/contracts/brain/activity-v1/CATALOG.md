# activity-v1 계약 fixture 목록

버전: `activity-fixtures-v1` — 2026-10-01 생성 (태스크 1.27, ACT-01 최초 고정).

변경 이력:

- 2026-10-01 초기 버전 고정.
- 2026-10-01 PR #56 리뷰 반영(미배포 상태에서 보정): `no_relevant_content`는
  `pages`를 비워야 하는 규칙을 invalid fixture `no-match-with-pages.json`로
  고정하고, 금지 내용 fixture 7종에 `DO-NOT-LEAK` sentinel 값을 실제로
  주입해 누출 sweep이 실측을 검사하도록 했다.

Activity v1 payload + Brain source envelope(`brain-source-v1`)의
source(snake_case)·browser(camelCase) 표현을 고정하는 versioned fixture다.
변경 시 새 버전 디렉터리를 만들고 이 파일에 변경 이유를 남긴다. 평가
정답·oracle과는 무관하며 어떤 fixture도 채점 기대값이나 후보 본문을 담지
않는다.

## valid/ — 허용되는 source 이벤트

모든 파일은 동일한 envelope(context: `run-7`·`attempt 1`·`fence-9f8c`)를
공유하고 `source_event_id`는 `run·activity·revision·event kind`에서 도출한
결정론적 문자열이다.

| 파일 | 내용 |
|---|---|
| input-analysis-started.json | `started` — allowlist label + 선택 `response_page_id` |
| input-analysis-updated.json | `updated` — 안전한 status/message |
| solution-generation-updated.json | `updated` — `status=retrying` |
| solution-generation-failed.json | `failed` — allowlisted `code` + 안전한 message |
| asset-understanding-started.json | `started` — `reason` + `targets[]`(두 asset, 비중첩) |
| asset-understanding-updated.json | `updated` — 측정된 `processed_pages`/`total_pages` |
| asset-understanding-completed.json | `completed` — 정상 판정 `outcome=unreadable_pages` + 최종 측정값 |
| note-retrieval-completed.json | `completed` — `limited_pages_unresolved` + 제한 page 목록 |
| note-retrieval-conflict.json | `completed` — `conflicting_content` + 양쪽 page 목록 |
| code-execution-completed.json | `completed` — `method_label` + 측정 `duration_ms` |
| verification-updated.json | `updated` — `method_label` + 측정 `progress{checked,total}` |
| verification-completed.json | `completed` — 최종 `progress` |
| note-retrieval-canceled.json | `canceled` — 종료 outcome |

## invalid/ — 거부되어야 하는 source 이벤트

파일명이 깨는 규칙이다. 모두 `parse_source_event`에서
`ActivityPayloadError`를 기대한다.

| 파일 | 위반 |
|---|---|
| code-field-in-payload.json / stdout-in-payload.json | code·stdout 등 금지 필드를 payload에 포함(extra=forbid) |
| verification-step-verdict.json / verification-answer-leak.json | 검증 중 단계 판정·답을 payload에 포함 |
| unknown-label.json / wrong-kind-label.json | allowlist 밖 label·다른 kind의 label |
| fake-percent.json | 추정 진행률 필드 — 측정된 수치만 허용 |
| progress-over-total.json | checked > total |
| reversed-target-range.json / overlapping-targets.json | 뒤집힌 page 범위·같은 asset의 중첩 target |
| negative-duration.json | `duration_ms < 0` |
| url-in-message.json / control-char-in-message.json | 안전하지 않은 message |
| bad-match-status.json | allowlist 밖 `match_status` |
| limited-without-pages.json | `limited_pages_unresolved`인데 page 목록 없음 |
| no-match-with-pages.json | `no_relevant_content`인데 page 목록이 있음 — plan/21의 빈 answer provenance와 모순 |
| type-payload-mismatch.json | envelope `type`과 payload phase 불일치 |
| missing-attempt.json / naive-occurred-at.json | envelope 필수 필드 누락·tz 없는 시각 |
| bad-activity-id.json | 자유형(비중립) `activity_id` |
| run-event-type.json | `run.*` 타입 — 이 레이어는 `activity.*`만 소유 |
| seq-on-source-envelope.json | Server 소유 `seq`가 source envelope에 포함 |

## browser/ — 브라우저 golden

| 파일 | 내용 |
|---|---|
| verification-updated.browser.json | `verification-updated.json`의 camelCase 표현 — `attempt`/`fence_token`/`source_event_id` 등 source 전용 필드가 빠지고 `contractVersion`·`runId`·`occurredAt`·`type`·`payload`만 남는다 |
| asset-understanding-started.browser.json | `asset-understanding-started.json`의 camelCase 표현 — `responsePageId`·`pageStart`/`pageEnd` 매핑 확인용 |
