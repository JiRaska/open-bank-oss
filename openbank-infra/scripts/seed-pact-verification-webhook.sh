#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Create-or-update the Pact Broker webhook that makes a PROVIDER verify a pact as
# soon as a CONSUMER publishes it (ADR-0092).
#
# WHY THIS EXISTS
#   Nothing did — a provider only ever verified a consumer's pact when the PROVIDER
#   itself next happened to build. The evidence for that is the BEHAVIOUR below, not
#   a webhook count: the first query used to establish "no webhooks" parsed
#   `_embedded.webhooks`, which this broker never populates (see existing_uuid), so
#   it would have answered zero either way. Measured on 2026-08-01:
#     15:08  openbank-lending-service publishes a pact for 9052f5f1
#     15:43, 16:45, 17:24, 18:42  auto-deploy FAILS — lending UNVERIFIED, blocked
#                                 on openbank-ledger-service
#     20:09  ledger finally verifies it, on a build of its own that had nothing
#            to do with lending
#   Five hours of red on a money-path service, and the gate was right every time —
#   there was simply no mechanism to make the provider look. 33 of the last 40
#   auto-deploy runs failed that way.
#
#   It compounds with the scoped main-push build: a push builds only the modules
#   it can attribute the diff to, so a lending-only change never rebuilds ledger.
#   `can-i-deploy` classifies UNVERIFIED as self-clearing ("the counterpart
#   verifies minutes later"), which is only true if SOMETHING causes the
#   counterpart to verify. This is that something.
#
# WHAT IT CREATES
#   One broker webhook on `contract_requiring_verification_published` that POSTs a
#   workflow_dispatch for .github/workflows/verify-provider.yml with
#   `service = ${pactbroker.providerName}`.
#
#   That substitution is safe because every pacticipant name in this broker is
#   exactly a module directory in the repo — 55 of 55, checked. It is not left as
#   folklore: .github/scripts/check-pacticipant-matches-module.py asserts it in CI,
#   so the day someone registers a pacticipant under a different name, that guard
#   goes red instead of this webhook silently dispatching a build of nothing.
#
# SECURITY — read before running
#   The broker is INTERNET-FACING (https://pact.open-bank.tech, a documented
#   ADR-0056 exception). Handing an internet-facing service a GitHub credential
#   turns a broker compromise into CI-execution capability, so the token this
#   webhook carries must be the smallest one GitHub can express:
#
#     * a FINE-GRAINED PAT (or App installation token)
#     * scoped to THIS REPOSITORY ONLY
#     * with exactly ONE permission: Actions: Read and write
#     * nothing else — no contents, no packages, no secrets, no admin
#
#   With that, the worst a stolen token can do is dispatch verify-provider.yml,
#   which builds a service and publishes a verification result. It cannot deploy,
#   read secrets, or push code. Do not substitute the ARC GitHub App key here: it
#   holds runner-administration rights the broker has no business being able to use.
#
#   The token is never written to git. It lives in OpenBao (`openbank/pact-broker`,
#   key `github-dispatch-token`) and, at runtime, in the broker's own database —
#   the broker redacts Authorization headers in its API responses.
#
# RUNBOOK (out-of-band, once — GATE 2, values never in git)
#     # 1. mint the fine-grained PAT described above, then:
#     export AWS_PROFILE=openbank
#     bao kv put openbank/pact-broker github-dispatch-token=<token>   # merge, not replace
#     # 2. create the webhook:
#     ./openbank-infra/scripts/seed-pact-verification-webhook.sh
#     # 3. prove it fires (see VERIFYING below) — a webhook that has never fired is
#     #    indistinguishable from one that cannot.
#
# VERIFYING
#     ./openbank-infra/scripts/seed-pact-verification-webhook.sh --test
#   asks the broker to execute the webhook against a sample event and prints what it
#   sent and what GitHub answered. Read the STATUS: 204 is success (workflow_dispatch
#   returns no body), 401/403 means the token is wrong or under-scoped, 422 usually
#   means the workflow file is not on the default branch.
#
#   Response logging requires api.github.com on PACT_BROKER_WEBHOOK_HOST_WHITELIST,
#   which pact-broker.yaml sets. Without it the broker prints "response details are
#   not logged" and every outcome — success, dead token, wrong URL — reads as the same
#   bare "Webhook execution failed". Check the whitelist first if you see that.
#
# IDEMPOTENT: re-running updates the existing webhook in place (matched by its
# description), so this is safe to run after a token rotation.
# -----------------------------------------------------------------------------
set -euo pipefail

