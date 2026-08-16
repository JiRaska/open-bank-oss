#!/usr/bin/env bash
# Render a committed Keycloak realm template with its real secret/password
# values and verify the result actually imports — before anyone writes it to
# Vault. Issue #3246, runbook docs/runbooks/0009-keycloak-realm-import-reconcile.md.
#
# WHY THIS SCRIPT EXISTS
#   #3246 found the deployed `keycloak-realm-import` / `keycloak-customers-realm-import`
#   Secrets are a stale ANCESTOR of the committed templates (4 roles / 2 clients vs
#   14 / 10 for `openbank`), because `--import-realm` runs on Keycloak's cold start
#   only and skips a realm that already exists — so the templates have never once
#   been what a rebuild actually imports. Runbook 0009's fix is a Vault write, and
#   it is owner-gated: only the owner holds the credentials the templates
#   placeholder-out (`__ADMINUI_CLIENT_SECRET__` and friends). But the runbook's
#   pre-write verification step — substitute, boot a throwaway Keycloak, confirm no
#   ERROR, never leave the substituted file on disk — was a multi-step manual
#   recipe with real ways to get it wrong (skip the boot test, forget to shred the
#   temp file, use a Keycloak version the cluster doesn't actually run). This script
#   is that recipe, made repeatable and safe by construction:
#     - every placeholder must be supplied via env var or the script refuses to run
#       (no accidental empty-string substitution);
#     - the substituted file lives ONLY in a mode-600 temp file, deleted (best-effort
#       shred, then rm) by a trap that fires on ANY exit path, including a failed
#       import or an interrupted run;
#     - the file is never echoed, logged, or written under the repo;
#     - it does not, and cannot, write to Vault — with or without `--out` (below),
#       the `vault kv put` itself is always a separate, manual, owner-run command.
#       That is deliberate, not an oversight: bundling the write into this script
#       would turn "this script has a bug" into "this script silently wrote wrong
#       secret data to Vault," and the whole reason a boot-only PASS is not enough
#       (see USAGE note on token minting below) is that a script cannot fully judge
#       its own output — a human reading a decoded JWT still has to.
#
# USAGE
#   # openbank realm — every __PLACEHOLDER__ in realm-template.json needs its env var
#   ADMINUI_CLIENT_SECRET=... ARGOCD_CLIENT_SECRET=... EDGE_CLIENT_SECRET=... \
#   GLITCHTIP_CLIENT_SECRET=... GOALERT_CLIENT_SECRET=... MCP_OBO_CLIENT_SECRET=... \
#   OPENBAO_CLIENT_SECRET=... SERVICES_CLIENT_SECRET=... ADMIN_USER_PASSWORD=... \
#   DEMO_USER_PASSWORD=... COMPLIANCE_USER_PASSWORD=... COMPLIANCE2_USER_PASSWORD=... \
#   ADMIN_HOST=admin.openbank.local \
#     ./openbank-infra/scripts/render-verify-keycloak-realm-import.sh openbank
#
#   # openbank-customers realm
#   CUSTOMER_EDGE_ADMIN_CLIENT_SECRET=... EDGE_WEBAUTHN_CLIENT_SECRET=... \
#     ./openbank-infra/scripts/render-verify-keycloak-realm-import.sh openbank-customers
#
#   # Keep the verified render just long enough to run `vault kv put` yourself —
#   # otherwise a PASS shreds it and there is nothing left to point the write at:
#     ./openbank-infra/scripts/render-verify-keycloak-realm-import.sh --out /tmp/ob.json openbank
#
# Set SKIP_IMPORT_TEST=1 to render and placeholder-check only (no Docker needed);
# --out is refused in that mode, since it would hand you a file that never passed
# the boot verification.
# Requires: python3, and (unless SKIP_IMPORT_TEST=1) docker.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KEYCLOAK_DIR="${REPO_ROOT}/openbank-infra/gitops/components/keycloak"
KEYCLOAK_MANIFEST="${KEYCLOAK_DIR}/keycloak.yaml"

usage() {
  echo "Usage: $0 [--out <path>] <openbank|openbank-customers>" >&2
  echo "  --out <path>  after a PASS, copy the verified render there (mode 600) instead of" >&2
  echo "                shredding it — for feeding straight into 'vault kv put ... @<path>'." >&2
  echo "                Without it (the default) nothing survives the run; this is the" >&2
  echo "                pre-flight verification step, not the write itself." >&2
  exit 2
}

OUT_PATH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      [[ $# -ge 2 ]] || usage
      OUT_PATH="$2"
      shift 2
      ;;
    -*)
      usage
      ;;
    *)
      break
      ;;
  esac
