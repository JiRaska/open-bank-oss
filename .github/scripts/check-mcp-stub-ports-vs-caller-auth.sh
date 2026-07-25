#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Guard: openbank-mcp-service must not bind a REAL read/proposal port while its
# caller identity is still the hardcoded phase-1 placeholder (BLOCKER #2206).
#
# WHY THIS EXISTS
#   The MCP threat model (PR #2200, docs/threat-models/openbank-mcp-service.md)
#   established that this service authenticates NOBODY today:
#     - McpEndpoint.resolveContext() hardcodes
#       ConsentContext("agent:mcp-anonymous", "none", emptyList()) — it does NOT
#       read the X-Agent-Id / X-Consent-Id headers its own KDoc claims, there is
#       no OIDC (tenant-enabled: false), no @RolesAllowed, no mTLS.
#     - The OPA PDP therefore authorizes a CONSTANT. It works; it is asked the
#       wrong question.
#     - Consent-scoped account filtering is delegated to "the port implementation",
#       and the only implementation is StubReadPorts, which enforces nothing.
#   So the STUB BOUNDARY is the load-bearing security control, and nothing else
#   guards it. Phase 2's plan — swap StubReadPorts for real @RegisterRestClient
#   adapters — needs NO endpoint and NO policy edit, which is exactly what makes
#   it dangerous: the moment a real port lands, get_balance(accountId) becomes an
#   UNAUTHENTICATED read of any guessable account id, and every other CI gate
#   stays green because nothing in CI knows the stub was the control.
#
# THE INVARIANT
#   A real (non-Stub) AccountReadPort / ProposalPort implementation may be wired
#   ONLY once resolveContext() no longer returns the hardcoded placeholder
#   identity. Caller authentication + consent binding must land BEFORE, or
#   atomically WITH, the real read ports — never as a follow-up (#2206).
#
#   This guard fails if a non-Stub port implementation exists in the service while
#   McpEndpoint still carries the `agent:mcp-anonymous` placeholder constant. It
#   is a REGRESSION guard: 0 violations today (only StubReadPorts is wired).
#
# stdlib-only (grep/awk); no Kotlin parser. ENFORCED.
# Usage: check-mcp-stub-ports-vs-caller-auth.sh [root]   (default root: .)
# Exit: 0 = clean, 1 = violation.
# -----------------------------------------------------------------------------
set -euo pipefail

ROOT="${1:-.}"
cd "$ROOT"
SVC="openbank-mcp-service/src/main/kotlin"
ENDPOINT="$SVC/com/openbank/mcp/infrastructure/mcp/McpEndpoint.kt"

# If the service tree isn't present (path-scoped checkout), nothing to check.
if [[ ! -d "$SVC" ]]; then
  echo "check-mcp-stub-ports-vs-caller-auth: openbank-mcp-service not present — skipping."
  exit 0
fi
if [[ ! -f "$ENDPOINT" ]]; then
  echo "::error title=MCP caller-auth guard::McpEndpoint.kt not found at $ENDPOINT — the guard cannot verify the placeholder identity; wiring may have moved. Update this guard." >&2
  exit 1
fi

# 1. Is resolveContext still the phase-1 placeholder? The load-bearing signal is
#    the literal placeholder principal id; a real OAuth/consent resolution removes it.
placeholder_present=0
if grep -qE 'agent:mcp-anonymous' "$ENDPOINT"; then
  placeholder_present=1
fi

# 2. Is a NON-Stub AccountReadPort / ProposalPort implementation wired anywhere in
#    the service? (The interface declarations in port/out/ReadPorts.kt are
#    `interface AccountReadPort` — only `class X ... : AccountReadPort` matches here.)
impl_classes=$(grep -rhoE 'class[[:space:]]+[A-Za-z0-9_]+[^{]*:[^{]*\b(AccountReadPort|ProposalPort)\b' "$SVC" 2>/dev/null \
  | grep -oE 'class[[:space:]]+[A-Za-z0-9_]+' | awk '{print $2}' | sort -u || true)
non_stub=$(printf '%s\n' "$impl_classes" | grep -vE '^Stub' | grep -v '^$' || true)

if [[ "$placeholder_present" -eq 1 && -n "$non_stub" ]]; then
  echo "::error title=MCP caller-auth guard (BLOCKER #2206)::A non-stub read/proposal port is wired while McpEndpoint.resolveContext() still returns the hardcoded 'agent:mcp-anonymous' placeholder. This turns get_balance/list_accounts into an UNAUTHENTICATED read of any account. Land real caller identity + PSD2 consent binding (resolveContext) BEFORE or ATOMICALLY WITH the real port. Offending non-stub impl(s): $(echo "$non_stub" | tr '\n' ' ')" >&2
  exit 1
fi

if [[ "$placeholder_present" -eq 1 ]]; then
  echo "check-mcp-stub-ports-vs-caller-auth: OK — placeholder identity still in place, only stub ports wired (phase 1)."
else
  echo "check-mcp-stub-ports-vs-caller-auth: OK — placeholder identity removed; real caller auth landed, real ports permitted."
fi
