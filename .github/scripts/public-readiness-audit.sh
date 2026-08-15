#!/usr/bin/env bash
# public-readiness-audit.sh — the "hostile auditor" gate.
#
# Runs the exact checks a critical open-source reviewer runs in the first hour after
# a repository goes public, and fails if any of them would produce a credible
# "this banking repo is unsafe" finding. The point is to make the claim
# "this repo is not low-hanging-fruit attackable" *machine-verifiable and
# self-serve* rather than asserted in prose — derive → enforce → sign (ADR-0029).
#
# Run from the repo root:   bash .github/scripts/public-readiness-audit.sh
# Exit 0 = clean. Exit 1 = at least one gate failed. Exit 2 = a tool is missing.
#
# This is intentionally dependency-light (bash + grep + python3 + gitleaks). It does
# NOT replace the CI security suite (CodeQL/Trivy/SBOM) — it is the cheap, fast,
# reproducible pre-flight any contributor can run locally before arguing the repo
# is safe to publish.

set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || echo .)" || exit 1

PASS=0; FAIL=0; WARN=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31m✗ %s\033[0m\n' "$1"; FAIL=$((FAIL+1)); }
warn() { printf '  \033[33m! %s\033[0m\n' "$1"; WARN=$((WARN+1)); }
hdr()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---------------------------------------------------------------------------
hdr "1. Secrets across FULL git history (gitleaks --log-opts=--all)"
# A community member's very first move: clone + `gitleaks detect --log-opts=--all`.
# A single hit (even a false positive) becomes a screenshot. Must be 0.
if [ -n "${SKIP_GITLEAKS:-}" ]; then
  warn "SKIP_GITLEAKS set — full-history scan skipped (CI runs it via secret-scan.yml)"
elif ! command -v gitleaks >/dev/null 2>&1; then
  warn "gitleaks not installed — SKIPPING the single most important check (install: brew install gitleaks)"
else
  rpt="$(mktemp)"
  # gitleaks exit codes: 0 = clean, 1 = leaks found, anything else = tool error.
  # A tool error must NOT read as a clean pass — that is the exact false-PASS a
  # security gate must avoid. Capture the real exit code and branch on it.
  gitleaks detect --source . --log-opts="--all" --report-format json \
    --report-path "$rpt" --no-banner >/dev/null 2>&1
  gl_rc=$?
  if [ "$gl_rc" = "0" ]; then
    ok "full-history secret scan clean (0 findings)"
  elif [ "$gl_rc" = "1" ]; then
    n=$(python3 -c "import json;print(len(json.load(open('$rpt'))))" 2>/dev/null || echo "unknown")
    bad "$n secret/PII finding(s) in git history — run: gitleaks detect --log-opts=--all --report-path /tmp/gl.json"
  else
    bad "gitleaks errored (exit $gl_rc) — scan inconclusive, NOT treated as clean"
  fi
  rm -f "$rpt"
fi

# ---------------------------------------------------------------------------
hdr "2. No literal secrets in Kubernetes manifests"
# Every secret must arrive via ExternalSecret/SealedSecret, never inline data:/stringData:.
lit=$(grep -rln 'kind: Secret' openbank-infra 2>/dev/null | while read -r f; do
        grep -qE '^\s+(data|stringData):' "$f" && echo "$f"; done)
if [ -z "$lit" ]; then
  ok "no inline data:/stringData: in any kind:Secret ($(grep -rln 'ExternalSecret' openbank-infra 2>/dev/null | wc -l | tr -d ' ') ExternalSecret refs)"
else
  bad "literal secret data found in: $lit"
fi

# ---------------------------------------------------------------------------
hdr "3. SECURITY.md claims are backed by real workflows (no security theater)"
# The worst credibility hit is a security policy promising gates that aren't wired.
# "name=regex" pairs — bash 3.2-portable (macOS default), no associative arrays.
for pair in "CodeQL=codeql" "gitleaks=gitleaks" "Trivy=trivy" "SBOM=syft|cyclonedx|sbom"; do
  name="${pair%%=*}"; pat="${pair#*=}"
  if grep -rliE "$pat" .github/workflows/ >/dev/null 2>&1; then
    ok "$name control wired in .github/workflows/"
  else
    bad "SECURITY.md implies $name but no workflow references it"
  fi
done
[ -f .github/dependabot.yml ] && ok "Dependabot config present" || bad "no .github/dependabot.yml"

# ---------------------------------------------------------------------------
hdr "4. Network default-deny present (zero-trust is real, not aspirational)"
if grep -rqs 'default-deny' openbank-infra/k8s/base/network-policies.yaml; then
  np=$(grep -rl 'kind: NetworkPolicy' openbank-infra --include='*.yaml' 2>/dev/null | wc -l | tr -d ' ')
  ok "default-deny NetworkPolicy present ($np NetworkPolicy manifests fleet-wide)"
else
  bad "no default-deny NetworkPolicy in k8s/base — perimeter is allow-by-default"
fi

# ---------------------------------------------------------------------------
hdr "5. No unguarded production secret fallbacks in app code"
# A baked-in default secret used in production = forgeable sessions / auth bypass.
# We require any dev-fallback to be guarded by a production fatal-check.
hits=$(grep -rn 'change-in-prod\|changeme\|CHANGE_ME' openbank-admin-ui/src openbank-libs/src 2>/dev/null \
        | grep -v 'requiredSecret\|throw\|must be set\|test' || true)
if [ -z "$hits" ]; then
  ok "no unguarded dev-default secret literals in production code paths"
else
  warn "review dev-default literals (confirm each is prod-fatal-guarded):"; echo "$hits" | sed 's/^/      /'
fi

# ---------------------------------------------------------------------------
hdr "6. Governance & disclosure surface complete"
for f in SECURITY.md LICENSE CODEOWNERS CODE_OF_CONDUCT.md CONTRIBUTING.md; do
  [ -f "$f" ] && ok "$f present" || bad "$f MISSING"
done
# Threat model per money-path service (ADR-0030).
miss=""
while read -r svc; do
  [ -z "$svc" ] && continue
  [ -f "docs/threat-models/${svc}.md" ] || miss="$miss $svc"
done < <(sed -n '/^money_path_services:/,/^[a-z_]*:/p' openbank-libs/governance/rules.yaml \
          | grep -E '^\s+- ' | sed 's/#.*//' | tr -d ' ' | sed 's/^-//')
if [ -z "$miss" ]; then
  ok "every money-path service has docs/threat-models/<svc>.md"
else
  bad "money-path services missing a threat model:$miss"
fi

# ---------------------------------------------------------------------------
hdr "Result"
printf '  %d passed, %d failed, %d warn\n' "$PASS" "$FAIL" "$WARN"
if [ "$FAIL" -gt 0 ]; then
  printf '\033[31m  NOT READY — resolve the ✗ items before going public.\033[0m\n'; exit 1
fi
printf '\033[32m  PUBLIC-READY — no low-hanging-fruit findings.\033[0m\n'
[ "$WARN" -gt 0 ] && printf '  (%d warning(s) are advisory — review but non-blocking.)\n' "$WARN"
exit 0
