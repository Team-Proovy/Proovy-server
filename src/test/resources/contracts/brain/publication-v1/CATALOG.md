# publication-v1 계약 fixture 목록

버전: `publication-fixtures-v1.2` — 2026-10-12 Task 1.73의 claim별 verdict/basis 혼합 허용.
이전 `publication-fixtures-v1.1`은 2026-10-11 Task 1.71의 claim-only inconclusive 공개 정책 수정이다.
변경 이유: 검증 report의 합법적 claim별 분기를 균일 projection 불변식이 거부했다.
`chain-mixed-inconclusive.json`은 일부 주장 고정 안내, `stepped-mixed-basis.json`은
fallback reject+chain accepted check 공개를 고정한다. 내부 ProjectedClaim의 대상 여부는
기본 true boolean으로 추가하고 공개 wire 필드는 바꾸지 않는다. 기존 valid/invalid/expected는
보존한다. `invalid/chain-mixed-verdicts.json`은 failed step+accepted chain이므로 계속 invalid다.
최초 버전 `publication-fixtures-v1`은 2026-10-01 Task 1.26에서 고정했다.

변경 이유: 독립 답 비교가 inconclusive여도 모든 step은 verified일 수 있다.
이 조합은 잘못된 입력이 아니므로 `invalid/inconclusive-all-verified.json`은
원본을 보존한 역사 fixture로 재분류하며, 전용 회귀 테스트에서 unverified +
답 전체 미확인 고정 안내로 공개됨을 `expected/inconclusive-all-verified.json`과 비교한다.
다른 invalid 요청과 기존 valid/expected golden은 바꾸지 않는다. wire 버전은 `publication-v1` 그대로다.

`PublicationRequest`(Brain 내부 입력, snake_case)의 파싱·조립 결과를 고정하는
versioned fixture다. 입력은 이미 결정된 검증 판정의 *공개 어휘 투영*
(`ReportProjection`)과 caller가 완료한 공개 범위 판정(`DisclosureDecision`)만
담는다 — 검증을 다시 계산하지 않으며, 내부 reason/method 텍스트·코드·stdout·
리뷰 원문·provider 메타데이터는 입력에 표현될 수 없다. `expected/`는
`assemble_response`의 결정론적 산출(`published_source_payload`)의 고정
스냅샷이다. 평가 정답·oracle과 무관하다.

## valid/ — 파싱·조립에 성공해야 하는 요청

각 파일과 같은 이름의 `expected/` golden이 대응한다.

| 파일 | 내용 |
|---|---|
| direct-verified.json | 전 단계 실행 검증(accepted/direct) — verified 배지·단계 anchor·출처 카탈로그·label |
| supporting-logical-move.json | 논리 수사 미확인 단계를 인정한 supporting 수용 — partially_verified + 미확인 단계 요약 |
| inconclusive-unverified.json | 미확정 공개 — unverified 배지, `solution_id` 없음, 혼합 mark |
| user-check-first-error.json | 사용자 제출 풀이 검산 — quoted span·failed mark·first_error_step_id |
| check-with-correction.json | 사용자 검산(reject) + 별도 accepted 수정 풀이 한 페이지에 병기 |
| hide-answer.json | `hide_answer` — 오류 설명(check)·수정 풀이(solution) 모두 숨긴 구간 제외 후 공개 |
| mixed-substantive-notice.json | 지원 결과 + 고정 미지원 notice 병기(substantive + unsupported_notice) |
| clarification.json | clarification_only — 허용 reason + 안내 rich_text, 출처·검증 없음 |
| unsupported-only.json | unsupported_only — 고정 안내만 |
| zero-step-fallback.json | 단계 0개 답 폴백 수용 — partially_verified + 독립 계산 비교 label(과정 미검증) |
| unicode-repeated-anchor.json | 반복 `$x+1$` 구간을 code-point anchor로 구분 |
| multi-page.json | main + thread_like 페이지 순서 유지 |
| mixed-fallback-verdicts.json | 단계 0개 폴백의 claim별 독립 판정(1건 수용+1건 기각→report reject) |
| chain-mixed-inconclusive.json | chain claim 혼합 미확정+수용 → unverified, 일부 주장(중간 사실) 고정 안내 |
| stepped-mixed-basis.json | stepped fallback reject+chain accepted → check 공개, verified 단계 유지 |

