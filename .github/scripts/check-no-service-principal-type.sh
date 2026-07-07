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
