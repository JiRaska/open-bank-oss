#!/usr/bin/env bash
# Seed one LiteLLM virtual key PER AGENT, each with its own daily budget (ADR-0112 / ADR-0174).
#
# WHY THIS IS NOT A KUBERNETES JOB. Creating a key needs the master key, and the OUTPUT is a new
# credential that has to reach OpenBao and then each agent's namespace. A Job doing that would need
# both the proxy admin credential and OpenBao write access in one pod — a strictly larger blast
# radius than an operator running this from their own session, for something done once per
# environment. Same reasoning as seed-litellm-gateway.sh, which this deliberately mirrors.
#
# WHAT IT BUYS. Today every agent authenticates to the gateway with the SAME master key, so:
#   - "which agent burned the budget" is unanswerable — spend is attributed to one shared key;
#   - "stop THAT agent" is unimplementable — the only lever revokes the whole fleet;
#   - the per-model ceilings in litellm-config.yaml are the only enforcement, and they cannot tell
#     a runaway scheduler from ordinary interactive use of the same model.
# A per-agent key fixes all three, and LiteLLM refuses the call once the budget is spent — a
# preventive control, where AiFleetDailySpendHigh is only detective.
#
# BUDGETS. Deliberately summing to less than the per-model ceilings in litellm-config.yaml (20 USD
# for the ops model, 5 for the interactive one), which in turn sit under the 25 USD/day fleet figure
# AiFleetDailySpendHigh alerts on. Each layer bites before the one outside it, so the alert is the
# last line rather than the first.
#
# RUN:
#     export AWS_PROFILE=openbank          # kubectl + break-glass access to the sandbox
#     ./openbank-infra/scripts/seed-litellm-virtual-keys.sh
#
# IDEMPOTENT: a key whose alias already exists is left alone and reported, so a re-run does not
# silently rotate a credential the agents are using. Pass --rotate to replace one on purpose.
set -euo pipefail

NS_GW="ai-platform"

# alias:daily-budget-usd. The alias is the agent's charter id, so a spend row in LiteLLM joins
# straight onto agents.yaml and onto the openbank_llm_* series without a mapping table.
AGENT_BUDGETS=(
  "devops-agent:3"
  "control-liveness-sentinel:3"
  "copilot-service:8"          # customer-facing, interactive, highest legitimate volume
  "agent-service:5"            # admin-UI assistant; matches the llama route's own ceiling
)

ROTATE=0
[[ "${1:-}" == "--rotate" ]] && ROTATE=1

command -v kubectl >/dev/null || { echo "ERROR: kubectl not on PATH." >&2; exit 1; }
command -v jq      >/dev/null || { echo "ERROR: jq not on PATH." >&2; exit 1; }

echo "[1/4] Reading the master key from the running gateway's own Secret ..."
# Read it from the cluster rather than from OpenBao: what matters is the key the RUNNING proxy is
# actually using. If those two have drifted, seeding against OpenBao's copy would mint keys the
# gateway rejects, and the failure would only show up as agents degrading to their fallback.
MASTER_KEY="$(kubectl -n "$NS_GW" get secret litellm-secrets \
  -o jsonpath='{.data.LITELLM_MASTER_KEY}' 2>/dev/null | base64 -d || true)"
[[ -n "$MASTER_KEY" ]] || {
  echo "ERROR: litellm-secrets/LITELLM_MASTER_KEY is empty in namespace $NS_GW." >&2
  echo "       Run ./openbank-infra/scripts/seed-litellm-gateway.sh first." >&2
  exit 1
}

echo "[2/4] Port-forwarding to the gateway ..."
kubectl -n "$NS_GW" port-forward svc/litellm 14001:4000 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  curl -sf --max-time 2 http://127.0.0.1:14001/health/liveliness >/dev/null 2>&1 && break
  sleep 1
done
curl -sf --max-time 2 http://127.0.0.1:14001/health/liveliness >/dev/null 2>&1 || {
  echo "ERROR: gateway did not answer /health/liveliness through the port-forward." >&2
  exit 1
}