## invalid/ — 거부되어야 하는 요청

아래 역사 fixture 예외를 제외하면 파일명이 깨는 규칙이다. `PublicationError`(파싱 경계에서는
`PublicationPayloadError`)를 기대하며, 오류 메시지·details에 입력 원문이
남지 않는다.

| 파일 | 위반 |
|---|---|
| reject-brain-candidate.json | reject 판정 Brain 후보 공개 시도 |
| hide-answer-missing-withheld.json | `hide_answer`인데 solution draft에 숨긴 구간 없음 |
| hide-solution-partial-withheld.json | `hide_solution`인데 solution draft 일부만 숨김 |
| hidden-text-in-title.json / hidden-text-in-body.json / hidden-text-in-notice.json / hidden-text-in-source-label.json | 숨긴 구간 원문이 제목·다른 본문·안내·출처 label에 그대로 등장 |
| hidden-text-matches-summary-label.json | 숨긴 구간 원문이 요약 method label(고정 문구)과 충돌 — 요약 표면도 검사됨 |
| anchor-overlaps-withheld.json / quoted-span-overlaps-withheld.json | 공개 anchor·인용 구간이 숨긴 구간과 겹침 |
| full-scope-with-withheld.json | `full` 공개인데 숨긴 구간 존재 |
| inconclusive-with-solution-id.json | 미확정 결과가 Verified Solution id 참조 |
| verified-without-solution-id.json | accepted 공개인데 `solution_id` 없음 |
| inconclusive-all-verified.json | **역사 fixture 예외(v1.1):** 과거 미확인 mark 부재로 거부했던 합법 조합. 이제 step verified를 보존하고 답 전체 미확인 안내로 공개(전용 테스트); 원본 경로·내용은 이력 보존 |
| clarification-with-sources.json / unsupported-with-clarification.json / substantive-with-clarification.json / substantive-without-pages.json / only-notice-substantive.json | disposition별 허용 조합 위반 |
| dangling-step-anchor.json | anchor가 report에 없는 step을 가리킴 |
| incomplete-source-labels.json / label-for-missing-source.json | catalog label 선언 불완전·미등록 id |
| unknown-block-kind.json / extra-field.json / internal-report-field.json | 임의 discriminator·닫힌 모델 밖 필드·내부 리포트 필드 주입 |
| unconfirmed-step-without-reason.json / pending-disclosure-judgment.json | 투영 입력 계약 위반·미완료 공개 판정 |
| withheld-out-of-bounds.json / overlapping-withheld.json | 숨김 구간 범위·중첩 위반 |
| noncanonical-draft-text.json | 앞뒤 공백이 있는 draft — strip이 좌표를 어겨 숨김 구간이 어긋남 |
| accepted-zero-evidence.json / direct-overclaim.json | 단계·폴백 증거 없는 accepted, supporting 단계를 direct로 과장 |
| rejected-without-failed-step.json | reject인데 실패 단계 증거 없음 |
| failed-step-wrong-reason.json / unsettled-logical-move-reason.json | failed 단계의 임의 사유·미정착 단계의 논리 수사 사유 과장 |
| dropped-claim-forgery.json / chain-mixed-verdicts.json | 요청 claim 누락을 숨긴 accept 위조·failed step이 있는 chain claim의 accepted 위조 |
| hide-solution-missing-id.json | accepted인데 `solution_id` 없음 — 전부 숨겨도 결합은 유지 |
