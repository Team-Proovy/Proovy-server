# proovy-brain 계약 fixture 사본 (검증된 mirror)

이 디렉터리는 **두 번째 SSOT가 아니다.** 브라우저 run API wire 계약(browser-run-v1, task 1.75)의
schema·정상/거부 예제의 원본은 proovy-brain `tests/fixtures/contracts/`이고, 여기에는 `PIN.json`의
commit에서 복사한 파일만 둔다(단계 2 합의 D9 — 복사본 + 해시 검증, 다운로드 token 없음).

| 경로 | 원본 |
|---|---|
| `browser-run-v1/**` | `tests/fixtures/contracts/browser-run-v1/**` (manifest·CATALOG·builder 포함 전체) |
| `terminal-v2/browser.schema.json` | `tests/fixtures/contracts/terminal-v2/browser.schema.json` (schema `$ref` 의존) |

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