echo "[3/4] Checking DATABASE_URL is actually in effect ..."
# Without a database LiteLLM accepts /key/generate calls in some versions and persists nothing, so
# the script would report success over keys that vanish on the next restart. Ask the proxy.
if ! curl -sf --max-time 10 http://127.0.0.1:14001/key/list \
      -H "Authorization: Bearer $MASTER_KEY" >/dev/null 2>&1; then
  echo "ERROR: /key/list failed — the gateway most likely has no DATABASE_URL." >&2
  echo "       Virtual keys REQUIRE Postgres (components/ai-platform/postgres.yaml)." >&2
  exit 1
fi

# `?return_full_object=true` is REQUIRED: without it LiteLLM answers with a bare array of key
# STRINGS, and `.keys[].key_alias` then aborts jq with
#   jq: error (at <stdin>:0): Cannot index string with string ("key_alias")
# That failure could not happen on the run that created the keys — an empty `.keys[]` yields
# nothing and exits 0 — so the idempotency branch this feeds was only ever exercised in the one
# state where it had nothing to do. The `if type=="object"` arm keeps it working against both
# shapes rather than pinning the script to today's response format.
EXISTING="$(curl -s --max-time 10 "http://127.0.0.1:14001/key/list?return_full_object=true" \
  -H "Authorization: Bearer $MASTER_KEY" \
  | jq -r '[.keys[]? | if type=="object" then (.key_alias // empty) else empty end] | join(" ")')"

echo "[4/4] Seeding per-agent keys ..."
for entry in "${AGENT_BUDGETS[@]}"; do
  alias="${entry%%:*}"
  budget="${entry##*:}"

  if [[ " $EXISTING " == *" $alias "* ]]; then
    if [[ "$ROTATE" -eq 0 ]]; then
      echo "      = $alias already has a key (budget unchanged) — skipping. Use --rotate to replace."
      continue
    fi
    # /key/generate REFUSES a duplicate alias outright:
    #   "Key with alias '<alias>' already exists. Unique key aliases across all keys are required."
    # so --rotate cannot mean "generate over the top" — the old key has to go first. Like the
    # listing bug above, this path was unreachable until a key existed, so --rotate had never once
    # run against the state it exists for.
    echo "      - $alias: deleting the existing key before re-issuing (--rotate) ..."
    del="$(curl -s --max-time 20 -X POST http://127.0.0.1:14001/key/delete \
      -H "Authorization: Bearer $MASTER_KEY" -H 'Content-Type: application/json' \
      -d "{\"key_aliases\":[\"$alias\"]}")"
    echo "$del" | jq -e --arg a "$alias" '.deleted_keys | index($a)' >/dev/null 2>&1 || {
      echo "ERROR: could not delete the existing key for $alias. Response: $del" >&2
      exit 1
    }
  fi

  resp="$(curl -s --max-time 20 -X POST http://127.0.0.1:14001/key/generate \
    -H "Authorization: Bearer $MASTER_KEY" -H 'Content-Type: application/json' \
    -d "{\"key_alias\":\"$alias\",\"max_budget\":$budget,\"budget_duration\":\"1d\"}")"

  key="$(echo "$resp" | jq -r '.key // empty')"
  [[ -n "$key" ]] || {
    echo "ERROR: /key/generate returned no key for $alias. Response (no secrets): " >&2
    echo "$resp" | jq -r 'del(.key)' >&2
    exit 1
  }
  # The VALUE is never echoed. Write it to OpenBao yourself, or pipe this into your secret store;
  # printing a live credential into a terminal scrollback is how it ends up in a paste.
  echo "      + $alias  budget=${budget} USD/day  key=${key:0:7}… (value not printed)"
  echo "$alias $key" >> "${SEED_OUT:-/dev/null}"
done

cat <<'ENDNOTE'

NEXT STEPS (not automated on purpose — each writes a credential):
  1. Re-run with SEED_OUT=<path> to capture the values, write them into OpenBao under
     openbank/litellm-keys, and shred the file.
  2. Point each agent at its own key instead of the shared master key (its *_MODEL_API_KEY /
     model.api-key entry), then restart it.
  3. Verify attribution: the openbank_llm_requests_total series should now split by agent once
     each has its own key, and LiteLLM's /spend/logs attributes spend per key_alias.

Until step 2 lands the agents still share the master key, so these budgets exist but bind nothing.
ENDNOTE