REPO="${PACT_WEBHOOK_REPO:-JiRaska/open-bank-oss}"
WORKFLOW="${PACT_WEBHOOK_WORKFLOW:-verify-provider.yml}"
REF="${PACT_WEBHOOK_REF:-main}"
DESCRIPTION="dispatch ${WORKFLOW} when a consumer publishes a pact this provider has not verified"
NS="${PACT_BROKER_NAMESPACE:-pact-broker}"
LOCAL_PORT="${PACT_BROKER_LOCAL_PORT:-19292}"

TEST_ONLY=0
[ "${1:-}" = "--test" ] && TEST_ONLY=1

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not found in PATH" >&2; exit 1; }; }
need kubectl
need curl
need python3

cleanup() { [ -n "${PF_PID:-}" ] && kill "$PF_PID" 2>/dev/null || true; }
trap cleanup EXIT

# --- broker credentials + a local port ---------------------------------------
# Read from the cluster Secret rather than prompting: the same values ESO already
# syncs from OpenBao, so there is one source of truth and nothing to paste.
BROKER_USER=$(kubectl -n "$NS" get secret pact-broker-basic-auth -o jsonpath='{.data.username}' | base64 -d)
BROKER_PASS=$(kubectl -n "$NS" get secret pact-broker-basic-auth -o jsonpath='{.data.password}' | base64 -d)
[ -n "$BROKER_USER" ] && [ -n "$BROKER_PASS" ] || { echo "ERROR: could not read pact-broker-basic-auth" >&2; exit 1; }

kubectl -n "$NS" port-forward svc/pact-broker "${LOCAL_PORT}:9292" >/dev/null 2>&1 &
PF_PID=$!
B="http://localhost:${LOCAL_PORT}"
for _ in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -u "$BROKER_USER:$BROKER_PASS" "$B/" || true)
  [ "$code" = "200" ] && break
  sleep 1
done
[ "${code:-}" = "200" ] || { echo "ERROR: broker not reachable on $B (last status ${code:-none})" >&2; exit 1; }

# --- find an existing webhook by description ---------------------------------
# The collection lives under `_links['pb:webhooks']` as HAL LINKS, not under
# `_embedded`, and each link carries only the broker's own auto-generated title
# ("A webhook for all pacts") — the description is on the individual resource. So
# this has to follow each link. The first draft read `_embedded.webhooks`, which is
# always absent: it found nothing, so `--test` reported "no webhook exists" about one
# that did, and — worse — the update path would never match, quietly creating a
# DUPLICATE webhook on every re-run. Both only showed up by running it against the
# real broker.
existing_uuid() {
  curl -s -u "$BROKER_USER:$BROKER_PASS" "$B/webhooks" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for l in (d.get('_links',{}).get('pb:webhooks') or []):
    href=l.get('href','')
    if href: print(href.rstrip('/').rsplit('/',1)[-1])
" | while IFS= read -r uuid; do
    [ -z "$uuid" ] && continue
    desc=$(curl -s -u "$BROKER_USER:$BROKER_PASS" "$B/webhooks/$uuid" \
      | python3 -c "import json,sys;print(json.load(sys.stdin).get('description') or '')")
    if [ "$desc" = "$DESCRIPTION" ]; then printf '%s' "$uuid"; return 0; fi
  done
}

UUID="$(existing_uuid || true)"