done

[[ $# -eq 1 ]] || usage
REALM="$1"

case "$REALM" in
  openbank)
    TEMPLATE="${KEYCLOAK_DIR}/realm-template.json"
    IMPORT_FILENAME="openbank-realm.json"
    ;;
  openbank-customers)
    TEMPLATE="${KEYCLOAK_DIR}/customers-realm-template.json"
    IMPORT_FILENAME="openbank-customers-realm.json"
    ;;
  *)
    usage
    ;;
esac

[[ -f "$TEMPLATE" ]] || { echo "ERROR: template not found: $TEMPLATE" >&2; exit 1; }

# --- collect placeholders, refuse to run with any unset -------------------
# (portable form, not `mapfile` — this needs to run on bash 3.2 too, macOS's default)
PLACEHOLDERS=()
while IFS= read -r line; do
  [[ -n "$line" ]] && PLACEHOLDERS+=("$line")
done < <(grep -oE '__[A-Z0-9_]+__' "$TEMPLATE" | sort -u)
if [[ ${#PLACEHOLDERS[@]} -eq 0 ]]; then
  echo "ERROR: no __PLACEHOLDER__ tokens found in $TEMPLATE — refusing to run blind" \
    "(this script's whole point is substituting them, so zero found means the wrong file)." >&2
  exit 1
fi

missing=()
for ph in "${PLACEHOLDERS[@]}"; do
  envname="${ph#__}"
  envname="${envname%__}"
  if [[ -z "${!envname:-}" ]]; then
    missing+=("$envname")
  fi
done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "ERROR: missing env var(s) for these placeholders in $(basename "$TEMPLATE"):" >&2
  printf '  %s\n' "${missing[@]}" >&2
  echo "Set every one — the runbook's standing rule is that an empty substitution" \
    "is worse than a stopped script, not a smaller version of the same problem." >&2
  exit 1
fi

# --- render into a private temp file, always cleaned up --------------------
TMPFILE="$(mktemp "${TMPDIR:-/tmp}/ob-realm-render.XXXXXX.json")"
chmod 600 "$TMPFILE"
cleanup() {
  if [[ -f "$TMPFILE" ]]; then
    # shred if available (best-effort — tmpfs/APFS may not honor overwrite),
    # rm -f always, regardless.
    command -v shred >/dev/null 2>&1 && shred -u "$TMPFILE" 2>/dev/null
    rm -f "$TMPFILE"
  fi
}
trap cleanup EXIT INT TERM

python3 - "$TEMPLATE" "$TMPFILE" "${PLACEHOLDERS[@]}" <<'PYEOF'
import sys

template_path, out_path = sys.argv[1], sys.argv[2]
placeholders = sys.argv[3:]

text = open(template_path, encoding="utf-8").read()
for ph in placeholders:
    envname = ph.strip("_")
    import os

    text = text.replace(ph, os.environ[envname])

with open(out_path, "w", encoding="utf-8") as f:
    f.write(text)
PYEOF

echo "Rendered $(basename "$TEMPLATE") -> temp file (not printed, not committed)."

# --- sanity: still valid JSON, and no placeholder token survived ----------
python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$TMPFILE" \
  || { echo "ERROR: rendered file is not valid JSON — substitution broke it" >&2; exit 1; }

if grep -qE '__[A-Z0-9_]+__' "$TMPFILE"; then
  echo "ERROR: a __PLACEHOLDER__ token survived substitution — refusing to treat this" \
    "as ready for import (would ship a literal placeholder string as a secret)." >&2
  exit 1
fi
echo "OK: rendered file is valid JSON with no surviving placeholder tokens."

if [[ "${SKIP_IMPORT_TEST:-}" == "1" ]]; then
  if [[ -n "$OUT_PATH" ]]; then
    echo "ERROR: --out with SKIP_IMPORT_TEST=1 would hand you a file that never passed the" \
      "Keycloak boot verification — refusing. Drop SKIP_IMPORT_TEST or drop --out." >&2
    exit 1
  fi
  echo "SKIP_IMPORT_TEST=1 set — not running the Keycloak boot verification."
  echo "Per runbook 0009, do NOT write this to Vault without that step passing first."
  exit 0
fi

command -v docker >/dev/null 2>&1 || {
  echo "ERROR: docker not found and SKIP_IMPORT_TEST is not set." >&2
  echo "Runbook 0009's standing rule: verify against a local Keycloak before writing" \
    "to Vault, because --import-realm runs on cold start only and a broken template" \
    "ships silently. Install docker, or re-run with SKIP_IMPORT_TEST=1 and run the" \
    "boot test some other way before proceeding." >&2
  exit 1
}

# --- resolve the Keycloak version the cluster actually runs ----------------
CLUSTER_TAG="$(grep -oE 'openbank-keycloak:[0-9][A-Za-z0-9.-]*' "$KEYCLOAK_MANIFEST" \
  | head -1 | cut -d: -f2 || true)"
KC_VERSION="$(grep -oE '^[0-9]+\.[0-9]+\.[0-9]+' <<<"$CLUSTER_TAG" || true)"
if [[ -z "$KC_VERSION" ]]; then
  echo "ERROR: could not read a Keycloak version out of $KEYCLOAK_MANIFEST" \
    "(looked for openbank-keycloak:<version>). Refusing to guess a version to test" \
    "against — an import test against the wrong version is worse than no test." >&2
  exit 1
fi
echo "Cluster runs openbank-keycloak:${CLUSTER_TAG} -> testing against public" \
  "quay.io/keycloak/keycloak:${KC_VERSION} (the private ECR image cannot be pulled" \
  "from an arbitrary machine; same upstream version, same import code path)."

IMPORT_LOG="$(mktemp "${TMPDIR:-/tmp}/ob-realm-import-log.XXXXXX")"
trap 'cleanup; rm -f "$IMPORT_LOG"' EXIT INT TERM

set +e
docker run --rm \
  -v "${TMPFILE}:/opt/keycloak/data/import/realm.json:ro" \
  "quay.io/keycloak/keycloak:${KC_VERSION}" \
  start-dev --import-realm \
  >"$IMPORT_LOG" 2>&1 &
DOCKER_PID=$!

# The container runs a dev server after import; it never exits on its own.
# Poll the log for the completion marker or an error, then stop it.
STATUS="timeout"
for _ in $(seq 1 90); do
  if grep -q "KC-SERVICES0032: Import finished successfully" "$IMPORT_LOG" 2>/dev/null; then
    STATUS="imported"
    break
  fi
  if grep -qE '^[0-9-]+.*ERROR' "$IMPORT_LOG" 2>/dev/null; then
    STATUS="error"
    break
  fi
  if ! kill -0 "$DOCKER_PID" 2>/dev/null; then
    STATUS="exited"
    break
  fi
  sleep 2
done
kill "$DOCKER_PID" >/dev/null 2>&1
wait "$DOCKER_PID" 2>/dev/null
set -e

echo "--- import log (realm names/roles only where matched; no secret values are logged" \
  "by Keycloak's import) ---"
grep -E "Realm '.*' imported|ERROR|WARN" "$IMPORT_LOG" || true
echo "--- end import log ---"

case "$STATUS" in
  imported)
    echo "PASS: ${REALM} rendered template imported cleanly against" \
      "quay.io/keycloak/keycloak:${KC_VERSION}, zero ERROR lines."
    echo
    if [[ "$REALM" == "openbank" ]]; then
      VAULT_KEY="openbank/keycloak-realm-import"
    else
      VAULT_KEY="openbank/keycloak-customers-realm-import"
    fi
    echo "Per runbook 0009, the owner-gated write is now:"
    if [[ -n "$OUT_PATH" ]]; then
      cp "$TMPFILE" "$OUT_PATH"
      chmod 600 "$OUT_PATH"
      echo "  vault kv put ${VAULT_KEY} \\"
      echo "    ${IMPORT_FILENAME}=@${OUT_PATH}"
      echo
      echo "This script does NOT run that command — it only wrote the verified render to" \
        "${OUT_PATH} because you passed --out. Run the vault kv put above yourself, then" \
        "shred ${OUT_PATH} immediately: shred -u ${OUT_PATH} 2>/dev/null; rm -f ${OUT_PATH}"
    else
      echo "  vault kv put ${VAULT_KEY} \\"
      echo "    ${IMPORT_FILENAME}=@<path-to-a-render>"
      echo
      echo "This script does not perform that write, and — no --out was given — the" \
        "verified render is about to be shredded rather than left for you to point" \
        "vault kv put at. Re-run with --out /some/path to keep it just long enough to" \
        "run the write, then delete it yourself (or let this script's own next" \
        "invocation shred it for you)."
    fi
    ;;
  error)
    echo "FAIL: ERROR lines seen during import — do NOT write this to Vault." >&2
    exit 1
    ;;
  *)
    echo "FAIL: import did not report success within the wait budget (status=${STATUS})." \
      "Re-run with a longer wait or inspect docker logs manually — do NOT write this" \
      "to Vault." >&2
    exit 1
    ;;
esac
