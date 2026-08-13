#!/usr/bin/env bash
# Guard: no .rego policy (standalone or embedded as a heredoc in a
# gen-*-opa-bundle.sh generator script, or already baked into a committed
# *-opa-bundle.yaml ConfigMap) may gate an allow rule on
# `principal.type == "SERVICE"`.
#
# AuthorizeInterceptor.principalType() (openbank-libs-runtime) only ever emits
# ANONYMOUS/AI_AGENT/HUMAN — never SERVICE. M2M callers authenticate with a Keycloak
# client_credentials JWT, which the interceptor classifies as HUMAN, and no realm
# client is ever granted a ROLE_SERVICE role. A rego rule gated on
# `principal.type == "SERVICE"` is therefore structurally unreachable dead code — it
# can never fire, silently denying the M2M caller it was meant to authorize once
# AUTHZ_ENFORCE flips to true (found in the sca/domestic-payment ADR-0034 Phase 5 PRs,
# issue #266, both pre-merge; the shared rest.rego edge-service-notification rule
# carried the same defect while already deployed with AUTHZ_ENFORCE=true).
#
# Identify a specific M2M caller by input.principal.id (Keycloak's
# "service-account-<clientId>" convention for a service-account token) instead —
# ROLE_OPERATOR is shared with real human staff, so gating on HUMAN + ROLE_OPERATOR
# alone is not equivalent and can over-grant (see rest.rego's edge-service-notification
# rule and rules.yaml: authz_policy).
#
# stdlib-only (grep); no opa/rego-parser dependency. ENFORCED.
# Usage: check-no-service-principal-type.sh [root]   (default root: .)
set -euo pipefail
# --- self-test ------------------------------------------------------------------------
# `input.principal.type == "SERVICE"` is UNREACHABLE: AuthorizeInterceptor only ever emits
# ANONYMOUS/AI_AGENT/HUMAN, M2M callers authenticate with a client_credentials JWT that
# classifies as HUMAN, and no realm client is granted ROLE_SERVICE. A rule gated on it is dead
# code that denies the very M2M caller it was written for once AUTHZ_ENFORCE flips (#266).
#
# It found that live in the shared rest.rego. It shipped without a self-test, so its own RED
# path was code nobody had run — and this guard's whole value is that it can still fire on a
# file nobody is reading.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0

  put() { mkdir -p "$(dirname "$1")"; printf '%b' "$2" > "$1"; }
  expect() { # expect <label> <root> <want-rc> [substring]
    local label="$1" root="$2" want="$3" sub="${4:-}" out rc
    out=$(bash "$0" "$root" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then
      echo "::error::self-test: $label — expected rc=$want, got rc=$rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1))
    fi
  }

  # THE DEFECT, in a live rule body.
  a="$td/dead"; put "$a/policy.rego" 'allow {\n  input.principal.type == "SERVICE"\n}\n'
  expect "a live SERVICE rule is FLAGGED" "$a" 1 "can never fire"

  # PROSE. This repo's guards keep flagging the comment that explains the rule they enforce
  # (#2450), and a guard that reddens its own documentation gets deleted by the next reader.
  b="$td/comment"; put "$b/policy.rego" '# never write input.principal.type == "SERVICE" here\nallow { input.principal.id == "x" }\n'
  expect "the same text in a COMMENT is not a hit" "$b" 0 "none gate on"

  # ...including an INDENTED comment, which is how it appears inside a rule body.
  c="$td/indented"; put "$c/policy.rego" 'allow {\n    # input.principal.type == "SERVICE" would be dead here\n    input.principal.id == "x"\n}\n'
  expect "an indented comment is not a hit" "$c" 0 "none gate on"

  # The sanctioned alternative must read as clean, or the gate blocks the fix it demands.
  d="$td/ok"; put "$d/policy.rego" 'allow {\n  input.principal.id == "service-account-openbank-services"\n}\n'
  expect "gating on principal.id is clean" "$d" 0 "none gate on"

  # SCOPE: the rule can also be embedded in a bundle generator heredoc or a bundle ConfigMap,
  # which is where a naive *.rego-only sweep loses it — the estate then looks covered.
  e="$td/bundle"; put "$e/gen-x-opa-bundle.sh" 'cat <<EOF\nallow { input.principal.type == "SERVICE" }\nEOF\n'
  expect "a generator heredoc is in scope" "$e" 1 "can never fire"
  f="$td/cm"; put "$f/x-opa-bundle.yaml" 'data: |\n  allow { input.principal.type == "SERVICE" }\n'
  expect "a bundle ConfigMap is in scope" "$f" 1 "can never fire"

  # Whitespace variants — `==` with any spacing is the same rule.
  g="$td/spacing"; put "$g/policy.rego" 'allow {\n  input.principal.type=="SERVICE"\n}\n'
  expect "no spaces around == is still a hit" "$g" 1 "can never fire"

  # A tree with no policy files at all: 0 checked. The comparison is right to report clean,
  # but a run that examined NOTHING must not read like a fleet that is clean, so the count is
  # asserted here rather than left to the reader.
  h="$td/none"; mkdir -p "$h"
  expect "an empty tree reports 0 checked" "$h" 0 "0 policy source file(s) checked"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: SERVICE-principal dead-rule guard is falsifiable (8 cases)"
  exit 0
fi

root="${1:-.}"
fail=0
checked=0

while IFS= read -r f; do
  checked=$((checked + 1))
  # Exclude comment lines (rego line-comments start with #, possibly indented) so this
  # guard flags live rule bodies, not explanatory prose about the defect itself.
  hits=$(grep -nE '^[[:space:]]*[^#[:space:]].*principal\.type[[:space:]]*==[[:space:]]*"SERVICE"' "$f" || true)
  if [ -n "$hits" ]; then
    fail=1
    while IFS= read -r hit; do
      lineno="${hit%%:*}"
      echo "::error file=$f,line=$lineno::principal.type == \"SERVICE\" can never fire — AuthorizeInterceptor never emits it and no Keycloak client is ever granted ROLE_SERVICE (rules.yaml: authz_policy). Gate on input.principal.id instead."
    done <<< "$hits"
  fi
done < <(find "$root" \
  \( -name '*.rego' -o -name 'gen-*-opa-bundle*.sh' -o -name '*-opa-bundle.yaml' \) \
  -not -path '*/build/*' -not -path '*/node_modules/*' -not -path '*/dist/*' | sort)

echo "check-no-service-principal-type: $checked policy source file(s) checked, $( [ "$fail" -eq 0 ] && echo "none gate on principal.type == \"SERVICE\"." || echo "VIOLATIONS above." )"
exit "$fail"
