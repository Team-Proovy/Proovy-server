#!/usr/bin/env bash
# Server ↔ 실제 Python Brain 로컬 연동 인수 (task 1.87).
#
# 격리 Postgres 컨테이너 → proovy-brain testkit supervisor → Gradle 연동 테스트 → 정리.
# Server CI는 proovy-brain(private)을 checkout할 수 없어 이 테스트를 skip한다(사용자 결정). 이 스크립트가 유일한 실행 경로다.
#
# 사용:
#   BRAIN_DIR=<proovy-brain checkout (정확한 commit)> scripts/brain-integration/run-local.sh [gradle --tests 패턴...]
#
# 환경변수 (기본값):
#   BRAIN_DIR                 필수. uv 환경이 준비된 proovy-brain checkout
#   PG_CONTAINER (proovy-postgres-brain-it)  PG_PORT (26432)   — 고정 compose 자원(15432 등)과 겹치지 않게 고른다
#   SUPERVISOR_PORT (26081)   BRAIN_API_PORT (26080)
#   GRADLE_IN_DOCKER (auto)   1이면 eclipse-temurin:21-jdk 임시 컨테이너(--network host)에서 Gradle을 돌린다
#   KEEP_RESOURCES (0)        1이면 끝나도 컨테이너·supervisor를 남긴다(디버깅용)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${BRAIN_DIR:?BRAIN_DIR must point at a proovy-brain checkout}"
PG_CONTAINER="${PG_CONTAINER:-proovy-postgres-brain-it}"
PG_PORT="${PG_PORT:-26432}"
SUPERVISOR_PORT="${SUPERVISOR_PORT:-26081}"
BRAIN_API_PORT="${BRAIN_API_PORT:-26080}"
PG_USER=brain_it_admin
PG_PASSWORD=brain-it-admin-dev-only
CONTROL_TOKEN="brain-it-control-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
SHARED_TOKEN="brain-it-shared-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
RUNTIME_PASSWORD=brain-it-runtime-dev-only
MIGRATION_PASSWORD=brain-it-migration-dev-only
SUPERVISOR_PID=""
LOG_DIR="$(mktemp -d)"

cleanup() {
  if [[ "${KEEP_RESOURCES:-0}" == "1" ]]; then
    echo "[brain-it] KEEP_RESOURCES=1 — leaving $PG_CONTAINER and supervisor pid ${SUPERVISOR_PID:-none}"
    return
  fi
  if [[ -n "$SUPERVISOR_PID" ]]; then
    # Brain API child는 supervisor가 새 session으로 띄운다 — 먼저 supervisor에게 멈추게 하고, supervisor 프로세스 그룹 전체를 정리한다
    curl -s -m 20 -X POST -H "X-Testkit-Control-Token: $CONTROL_TOKEN" -H "Content-Type: application/json" \
      -d '{"signal": "kill"}' "http://127.0.0.1:$SUPERVISOR_PORT/brain/stop" >/dev/null 2>&1 || true
    kill -- "-$SUPERVISOR_PID" 2>/dev/null || kill "$SUPERVISOR_PID" 2>/dev/null || true
    wait "$SUPERVISOR_PID" 2>/dev/null || true
  fi
  pkill -f "testkit.integration serve --host 127.0.0.1 --port $BRAIN_API_PORT" 2>/dev/null || true
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "${LOG_DIR:?}"
}
trap cleanup EXIT

GRADLE_IN_DOCKER="${GRADLE_IN_DOCKER:-auto}"
if [[ "$GRADLE_IN_DOCKER" == "auto" ]]; then
  if command -v javac >/dev/null 2>&1; then GRADLE_IN_DOCKER=0; else GRADLE_IN_DOCKER=1; fi
fi

# gradle <args...> — 호출 시점의 TEST_ENV를 전달한다
gradle() {
  local env_args=()
  if [[ "$GRADLE_IN_DOCKER" == "1" ]]; then
    for kv in "${TEST_ENV[@]}"; do env_args+=(-e "$kv"); done
    flock /tmp/proovy-s07-gradle.lock docker run --rm --network host --memory 1536m --cpus 2 \
      -u "$(id -u):$(id -g)" -e HOME=/home/gradle -e GRADLE_USER_HOME=/home/gradle/.gradle \
      -v "$HOME/.gradle:/home/gradle/.gradle" -v "$ROOT:$ROOT" -w "$ROOT" "${env_args[@]}" \
      eclipse-temurin:21-jdk sh ./gradlew "$@" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx768m
  else
    (cd "$ROOT" && env "${TEST_ENV[@]}" flock /tmp/proovy-s07-gradle.lock \
      ./gradlew "$@" --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx768m)
  fi
}
TEST_ENV=()

