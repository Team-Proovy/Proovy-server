# run-snapshot-v1 — 실행 입력·history·참조·정책의 불변 snapshot (task 1.78, #217)

새 run을 접수할 때 브라우저 `ConversationRunRequest`를 권한·범위 검사 후 1.76 `InternalRunRequest`로 바꿔 고정하고,
이후 모든 Brain 전달을 저장된 snapshot에서만 다시 만든다. 이 문서는 Java 대응과 후속 태스크 인계 메모다.
의미 원본은 proovy-brain `docs/plan/22`(internal-run-v1)·`docs/plan/10`이고 wire DTO·fingerprint는 1.76 그대로 쓴다.

## Java 대응 (`com.proovy.domain.run.snapshot`)

| 타입 | 역할 |
|---|---|
| `RunSnapshotFactory.create(runId, userId, noteId, request, now)` | 접수 시점 구성. `RunSnapshot`을 돌려주거나 `RunSnapshotRejectedException`(RUN4041·RUN4221) |
| `RunSnapshot` | 저장할 값. `toPendingRun(userMsgId, assistantMsgId, createdAt)` → `AiRun`, `assetDependencies(createdAt)` → `RunAssetDependency` 행 |
| `RunSnapshotRestorer.delivery(AiRun, RunAssetAccess)` | 저장 run에서 현재 attempt·fence·누적 usage로 전달 요청 복원. fingerprint·deadline·식별자를 저장 열과 대조 |
| `RunSnapshotCodec` | `request_snapshot` 문서 ↔ `InternalRunRequest` (control·자격증명 필드 제거/부착) |
| `RunAssetAccess` | 한 전달에만 쓰는 grant·read URL. 저장하지 않는다 |
| `HistoryBound` | history token 경계(Brain `bound_history`와 같은 추정) |
| `RunSnapshotSources` / `JdbcRunSnapshotSources` | Note 범위 projection 조회(전체 메시지·엔티티 그래프를 읽지 않는다) |
| `RunAssetFacts` / `StoredRunAssetFacts` | 전송 시점 Viewer Focus·현재 첨부 사실. S09/S10이 교체한다 |
| `RunSnapshotProperties` (`proovy.run.snapshot.*`) | 접수 시점에만 읽는 정책 값 |

schema 변경은 없다. V1의 `ai_runs` snapshot 열과 `run_asset_dependencies`를 그대로 쓴다.

## 저장 열

| 열 | 내용 |
|---|---|
| `request_snapshot` | internal-run-v1 wire(snake_case)에서 `attempt`·`fence_token`·`asset_access_grant`·`viewer_context.read_url`·`turn_attachments[].read_url`·`execution_options.usage`를 뺀 문서. 의미 snapshot·`request_deadline_at`·limits·budget version이 남는다 |
| `request_fingerprint` | 1.76 `InternalRunFingerprint` sha256 (Brain status 응답과 비교하는 값) |
| `policy_snapshot` | `snapshot_version`(run-snapshot-v1)·contract/consumer version·locale·budget version·limits·request_timeout·history 경계 기록(max_tokens·estimator·messages·estimated_tokens·truncated)·retrieval/asset version·첨부 한도 |
| `viewer_snapshot` | `viewer_context`(read_url 없음) + `content_version`. Viewer Focus가 없으면 NULL |
| `corpus_snapshot` | `retrieval_scope` + `assets[{asset_id, content_version}]` |
| `request_deadline_at`·`budget_policy_version` | `request_snapshot`의 값과 같다 |
| `run_asset_dependencies` | corpus 구성원마다 `CORPUS_SNAPSHOT`, Viewer Focus asset에 `VIEWER_FOCUS` (content version 포함) |

JWT·credit·grant·signed URL·fence는 어느 열에도 없다. V1 trigger가 snapshot 열·deadline 변경과 usage 감소를 거부한다.

## 구성 규칙

