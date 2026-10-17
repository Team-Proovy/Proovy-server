# response-v1 계약 fixture 목록

버전: `response-fixtures-v1` — 2026-10-01 생성 (태스크 1.25, RSP-01 최초 고정).

변경 이력:

- 2026-10-01 PR #55 리뷰 반영 — `duplicate-source-ref-id`·
  `overlapping-source-pages`·`reversed-page-range`의 `source_references`를
  block 안에서 top-level catalog 위치로 이동해 파일명이 가리키는 검증 규칙이
  실제로 발동하도록 고쳤다(기존에는 `extra_forbidden`으로만 거부돼 규칙 미검증).
  `markdown/rejected/`에 `html-tag-newline.md`(태그 내부 개행)·
  `indented-code.md`(4칸 들여쓰기 코드)를 추가했다.

AssistantResponse v1의 Brain source(snake_case)·browser(camelCase) 표현과
SafeMarkdown v1 허용/거부 입력을 고정하는 versioned fixture다. 변경 시 새
버전 디렉터리를 만들고 이 파일에 변경 이유를 남긴다. 평가 정답·oracle과는
무관하며 어떤 fixture도 채점 기대값을 공개 DTO에 담지 않는다.

## valid/ — 허용되는 source payload

| 파일 | 내용 |
|---|---|
| verified-solution.json | main+thread_like 2페이지, verified solution·explanation·notice·rich_text 4종 block, step_marks anchor, 2개 source 참조(같은 asset 비중첩) |
| inconclusive-unverified.json | inconclusive 공개 — unverified 배지, `solution_id` 없음, verified/unconfirmed 혼합 mark, anchor 없는 미확인 단계 |
| bare-claim.json | 단계 0개 — 답 폴백 수용(과정 미검증)을 나타내는 partially_verified |
| check-block.json | 사용자 제출 풀이 인용(quoted_spans)·failed mark·first_error_step_id |
| clarification.json | 본문만 있는 안내형 — rich_text 단독, 출처 없음 |

## invalid/ — 거부되어야 하는 source payload

파일명이 깨는 규칙이다. 모두 `AssistantResponseV1` 검증 실패를 기대한다.

| 파일 | 위반 |
|---|---|
| dangling-source-marker.json | catalog에 없는 marker 참조 |
| duplicate-block-id.json / duplicate-page-id.json / duplicate-source-ref-id.json | 전역 ID 중복 |
| reversed-page-range.json | page_start > page_end |
| overlapping-source-pages.json | 같은 asset의 겹치는 page 범위 |
| anchor-out-of-bounds.json / anchor-splits-marker.json | 본문 범위 밖 anchor·marker 내부를 가리키는 anchor |
| unconfirmed-mark-without-reason.json | 사유 없는 unconfirmed mark |
| failed-mark-on-solution.json | 공개 solution의 failed mark(=reject 후보 공개) |
| brain-candidate-quote.json | Brain 생성 후보 인용(허용은 user_submission뿐) |
| html-in-body.json / fenced-code-in-body.json / forbidden-link-scheme.json / bare-dangerous-scheme.json / unclosed-math.json / malformed-source-marker.json | SafeMarkdown 금지 문법 |
| missing-main-page.json / two-main-pages.json | main page는 정확히 1개 |
| verified-badge-with-unconfirmed-mark.json / unverified-with-solution-id.json | 배지·mark·참조 정합 위반 |
| unknown-block-kind.json | 5종 밖 block kind |
| extra-field.json | 닫힌 모델 밖의 추가 필드 |

## markdown/ — SafeMarkdown v1 허용/거부 본문

`allowed/`는 `validate_safe_markdown`이 통과해야 하는 본문, `rejected/`는
각각 다른 규칙을 깨는 본문이다.

## browser/ — browser wire golden

`browser/verified-solution.browser.json`은 `to_browser_payload`가
`valid/verified-solution.json`에 Server 파생 label/available을 주입해 만든
camelCase 표현의 고정 스냅샷이다.
