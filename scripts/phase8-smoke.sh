#!/usr/bin/env bash
# phase8-smoke.sh — CI smoke test for claw-code-java.
# Runs against a live server (no external secrets required with NoopModelClient).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:?API_KEY env var is required}"
PASS=0
FAIL=0

assert_test() {
  local name="$1" result="$2" detail="${3:-}"
  if [ "$result" = true ]; then
    echo "  PASS  $name"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $name ${detail:+($detail)}"
    FAIL=$((FAIL + 1))
  fi
}

echo ""
echo "claw-code-java Phase 8 Smoke Test"
echo "Server: $BASE_URL"
echo ""

# --- 1. Health check ---
echo "[1/5] Health check"
STATUS=$(curl -sf "$BASE_URL/actuator/health" 2>/dev/null | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
if [ "$STATUS" = "UP" ]; then
  assert_test "GET /actuator/health" true
else
  assert_test "GET /actuator/health" false "status=$STATUS"
fi

# --- 2. Auth failure (expect 401) ---
echo "[2/5] Auth failure check"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/sessions" 2>/dev/null || true)
if [ "$CODE" = "401" ]; then
  assert_test "POST /api/sessions without key returns 401" true
else
  assert_test "POST /api/sessions without key returns 401" false "got $CODE"
fi

# --- 3. Session create ---
echo "[3/5] Session create"
BODY=$(curl -sf -X POST -H "X-API-Key: $API_KEY" "$BASE_URL/api/sessions" 2>/dev/null || echo "")
SESSION_ID=$(echo "$BODY" | grep -o '"sessionId":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "")
if [ -n "$SESSION_ID" ] && echo "$SESSION_ID" | grep -qE '^[0-9a-f-]{36}$'; then
  assert_test "session create returns valid ID" true
else
  assert_test "session create returns valid ID" false "body=$BODY"
fi

# --- 4. Message send (expect 202) ---
echo "[4/5] Message send"
if [ -n "$SESSION_ID" ]; then
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H "X-API-Key: $API_KEY" \
    -H "Content-Type: application/json" \
    -d '{"content":"smoke test"}' \
    "$BASE_URL/api/sessions/$SESSION_ID/messages" 2>/dev/null || true)
  if [ "$CODE" = "202" ]; then
    assert_test "message send returns 202" true
  else
    assert_test "message send returns 202" false "got $CODE"
  fi
else
  assert_test "message send returns 202" false "skipped: no session ID"
fi

# --- 5. Stream attach (SSE, 3s timeout) ---
echo "[5/5] Stream attach"
if [ -n "$SESSION_ID" ]; then
  timeout 3 curl -sf -N -H "X-API-Key: $API_KEY" \
    "$BASE_URL/api/sessions/$SESSION_ID/stream" \
    > /tmp/agent-smoke-stream.txt 2>/dev/null || true
  if grep -Eq '^(event:|data:|:)' /tmp/agent-smoke-stream.txt 2>/dev/null; then
    assert_test "stream attach receives SSE events" true
  else
    assert_test "stream attach receives SSE events" false "no events in stream"
  fi
else
  assert_test "stream attach receives SSE events" false "skipped: no session ID"
fi

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
echo ""
[ "$FAIL" -eq 0 ]