**history**
- Note의 `ai_runs.status = COMPLETED` run만, run의 `user_message_id`·`assistant_message_id`로 짝을 짓는다(#227 R6 결정 — 시각 정렬 짝이 아니다).
  실패·취소·진행 중 turn은 user 메시지까지 통째로 빠진다. 본문은 `chat_messages.plain_text`(공개 Result Block 파생)만 쓰고
  `response_pages`·Verified Solution 본문은 읽지 않으므로 숨긴 답·Candidate가 history로 들어갈 길이 없다.
- 최신 메시지부터 담는다. 추정 = strip한 본문 code point + 메시지당 8 (Brain `estimate_history_turn_tokens`와 같음).
  추정 합 ≤ 24,000, 메시지 ≤ 100, 메시지당 ≤ 50,000자. 넘치는 메시지를 만나면 거기서 멈춘다(중간 절단·건너뛰기 없음).
- **Brain과 다른 점**: Brain은 최신 메시지 하나는 상한을 넘어도 담는다. Server는 그것도 담지 않는다(그때 history는 비고
  `policy_snapshot.history.truncated = true`). 그래서 Server가 보낸 history는 Brain이 다시 잘라도 그대로다.
- 공백만인 본문은 메시지로 치지 않는다. 현재 input과 strip 후 같은 user 본문은 넣지 않는다(Brain이 거부한다). 그 turn의 assistant 답은 남는다.

**참조**
- Verified Solution Reference: 남은 history의 assistant 메시지에 묶인 solution만. 조회 시점 `status`(active·superseded)와
  `revises_solution_id`(정정 대상의 Brain ID)를 그대로 싣는다. 대상 생략 시 Active Solution 선택과 superseded 명시 비교는
  Brain이 이 값으로 구분한다(Server는 자연어 대상을 판정하지 않는다). 최신 50개.
- Brain neutral ID(`public_solution_id`)는 run 안에서만 unique다. 범위 안에서 겹치면 그 ID의 참조를 **모두 뺀다**(경고 로그, ID만).
  Brain 운영 경로는 run_id 기반 해시 ID라 겹치지 않는다. 겹치면 Brain은 참조 없이 clarification으로 끝난다.
- `problem_summary` = `problem_statement`를 strip 후 4,000 code point로, claim `statement`는 2,000으로 앞에서 자른다.
  저장 claims를 계약에 맞게 읽을 수 없는 solution은 빼고(경고 로그) 요청을 막지 않는다.
- Turn Attachment Reference: 남은 history의 user 메시지 첨부만. 최신 50개.
- 잘린 범위·다른 Note의 참조는 조회 단계에서 만들어지지 않고, 1.76 생성자가 history 밖 `source_message_id`를 다시 거부한다.

**Viewer Focus·corpus·현재 첨부**
- `RunAssetFacts`가 같은 사용자·Note에서 지금 쓸 수 있는 자산만 받아들인다. 아니면 RUN4221.
  기본 구현(S09 전): Viewer Focus는 ACTIVE PDF asset의 저장 metadata(sha256·page_count·file_size), 적용 version은 정책 값.
  이미지 asset은 pixel 사실이 아직 없어 거부한다. 현재 Turn Attachment는 업로드 staging이 없어 비어 있지 않으면 거부한다.
- 현재 첨부 한도(정책): 3개·총 30MB, PDF당 20MB·50 pages, 이미지당 15MB·40MP. 넘거나 resolver가 요청 ID를 모두 돌려주지 않으면 RUN4221.
- `corpus_version` = `corpus-` + sha256(canonical JSON `[{asset_id, content_version}]`, ACTIVE asset, id 순). 빈 corpus도 고정 값.
  Search Extraction·chunking·embedding version은 S10 전까지 정책 label이다. 준비 여부(corpus_not_ready)는 Brain이 판정한다.

**정책**
- locale(기본 ko-KR)·budget version·limits·`request_deadline_at = now + request-timeout`(기본 3m, µs 절단)을 설정에서 한 번 읽는다.
- 첫 전달 usage는 `ExecutionUsage.ZERO`(run 행 counter 0).

## 복원 규칙 (1.83·1.85)

- `RunSnapshotRestorer.delivery(run, access)`: attempt·fence는 run 행(조건부 UPDATE로 배정된 값), usage는 run 행의 `used_*`,
  나머지는 `request_snapshot`. 현재 설정·history·viewer·corpus를 읽지 않는다.
- 복원 fingerprint ≠ `request_fingerprint`, deadline ≠ `request_deadline_at`, run/request/user/note ID 불일치, 저장 문서에
  control·자격증명 필드, 계약 위반, 현재 첨부 read URL 누락이면 `RunSnapshotIntegrityException`. 다시 만들지 않는다 — 실패로 수습한다.
- 살아 있는 실행의 재전달은 1.76 `forRetry`를 써도 같다. 이 경로는 메모리에 요청이 없을 때(재시작·다른 worker)용이다.
- grant·read URL은 전달마다 `RunAssetAccess`로 새로 넘긴다(S09/S10 발급). grant는 `ai_runs.grant_digest`(digest·만료)만 저장한다.

## 인계 (다른 snapshot·history 정책을 만들지 않는다)

- **1.79 접수**: Note 권한(1.79 판정) 뒤, 같은 transaction에서 `create(...)` → `chat_messages` insert →
  `aiRunRepository.save(snapshot.toPendingRun(...))` → flush → `dependencyRepository.saveAll(snapshot.assetDependencies(now))`.
  `RunSnapshotRejectedException.getErrorCode()`가 browser-run-v1 오류(RUN4041·RUN4221)다. `create`는 Note 소유·ACTIVE를 다시 확인한다.
  같은 requestId 재전송(replay)은 `create`를 부르지 않는다. `now`는 run `created_at`과 같은 값을 넘긴다.
  admission 판정표의 `references` 단계(RUN4221)가 이 예외다.
- **1.83 coordinator**: attempt·fence를 배정한 뒤 `RunSnapshotRestorer.delivery`로 요청을 만든다. usage 누적은 run 행 counter를
  올리는 것으로만 한다(감소는 DB가 거부). deadline은 `request_deadline_at`에서만 읽는다.
- **1.85 회수**: status 조회의 fingerprint 대조값은 `ai_runs.request_fingerprint`다(`RunStatusResponse.matches`). 회수 경로도
  snapshot을 다시 만들지 않는다.
- rate limit은 snapshot을 만들지 않는다(1.79 판정 순서에서 snapshot 전에 끝난다).

## 미확인·후속

- **S09/S10**: 실제 업로드 staging·이미지 pixel metadata·asset별 적용 version·scoped resolver·grant/URL 발급은 없다.
  `StoredRunAssetFacts`를 교체하고, viewer의 적용 version을 정책 값에서 asset별 값으로 바꿀지 그때 정한다.
- **Note 상세 페이지(#227 R11)**: 이 태스크의 history 조회는 projection + DB `LIMIT`이다. `NoteServiceImpl.getNoteDetail`의
  전체 메시지 로딩은 대화 조회 API 구현 태스크에서 같은 run 기준 짝으로 바꾼다(이 PR은 건드리지 않았다).
- **Brain fixture**: 이 태스크는 새 fixture를 pin하지 않았다. pin된 fingerprint fixture 27개로 저장·복원 왕복을 검사한다.
- 같은 사용자가 이전과 똑같은 문장을 다시 보내면 이전 user 메시지는 history에서 빠지고 답만 남는다(Brain 중복 거부 규칙 때문).
