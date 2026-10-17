# 브라우저 run API 계약 browser-run-v1 (task 1.75, #214)

이 문서는 인계용 안내다. 계약의 판정 기준은 아래 artifact이고, 이 문서나 OpenAPI가 아니다.

## 어디에 무엇이 있나

| 무엇 | 위치 |
|---|---|
| 계약 원본(SSOT) — schema·정상/거부 예제·판정표·CATALOG | proovy-brain `tests/fixtures/contracts/browser-run-v1/` |
| Server가 pin한 version | `src/test/resources/contracts/brain/PIN.json` — commit `303a9e4`, `browser-run-fixtures-v1` |
| 검증된 사본과 갱신 절차 | `src/test/resources/contracts/brain/` (`README.md`) |
| Java 계약 타입 | `com.proovy.domain.run.contract` |
| 소비 검사 | `src/test/java/com/proovy/domain/run/contract/` |

경로·오류 code·판정 순서·cursor·stream 규칙(S1~S7)은 Brain `CATALOG.md`가 정리한다. 단계 2에서 합의한
결정 D1~D10은 그 artifact와 #214에 기록되어 있다.

## Java 타입과 artifact 대응

| artifact | Java |
|---|---|
| `ConversationRunRequest` (closed) | `ConversationRunRequest` + `ConversationRunRequestValidator` (RUN4001/RUN4002) |
| 요청 fingerprint | `BrowserRequestFingerprint`, `CanonicalJson` (internal-run-v1 canonical JSON) |
| event id / cursor `{runId}:{seq}` | `RunEventCursor` (`resolve` → replay 시작점 또는 RUN4003/RUN4004) |
| durable frame / Server 소유 payload | `BrowserRunFrame`, `ServerFrameType`, `RunCreatedPayload` 등 |
| 상태·취소·대화 조회 응답 | `RunStatus`, `RunCancelResult`, `ConversationPage` 등 (`ApiResponse`로 감싼다) |
| 오류 envelope | `ErrorCode.RUN4001`~`RUN4291`, RUN4091만 `ApiResponse.failure(code, ActiveRunConflict)` |
| 공개 상태 투영 | `PublicRunStatus.from(AiRunStatus)` (RETRYING → RUNNING) |
| 고정 값(limits·heartbeat·frame version) | `BrowserRunContract` |

## 아직 없는 것

이 태스크는 계약 타입과 소비 검사만 추가했다. 아래는 후속 태스크가 이 계약대로 구현한다.

- controller·접수 transaction·SSE 연결·replay 저장소 (1.79·1.86 등)
- Brain 내부 client (1.76)
- Front 화면 (S08)

OpenAPI에는 component schema만 등록했고 경로는 없다.

## 계약을 바꿀 때

1. Server 이슈에서 의미 변경을 합의한다. 계약 의미의 소유자는 Server다.
2. proovy-brain artifact를 새 `consumer_version`과 `change_history` 항목으로 올리고 merge한다.
3. Server는 사본을 새 commit에서 다시 복사하고 `PIN.json`을 갱신한다(`README.md` 절차). Java 타입과 검사를 함께 고친다.
4. Front(S08)도 같은 commit과 version을 pin한다.

pin을 올리지 않고 사본이나 Java 상수만 고치면 `BrainContractMirrorTest`가 실패한다.
