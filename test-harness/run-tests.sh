#!/bin/bash
# Runs the Folia server with both plugins, waits for the test plugin to drop
# a `test-results/ready` marker, prints the JUnit XML, then stops the server.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$ROOT/test-harness/server"
TIMEOUT=${TIMEOUT:-180}                # seconds to wait for tests to complete

JAVA_BIN="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}/bin/java"
[ -x "$JAVA_BIN" ] || { echo "java 21 not found at $JAVA_BIN"; exit 2; }

mkdir -p "$SERVER/plugins" "$SERVER/test-results"
rm -f "$SERVER/test-results/ready" "$SERVER/test-results/junit.xml"

# Copy reobf plugin jars
PLUGIN_JAR="$ROOT/plugin/build/libs/plugin-0.1.0-reobf.jar"
TEST_JAR="$ROOT/test-plugin/build/libs/test-plugin-0.1.0-reobf.jar"
[ -f "$PLUGIN_JAR" ] || { echo "plugin reobf jar missing — run ./gradlew build first"; exit 3; }
[ -f "$TEST_JAR" ]   || { echo "test-plugin reobf jar missing — run ./gradlew build first"; exit 3; }
cp -f "$PLUGIN_JAR" "$SERVER/plugins/folia-redstone-region.jar"
cp -f "$TEST_JAR"   "$SERVER/plugins/folia-redstone-region-tests.jar"

cd "$SERVER"
echo "[run-tests] launching folia (timeout=${TIMEOUT}s)"
"$JAVA_BIN" -Xms1G -Xmx2G \
  -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions \
  -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:+DisableExplicitGC \
  -Dcom.mojang.eula.agree=true \
  -jar folia.jar nogui > server.log 2>&1 &
SERVER_PID=$!
trap "kill $SERVER_PID 2>/dev/null; wait $SERVER_PID 2>/dev/null; true" EXIT

# Wait for test-results/ready or timeout
ELAPSED=0
while [ $ELAPSED -lt $TIMEOUT ]; do
  if [ -f "test-results/ready" ]; then
    echo "[run-tests] tests completed after ${ELAPSED}s"
    cat test-results/ready
    break
  fi
  if ! kill -0 $SERVER_PID 2>/dev/null; then
    echo "[run-tests] server died unexpectedly — last 50 log lines:"
    tail -50 server.log
    exit 4
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

if [ ! -f "test-results/ready" ]; then
  echo "[run-tests] timed out after ${TIMEOUT}s — last 80 log lines:"
  tail -80 server.log
  exit 5
fi

# Trigger graceful shutdown
echo "[run-tests] stopping server"
kill -TERM $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true

echo "[run-tests] junit.xml:"
if [ -f "test-results/junit.xml" ]; then
  cat test-results/junit.xml
else
  echo "(none — test plugin aborted before XML write)"
fi

# Exit non-zero if any test failed
if grep -q '<failure ' test-results/junit.xml 2>/dev/null; then
  echo "[run-tests] FAILURES detected"
  exit 6
fi
echo "[run-tests] all tests passed"