SERVER_HEAD="$(git -C "$ROOT" rev-parse HEAD)"
BRAIN_HEAD="$(git -C "$BRAIN_DIR" rev-parse HEAD)"
BRAIN_DIRTY="$(git -C "$BRAIN_DIR" status --porcelain | head -1)"
echo "[brain-it] server=$SERVER_HEAD brain=$BRAIN_HEAD${BRAIN_DIRTY:+ (brain checkout DIRTY)}"

# 컴파일을 먼저 끝낸다 — Brain child 기동(supervisor readiness 60s)이 컴파일 메모리 피크와 겹치지 않게
echo "[brain-it] compiling test classes"
gradle testClasses

echo "[brain-it] postgres $PG_CONTAINER on 127.0.0.1:$PG_PORT (tmpfs)"
docker run -d --name "$PG_CONTAINER" --tmpfs /var/lib/postgresql/data:rw,size=600m --memory 384m \
  -e POSTGRES_USER="$PG_USER" -e POSTGRES_PASSWORD="$PG_PASSWORD" -e POSTGRES_DB=postgres \
  -p "127.0.0.1:$PG_PORT:5432" postgres:15-alpine >/dev/null
wait_for() {  # wait_for <what> <command...> — 60초 안에 성공하지 못하면 실패
  local what=$1
  shift
  for _ in $(seq 1 60); do
    "$@" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "[brain-it] $what did not become ready in 60s" >&2
  return 1
}
wait_for postgres docker exec "$PG_CONTAINER" pg_isready -U "$PG_USER" -d postgres

echo "[brain-it] brain testkit supervisor 127.0.0.1:$SUPERVISOR_PORT (api $BRAIN_API_PORT)"
export PG_USER PG_PASSWORD PG_PORT CONTROL_TOKEN SHARED_TOKEN RUNTIME_PASSWORD MIGRATION_PASSWORD \
  SUPERVISOR_PORT BRAIN_API_PORT
setsid bash -c '
  cd "$0"
  env -i PATH="$PATH" HOME="$HOME" \
    TESTKIT_PG_ADMIN_URL="postgresql://$PG_USER:$PG_PASSWORD@127.0.0.1:$PG_PORT/postgres" \
    TESTKIT_CONTROL_TOKEN="$CONTROL_TOKEN" TESTKIT_SHARED_TOKEN="$SHARED_TOKEN" \
    TESTKIT_BRAIN_RUNTIME_PASSWORD="$RUNTIME_PASSWORD" TESTKIT_BRAIN_MIGRATION_PASSWORD="$MIGRATION_PASSWORD" \
    uv run --locked python -m testkit.integration supervise \
      --port "$SUPERVISOR_PORT" --api-port "$BRAIN_API_PORT"
' "$BRAIN_DIR" >"$LOG_DIR/supervisor.log" 2>&1 &
SUPERVISOR_PID=$!   # setsid → 이 PID가 프로세스 그룹 ID다
wait_for supervisor curl -sf -H "X-Testkit-Control-Token: $CONTROL_TOKEN" \
  "http://127.0.0.1:$SUPERVISOR_PORT/brain/status"

# Brain child 기동은 supervisor의 고정 readiness 대기(60s) 안에 끝나야 한다. 부하가 큰 호스트에서도 넘지 않게
# import·bytecode를 미리 데운다(같은 checkout, 같은 venv).
echo "[brain-it] warming brain imports"
(cd "$BRAIN_DIR" && env -i PATH="$PATH" HOME="$HOME" uv run --locked python -c \
  'import proovy_brain.apps.api, proovy_brain.features.solve_execution.text_run, testkit.integration.app')