if [ "$TEST_ONLY" = "1" ]; then
  [ -n "$UUID" ] || { echo "ERROR: no webhook with that description exists yet — run without --test first" >&2; exit 1; }
  echo "Executing webhook $UUID against a sample event..."
  curl -s -u "$BROKER_USER:$BROKER_PASS" -X POST "$B/webhooks/$UUID/execute" \
    | python3 -c "
import json,sys
d=json.load(sys.stdin)
l=d.get('logs','')
print(l)
r=(d.get('request') or {}); resp=(d.get('response') or {})
print('--- response status:', resp.get('status'))
print('--- 204 = dispatched. 401/403 = token wrong or under-scoped. 422 = workflow not on the default branch.')
"
  exit 0
fi

# --- the GitHub token, from OpenBao ------------------------------------------
# Never echoed, never written to a file. If it is not in OpenBao yet, say exactly
# what to do rather than creating anything: minting credentials is a human step.
TOKEN="${PACT_WEBHOOK_GITHUB_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  if command -v bao >/dev/null 2>&1; then
    TOKEN=$(bao kv get -field=github-dispatch-token openbank/pact-broker 2>/dev/null || true)
  elif command -v vault >/dev/null 2>&1; then
    TOKEN=$(vault kv get -field=github-dispatch-token openbank/pact-broker 2>/dev/null || true)
  fi
fi
if [ -z "$TOKEN" ]; then
  cat >&2 <<'MSG'
ERROR: no GitHub dispatch token available.

  Mint a FINE-GRAINED PAT scoped to this repository only, with exactly one
  permission — Actions: Read and write — and nothing else. Then:

      bao kv patch openbank/pact-broker github-dispatch-token=<token>

  (`patch`, not `put` — `put` replaces the whole secret and would drop the
  broker's basic-auth credentials, taking the broker down with it.)

  Or, for a one-off run without storing it:
      PACT_WEBHOOK_GITHUB_TOKEN=<token> ./openbank-infra/scripts/seed-pact-verification-webhook.sh
MSG
  exit 1
fi

# --- the webhook definition ---------------------------------------------------
# `${pactbroker.providerName}` is substituted by the broker at execution time.
# Single-quoted heredoc so the shell leaves it alone; the token is injected after.
BODY=$(python3 - "$REPO" "$WORKFLOW" "$REF" "$DESCRIPTION" <<'PY'
import json, sys
repo, workflow, ref, description = sys.argv[1:5]
print(json.dumps({
    "description": description,
    "events": [{"name": "contract_requiring_verification_published"}],
    "request": {
        "method": "POST",
        "url": f"https://api.github.com/repos/{repo}/actions/workflows/{workflow}/dispatches",
        "headers": {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
            "Authorization": "Bearer __TOKEN__",
        },
        "body": {"ref": ref, "inputs": {"service": "${pactbroker.providerName}"}},
    },
}))
PY
)
BODY=${BODY/__TOKEN__/$TOKEN}

if [ -n "$UUID" ]; then
  echo "Updating existing webhook $UUID"
  METHOD=PUT; URL="$B/webhooks/$UUID"
else
  echo "Creating webhook"
  METHOD=POST; URL="$B/webhooks"
fi

STATUS=$(printf '%s' "$BODY" | curl -s -o /tmp/pact-webhook-resp.$$ -w '%{http_code}' \
  -u "$BROKER_USER:$BROKER_PASS" -X "$METHOD" "$URL" \
  -H 'Content-Type: application/json' --data-binary @-)

if [ "$STATUS" != "200" ] && [ "$STATUS" != "201" ]; then
  echo "ERROR: broker returned $STATUS" >&2
  # The response can echo the request back; strip anything token-shaped before printing.
  sed -E 's/(Bearer )[A-Za-z0-9_.-]+/\1<redacted>/g' "/tmp/pact-webhook-resp.$$" >&2 || true
  rm -f "/tmp/pact-webhook-resp.$$"
  exit 1
fi
rm -f "/tmp/pact-webhook-resp.$$"

echo "OK — webhook is in place."
echo
echo "Now PROVE it fires, because a webhook that has never fired is indistinguishable"
echo "from one that cannot:"
echo "    $0 --test"
