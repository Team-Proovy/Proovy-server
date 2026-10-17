# internal-run-v1 fixture catalog

Java(Proovy-server)와 Python(Brain)이 같은 실행 요청 계약을 검증하기 위한
공유 golden fixture (task 1.43, docs/plan/22).

## 버전

- `consumer_version`: `internal-run-fixtures-v2`
- `contract_version`: `internal-run-v1`
- fingerprint 알고리즘: `sha256-canonical-json-v1`
  (`sha256(canonical_json(document))` 의 소문자 hex)

## canonical JSON 규칙 (`canonical.json`)

양쪽 구현이 byte-identical 출력을 만들기 위한 규칙이다:

- UTF-8 출력, 공백 없음.
- 객체 멤버는 키 Unicode 코드포인트 순 정렬, `null` 멤버는 생략
  (필드 부재와 명시 `null`은 같은 canonical 을 만든다).
- 문자열은 `"` `\` `\b` `\f` `\n` `\r` `\t` 와 U+0020 미만 제어문자,
  lone UTF-16 surrogate 만 `\uXXXX`(소문자)로 이스케이프한다.
  비ASCII 문자는 리터럴로 출력한다 (RFC 8785 호환).
- 정수는 평문 base-10 (`-0` → `0`). 비정수 IEEE-754 double 은
  shortest round-trip 자릿수를 평문으로 표기한다 (지수 없음 —
  `1e-05` → `0.00001`). 정수 double 은 정수로 출력한다 (`1.0` → `1`).
- **정규화 순서**: fingerprint 입력은 파싱 정규화 후의 모델 값이다.
  문자열 필드는 양 끝의 Unicode whitespace(Python `str.strip()` 집합 —
  U+0009–000D, U+001C–001F, U+0085, U+00A0, U+1680, U+2000–200A,
  U+2028, U+2029, U+202F, U+205F, U+3000)를 제거한 뒤 들어간다.
  Java `String.strip()`은 이 집합보다 좁으므로(U+00A0 등을 제거하지 않음)
  소비자는 같은 집합을 그대로 구현해야 지문이 일치한다.
  `normalization_cases`와 `fingerprint/normalize-whitespace.json`이
  이 규칙을 고정한다.
- **알려진 경계**: shortest round-trip 자릿수는 비정규(subnormal) double
  에서 언어별로 다를 수 있다(`5e-324` vs `4.9e-324`). v1 입력은 정상
  범위 double(Page Selection 좌표 등 유효 자릿수가 제한된 값)만 기대하며,
  그 밖의 입력은 계약 갱신 사유다.

`number_cases`/`string_cases`/`document_cases` 의 `input` → `canonical`
쌍으로 양쪽 serializer 를 검증한다.

## 디렉터리

- `requests/` — 파싱 가능한 요청 본문. `minimal`은 필수 필드만,
  `full`은 모든 선택 필드를 포함한다.
- `operations/` — status/cancel/cleanup 요청·응답 쌍과 판단 근거.
  `status-terminal`은 저장된 `run.completed` brain-source-v1 이벤트의
  재전달을 보여주고, `status-terminal-canceled`는 취소된 run 이 저장한
  `run.canceled` 의 재전달을 보여준다. `run.canceled`는 저장 전용
  variant — brain-source-v1 `SourceEvent` union 에는 들어가지 않으므로
  스트림 재전달 경로가 아니라 `RunStatusResponse.terminal` 을 통해서만
  소비된다. consumer 가 status 응답의 `terminal` 을 discriminated union
  으로 파싱한다면 `run.canceled` discriminator 를 허용해야 한다 —
  기존 variant 의 wire shape 는 변하지 않았다 (additive).
  `stream-request-id-bound`는 stream 종류의 첫 사례다 — `request` 는
  InternalRunRequestV1 본문, `bound_run_id` 는 `request_id` 가 이미 묶인
  run, `http_status` 409 의 `response` 본문은
  `{"error":"conflict","reason":"request_id_bound_to_other_run",
  "existing_run_id":...}` 를 고정한다.
- `decisions.json` — `decide_run_request` 의 4×4×2 전수 결정표
  (`run-decisions-v2`). Java 소비자는 같은 operation × record_state ×
  fingerprint_match 에 같은 decision 을 내놓아야 한다. v2는
  `binding_overrides` 섹션을 추가한다: `request_id` 가 다른 `run_id` 에
  묶인 stream 전달은 contention 표보다 먼저 평가되어 `reject` +
  `request_id_bound_to_other_run` 으로 거부된다 (라이브 레코드와
  cleanup tombstone 모두).
- `invalid/` — 계약 경계에서 거부되어야 하는 요청 (`rationale` + `request`).
- `fingerprint/` — 의미 지문 기대값. 각 파일은
  `relation`(`same`/`different`), `rationale`, `request`,
  `expected_canonical`, `expected_fingerprint` 를 가진다.

## fingerprint 포함/제외

**포함 (의미 입력)**: `contract_version`, `user_id`, `note_id`,
`conversation_id`, `input`(text·latex·requested_tasks·requested_outputs·
locale), `history`(message_id + sha256(role,text)), `verified_solution_refs`
(solution_id + sha256(problem_summary,claims)) ,
`turn_attachment_refs`(attachment_id·source_message_id·sha256·
page_understanding_version), `viewer_context`(asset_id·page·selection·
sha256·page_understanding_version), `retrieval_scope` 전체,
`turn_attachments`(attachment_id·sha256·page_understanding_version),
`execution`(budget_policy_version·limits).

**제외 (전송/운영 스탬프)**: `request_id`, `run_id`, `attempt`,
`fence_token`, `asset_access_grant`, 모든 `read_url`, 절대
`request_deadline_at`, `usage` 누적 counter.

**제외 (수명주기·표현 메타데이터)**: `status`/`revises_solution_id`와
solution ref의 `source_message_id`는 참조의 의미 내용이 아닌 수명주기
기록이다. `mime`·`byte_size`·`page_count`·pixel 필드·`display_label`은
`sha256`으로 이미 고정되는 바이트의 표현/파생 사실이다.
`upload_policy_version`·`image_normalization_version`은 업로드/정규화
파이프라인의 운영 버전이다 — sha256에서 유도되지는 않지만, 결과물의
의미는 `sha256`(바이트)과 `page_understanding_version`(이해 결과)이
이미 고정하므로 재파생 라벨이 바뀌어도 요청 의미는 같다.

비순서 의미의 컬렉션(requested_tasks·requested_outputs·claims·solution
refs·attachment refs·turn attachments)은 canonical 정렬되고, 시간 순서가
의미인 `history`는 순서를 유지한다.

## 변경 이력

- `internal-run-fixtures-v1` (2026-10-02, task 1.43): 최초 고정.
- `internal-run-fixtures-v1` (2026-10-05, task 1.47): `operations/`
  에 `status-terminal-canceled` 추가 — `RunStatusResponse.terminal`
  의 `run.canceled` variant 를 고정한다. additive 변경이므로
  consumer_version 은 유지한다.
- `internal-run-fixtures-v2` (2026-10-08, task 1.65): 다른 `run_id` 에
  묶인 `request_id` 의 stream 재전송을 409로 거부하는 identity 규칙을
  고정한다 — `operations/stream-request-id-bound`(응답 본문 `reason` +
  `existing_run_id`)와 `decisions.json` 의 `binding_overrides`
  (`run-decisions-v2`). 기존 fixture 의 wire shape 는 변하지 않았다
  (additive)지만, 소비자가 새 사유 어휘를 인식해야 하므로
  consumer_version 을 올린다.