# 테스트 전에 child를 한 번 띄워 둔다(빈 warm-up database). 테스트의 /brain/start는 이 child를 죽이고 새로 띄우지만
# page cache가 데워져 있어 테스트 JVM과 겹쳐도 readiness 안에 뜬다.
docker exec "$PG_CONTAINER" psql -q -U "$PG_USER" -d postgres -c "CREATE DATABASE brain_it_warm" >/dev/null
curl -sf -X POST -H "X-Testkit-Control-Token: $CONTROL_TOKEN" -H "Content-Type: application/json" \
  -d '{"database": "brain_it_warm", "lease_ttl_seconds": 3}' \
  "http://127.0.0.1:$SUPERVISOR_PORT/brain/start" >/dev/null && echo "[brain-it] warm child ready"

TESTS=("$@")
if [[ ${#TESTS[@]} -eq 0 ]]; then
  TESTS=("com.proovy.integration.brain.*")
fi
# --rerun: 환경변수(testkit 주소·token)는 test task 입력이 아니라서, 이전 결과(예: testkit 없이 돈 18 skipped)가 남아 있으면
# UP-TO-DATE로 테스트 0건이 "통과"한다. 항상 실제로 실행하게 강제한다.
GRADLE_ARGS=(test --rerun)
for pattern in "${TESTS[@]}"; do
  GRADLE_ARGS+=(--tests "$pattern")
done

TEST_ENV=(
  "PROOVY_TEST_PG_URL=jdbc:postgresql://127.0.0.1:$PG_PORT/postgres"
  "PROOVY_TEST_PG_USER=$PG_USER" "PROOVY_TEST_PG_PASSWORD=$PG_PASSWORD" "PROOVY_REQUIRE_DB_TESTS=true"
  "PROOVY_BRAIN_TESTKIT_URL=http://127.0.0.1:$SUPERVISOR_PORT"
  "PROOVY_BRAIN_TESTKIT_CONTROL_TOKEN=$CONTROL_TOKEN" "PROOVY_BRAIN_TESTKIT_SHARED_TOKEN=$SHARED_TOKEN"
  "PROOVY_BRAIN_TESTKIT_RUNTIME_PASSWORD=$RUNTIME_PASSWORD"
  "PROOVY_BRAIN_EXPECTED_COMMIT=$BRAIN_HEAD" "PROOVY_REQUIRE_BRAIN_IT=true"
)

STARTED_AT="$(date +%s)"
set +e
gradle "${GRADLE_ARGS[@]}"
STATUS=$?
set -e

# 결과 검증: 이번 실행이 만든 연동 테스트 결과가 있고, 실제로 돌았고(tests > 0), skip이 0이어야 한다
verify_results() {
  local found=0 file tests skipped failures errors
  shopt -s nullglob
  for file in "$ROOT"/build/test-results/test/TEST-com.proovy.integration.brain.*.xml; do
    if [[ "$(stat -c %Y "$file")" -lt "$STARTED_AT" ]]; then
      echo "[brain-it] stale result (not produced by this run): $file" >&2
      return 1
    fi
    tests=$(grep -o 'tests="[0-9]*"' "$file" | head -1 | tr -dc '0-9')
    skipped=$(grep -o 'skipped="[0-9]*"' "$file" | head -1 | tr -dc '0-9')
    failures=$(grep -o 'failures="[0-9]*"' "$file" | head -1 | tr -dc '0-9')
    errors=$(grep -o 'errors="[0-9]*"' "$file" | head -1 | tr -dc '0-9')
    echo "[brain-it] $(basename "$file" .xml): tests=$tests skipped=$skipped failures=$failures errors=$errors"
    if [[ "${tests:-0}" -eq 0 || "${skipped:-1}" -ne 0 || "${failures:-1}" -ne 0 || "${errors:-1}" -ne 0 ]]; then
      return 1
    fi
    found=$((found + 1))
  done
  if [[ $found -eq 0 ]]; then
    echo "[brain-it] no real-Brain integration result was produced" >&2
    return 1
  fi
}
if [[ $STATUS -eq 0 ]] && ! verify_results; then
  echo "[brain-it] FAILED: the real-Brain integration did not actually run green (skip or zero tests)" >&2
  STATUS=1
fi

echo "[brain-it] server=$SERVER_HEAD brain=$BRAIN_HEAD gradle exit=$STATUS"
echo "[brain-it] reports: $ROOT/build/reports/tests/test/index.html"
if [[ $STATUS -ne 0 ]]; then
  echo "[brain-it] supervisor log tail:"
  tail -n 40 "$LOG_DIR/supervisor.log" || true
fi
exit $STATUS
