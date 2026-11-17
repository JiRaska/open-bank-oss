#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Live (model-in-the-loop) run of the MCP red-team pack. Owner-run, weekly; never in CI.
#   MCP_REDTEAM_MODEL      model id on the OpenAI-compatible endpoint (required)
#   OPENAI_API_KEY         key for that endpoint (required; never commit it, never printed)
#   OPENAI_BASE_URL        endpoint base URL (optional; the provider default otherwise)
#   MCP_REDTEAM_CONFIRM=1  required: confirms the poisoned scenarios may be sent to the endpoint
#                          printed below (they leave this machine and cost tokens)
# Writes evals/mcp-redteam/recordings/recording.json and prints the verdict. Commit the recording
# only to refresh what CI replays (README: "Re-recording").
set -euo pipefail
: "${MCP_REDTEAM_MODEL:?set MCP_REDTEAM_MODEL}"
: "${OPENAI_API_KEY:?set OPENAI_API_KEY (owner-held; never committed)}"
PROMPTFOO_VERSION=0.125.1
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"

# Say where the data goes BEFORE anything is sent. The key is never printed.
echo "model:    ${MCP_REDTEAM_MODEL}"
echo "endpoint: ${OPENAI_BASE_URL:-<provider default>}"
echo "key:      set (not shown)"
if [ "${MCP_REDTEAM_CONFIRM:-}" != "1" ]; then
  echo "refusing to run: set MCP_REDTEAM_CONFIRM=1 to send the poisoned scenarios to that endpoint" >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
out="$work/promptfoo-results.json"

python3 "$root/.github/scripts/run-mcp-redteam-evals.py" --emit-promptfoo
cd "$here/promptfoo"
# promptfoo exits 100 when any of its own asserts fail; that is a result, not a run failure.
set +e
PROMPTFOO_DISABLE_TELEMETRY=1 PROMPTFOO_DISABLE_UPDATE=1 PROMPTFOO_CACHE_ENABLED=false \
  npx --yes "promptfoo@${PROMPTFOO_VERSION}" eval -c promptfooconfig.yaml \
  -r "openai:chat:${MCP_REDTEAM_MODEL}" --no-write -o "$out"
rc=$?
set -e
if [ "$rc" -ne 0 ] && [ "$rc" -ne 100 ]; then echo "promptfoo failed (exit $rc)" >&2; exit "$rc"; fi

python3 "$root/.github/scripts/run-mcp-redteam-evals.py" --import-promptfoo "$out"
# Exit status is the model verdict: a partial, stale or failing recording is non-zero.
python3 "$root/.github/scripts/run-mcp-redteam-evals.py"
