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
#   A real (non-Stub), CDI-reachable AccountReadPort / ProposalPort implementation may be
#   wired ONLY once resolveContext() no longer returns the hardcoded placeholder identity.
#   Caller authentication + consent binding must land BEFORE, or atomically WITH, the real
#   read ports — never as a follow-up (#2206). A class may exist code-complete but INERT —
#   annotated `@Vetoed` (jakarta.enterprise.inject), so Quarkus never instantiates it via
#   CDI — as an explicit "prepared, not wired" step (ADR-0195); this guard does not flag
#   those, only a class that is actually reachable.
#
#   This guard fails if a non-Stub, non-@Vetoed port implementation exists in the service
#   while McpEndpoint still carries the `agent:mcp-anonymous` placeholder constant. It is a
#   REGRESSION guard: 0 violations today (StubReadPorts wired; RealAccountReadPort @Vetoed).
#
# Kotlin class headers routinely span multiple lines, so the scan uses python3 (this repo's
# existing convention for structural guards, e.g. check-threat-models.py) rather than a
# single-line grep, which would silently miss a multi-line constructor. ENFORCED.
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

# 2. Is a NON-Stub, NON-@Vetoed AccountReadPort / ProposalPort implementation wired anywhere in
#    the service? (The interface declarations in port/out/ReadPorts.kt are `interface
#    AccountReadPort` — only `class X ... : AccountReadPort` matches here.) A Kotlin constructor
#    routinely spans multiple lines, so this is NOT a single-line grep — parsed with python3
#    (matching this repo's existing convention for structural guards, e.g. check-threat-models.py)
#    for a multi-line-safe class-header scan. A class prefixed `@Vetoed` (jakarta.enterprise.inject)
#    is a CDI-excluded, code-complete-but-inert implementation — ADR-0195's own "code + tests, not
#    wired" step landed exactly this way (RealAccountReadPort) and must not trip this guard; a
#    class implementing the port WITHOUT @Vetoed and without CDI exclusion is reachable and unsafe.
non_stub=$(python3 - "$SVC" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
port_re = re.compile(r'\bclass\s+([A-Za-z0-9_]+)\s*')
target_re = re.compile(r'\b(?:AccountReadPort|ProposalPort)\b')

def supertype_clause(text: str, class_end: int) -> str | None:
    """Return the text after a class's primary-constructor param list (or after the class
    name when it has none) up to the first '{' or end of declaration — i.e. the supertype
    list — or None if there is no primary constructor closing paren to anchor on within a
    sane scan window. A constructor parameter's OWN type annotation (e.g. `accounts:
    AccountReadPort` inside the parens) must never be mistaken for a supertype: only text
    that appears at PAREN DEPTH 0, after the matching top-level ')', counts."""
    i = class_end
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    if i >= len(text):
        return None
    if text[i] != "(":
        # No primary constructor at all — whatever follows the class name up to '{' is
        # already the supertype clause (still may be absent, e.g. an abstract class).
        end = text.find("{", i)
        return text[i:end] if end != -1 else text[i : i + 500]
    depth = 0
    j = i
    while j < len(text):
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
            if depth == 0:
                break
        j += 1
    else:
        return None  # unbalanced within the file — give up rather than guess
    end = text.find("{", j)
    return text[j + 1 : end] if end != -1 else text[j + 1 : j + 1 + 500]

offenders = []
for path in sorted(root.rglob("*.kt")):
    text = path.read_text()
    for m in port_re.finditer(text):
        name = m.group(1)
        if name.startswith("Stub"):
            continue
        clause = supertype_clause(text, m.end())
        if clause is None or not target_re.search(clause):
            continue
        # @Vetoed is a standalone annotation line immediately preceding `class` (skipping
        # blank lines only — a KDoc block or another annotation in between does NOT count,
        # so @Vetoed must be the line directly above class, same as a real Kotlin annotation).
        line_no = text.count("\n", 0, m.start())
        lines = text.splitlines()
        k = line_no - 1
        while k >= 0 and lines[k].strip() == "":
            k -= 1
        vetoed = k >= 0 and lines[k].strip() == "@Vetoed"
        if not vetoed:
            offenders.append(f"{name} ({path})")

print("\n".join(offenders))
PY
)

if [[ "$placeholder_present" -eq 1 && -n "$non_stub" ]]; then
  echo "::error title=MCP caller-auth guard (BLOCKER #2206)::A non-stub, non-@Vetoed read/proposal port is wired while McpEndpoint.resolveContext() still returns the hardcoded 'agent:mcp-anonymous' placeholder. This turns get_balance/list_accounts into an UNAUTHENTICATED read of any account. Land real caller identity + PSD2 consent binding (resolveContext) BEFORE or ATOMICALLY WITH the real port — or mark the class @Vetoed if it is intentionally not yet reachable. Offending impl(s): $(echo "$non_stub" | tr '\n' '; ')" >&2
  exit 1
fi

if [[ "$placeholder_present" -eq 1 ]]; then
  echo "check-mcp-stub-ports-vs-caller-auth: OK — placeholder identity still in place, only stub (or @Vetoed) ports wired (phase 1)."
else
  echo "check-mcp-stub-ports-vs-caller-auth: OK — placeholder identity removed; real caller auth landed, real ports permitted."
fi
