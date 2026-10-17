# proovy-brain 계약 fixture 사본 (검증된 mirror)

이 디렉터리는 **두 번째 SSOT가 아니다.** 브라우저 run API wire 계약(browser-run-v1, task 1.75)의
schema·정상/거부 예제의 원본은 proovy-brain `tests/fixtures/contracts/`이고, 여기에는 `PIN.json`의
commit에서 복사한 파일만 둔다(단계 2 합의 D9 — 복사본 + 해시 검증, 다운로드 token 없음).

| 경로 | 원본 |
|---|---|
| `browser-run-v1/**` | `tests/fixtures/contracts/browser-run-v1/**` (manifest·CATALOG·builder 포함 전체) |
| `terminal-v2/browser.schema.json` | `tests/fixtures/contracts/terminal-v2/browser.schema.json` (schema `$ref` 의존) |
| `internal-run-v1/**` | `tests/fixtures/contracts/internal-run-v1/**` (Server↔Brain 내부 실행 wire, task 1.76, pin `PIN-internal-run-v1.json`) |
| `terminal-v2/**` | `tests/fixtures/contracts/terminal-v2/**` (terminal 수락 검증, task 1.81, pin `PIN-terminal-v2.json`) |
| `response-v1/**`·`publication-v1/**` | 같은 경로 (task 1.81, pin `PIN-response-v1.json`·`PIN-publication-v1.json`) |

- `BrainContractMirrorTest`가 다음을 검사한다.
  - `PIN.json`의 `manifest_sha256`·`consumer_version`이 사본 manifest와 같다.
  - manifest `files`·`dependencies`의 모든 sha256이 사본과 같다.
  - 목록 밖 파일이 없다(`PIN.json`·`README.md` 제외).
- **손으로 고치지 않는다.** fixture가 틀렸으면 proovy-brain에서 고치고(새 `consumer_version`·`change_history`) 다시 pin한다.
- 계약 의미(경로·판정·오류)는 Server가 소유한다. 바꿀 때는 Server 이슈에서 합의한 뒤 Brain artifact를 새 version으로 올리고,
  Server(이 사본)와 Front가 그 version을 pin한다.

## 갱신 절차

1. proovy-brain에서 바뀐 artifact가 `dev`에 merge된 commit SHA를 확인한다(squash commit).
2. 이 디렉터리의 사본을 지우고 그 commit에서 다시 복사한다.

   ```bash
   SHA=<merge commit>
   DEST=src/test/resources/contracts/brain
   rm -rf "$DEST/browser-run-v1" "$DEST/terminal-v2"
   git -C <proovy-brain checkout> fetch origin dev
   git -C <proovy-brain checkout> archive "$SHA" \
     tests/fixtures/contracts/browser-run-v1 \
     tests/fixtures/contracts/terminal-v2/browser.schema.json \
     | tar -x --strip-components=3 -C "$DEST"
   ```

3. `PIN.json`의 `commit`, `consumer_version`(manifest 값), `manifest_sha256`(`sha256sum $DEST/browser-run-v1/manifest.json`)을 갱신한다.
4. Java 상수·DTO가 새 계약과 맞는지 `./gradlew test --tests 'com.proovy.domain.run.contract.*'`로 확인한다.
   consumer_version이 바뀌었는데 Java 쪽을 고치지 않았으면 `BrainContractMirrorTest`가 실패하도록
   `BrowserRunContractTestSupport.EXPECTED_CONSUMER_VERSION`도 함께 올린다.

## internal-run-v1 (task 1.76)

- pin: `PIN-internal-run-v1.json` (commit·`consumer_version` `internal-run-fixtures-v2`·manifest sha256).
- 이 artifact의 manifest에는 파일별 sha256이 없다. 그래서 PIN의 `files`에 pin commit에서 복사한 직후 계산한 sha256을 적는다.
  값은 사본에서 만들지 않고 Brain commit의 blob과 같은지 확인한 뒤 적는다.
- `InternalRunContractMirrorTest`가 manifest sha256·파일 sha256·목록 밖 파일·manifest가 나열한 fixture의 존재를 검사한다.
  `BrainContractMirrorTest`(browser-run-v1)는 이 pin이 소유한 경로를 목록 밖 파일에서 제외한다.

갱신 절차:

```bash
SHA=<merge commit>
DEST=src/test/resources/contracts/brain
rm -rf "$DEST/internal-run-v1"
git -C <proovy-brain checkout> fetch origin dev
git -C <proovy-brain checkout> archive "$SHA" tests/fixtures/contracts/internal-run-v1 \
  | tar -x --strip-components=3 -C "$DEST"
# PIN-internal-run-v1.json: commit, consumer_version(manifest 값), manifest_sha256, files(경로 → sha256)를 다시 쓴다.
(cd "$DEST" && find internal-run-v1 -type f ! -name manifest.json | sort | xargs sha256sum)
# 위 값이 Brain commit blob과 같은지 확인한다: git -C <proovy-brain checkout> show "$SHA:tests/fixtures/contracts/<path>" | sha256sum
```

consumer_version이 바뀌면 `InternalRunContract.CONSUMER_VERSION`도 올리고
`./gradlew test --tests 'com.proovy.domain.run.brain.*' --tests 'com.proovy.global.infra.brain.*'`로 확인한다.

## terminal-v2·response-v1·publication-v1 (task 1.81)

- pin: `PIN-terminal-v2.json`(`terminal-fixtures-v2`, manifest sha256 포함), `PIN-response-v1.json`(`response-fixtures-v1`),
  `PIN-publication-v1.json`(`publication-fixtures-v1.2`). 세 artifact 모두 파일별 sha256이 없어 PIN의 `files`에 적는다.
  response-v1·publication-v1은 manifest가 없어 `consumer_version`은 `CATALOG.md`의 `버전:` 줄 값이다.
- `terminal-v2/browser.schema.json`은 `PIN.json`(browser-run-v1 dependency)과 `PIN-terminal-v2.json`이 함께 소유한다.
  갱신할 때 두 pin을 같은 commit으로 맞춘다.
- `TerminalContractMirrorTest`가 해시·목록 밖 파일·manifest pair 존재를 검사한다. `BrainContractMirrorTest`는
  `PIN-*.json`이 소유한 경로를 목록 밖 파일에서 제외한다.

갱신 절차:

```bash
SHA=<merge commit>
DEST=src/test/resources/contracts/brain
rm -rf "$DEST/terminal-v2" "$DEST/response-v1" "$DEST/publication-v1"
git -C <proovy-brain checkout> archive "$SHA" tests/fixtures/contracts/terminal-v2 \
  tests/fixtures/contracts/response-v1 tests/fixtures/contracts/publication-v1 | tar -x --strip-components=3 -C "$DEST"
# PIN-<artifact>.json: commit, consumer_version, files(경로 → sha256), terminal-v2는 manifest_sha256도 다시 쓴다.
# 각 값이 Brain commit blob과 같은지 확인한다(위 internal-run-v1 절차와 같다).
```

consumer_version이 바뀌면 `TerminalContractMirrorTest`의 기대 version을 올리고
`./gradlew test --tests 'com.proovy.domain.run.terminal.*'`로 확인한다.
