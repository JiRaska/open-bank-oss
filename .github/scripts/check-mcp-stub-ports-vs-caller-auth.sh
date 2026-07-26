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
#   while McpEndpoint still carries the `agent:mcp-anonymous` placeholder constant.
#
# POST-CUTOVER (#2401) — the invariant above is now UNREACHABLE, and that is why there is a
# second one. ADR-0195 step 4 completed the cutover: the placeholder is gone from McpEndpoint,
# RealAccountReadPort is wired, and `placeholder_present` is therefore permanently 0, so the
# pre-cutover rule can never fire again. A guard whose trigger was deleted by the fix it guarded
# still reads as coverage while checking nothing. Section 3 inverts it: after the cutover the
# SHARED FALLBACK IDENTITY is the violation, because every caller now presents its own `sub` and
# reintroducing one id would silently collapse them all onto one charter's grant.
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

# Does a Kotlin file contain the forbidden id as CODE (not in a comment)? Prints the path when it
# does, nothing when it does not. Kotlin block comments NEST, so the stripper mirrors that; a KDoc
# containing "/*" must not close the comment early and leak the rest of the file back into scope.
kotlin_code_mentions() {
  python3 - "$@" <<'PYSTRIP'
import sys
from pathlib import Path

FORBIDDEN = "agent:mcp-anonymous"


def strip_comments(text: str) -> str:
    """Kotlin source with // and (NESTING) /* */ comments removed, string literals preserved."""
    out = []
    i, n, depth = 0, len(text), 0
    while i < n:
        two = text[i:i + 2]
        if depth:
            if two == "/*":
                depth += 1
                i += 2
            elif two == "*/":
                depth -= 1
                i += 2
            else:
                i += 1
            continue
        if two == "/*":
            depth = 1
            i += 2
        elif two == "//":
            j = text.find("\n", i)
            i = n if j == -1 else j
        elif text[i] == '"':
            if text[i:i + 3] == '"""':
                j = text.find('"""', i + 3)
                j = n if j == -1 else j + 3
                out.append(text[i:j])
                i = j
            else:
                j = i + 1
                while j < n and text[j] != '"':
                    j += 2 if text[j] == "\\" else 1
                out.append(text[i:j + 1])
                i = j + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


targets = []
for arg in sys.argv[1:]:
    path = Path(arg)
    targets.extend(sorted(path.rglob("*.kt")) if path.is_dir() else [path])
print("\n".join(str(p) for p in targets if FORBIDDEN in strip_comments(p.read_text())))
PYSTRIP
}

# 0. Has the ADR-0195 step-4 cutover happened? McpEndpoint resolving the caller through
#    CallerContextResolver is the structural signal — it is the class that reads the token's real
#    `sub`. This selects WHICH invariant applies below, so that neither one is silently vacuous:
#    pre-cutover the placeholder is expected and a real port is the violation; post-cutover the
#    placeholder is itself the violation. Without this split the pre-cutover rule survives the fix
#    that made it unreachable and keeps reporting a green about nothing.
cutover_done=0
if grep -q 'CallerContextResolver' "$ENDPOINT"; then
  cutover_done=1
fi

# 1. Is resolveContext still the phase-1 placeholder? The load-bearing signal is the literal
#    placeholder principal id, IN CODE. Deliberately not a raw grep: this repo has been bitten
#    three times by a text guard flagging the prose that explains the bug it exists to catch, and
#    that is not hypothetical here — with a raw grep, adding a KDoc that merely mentions the
#    retired `agent:mcp-anonymous` made this guard fire and announce an "UNAUTHENTICATED read of
#    any account" that did not exist. Comments are stripped (Kotlin block comments NEST) so only a
#    real constant counts.
placeholder_present=0
if [[ -n "$(kotlin_code_mentions "$ENDPOINT")" ]]; then
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

if [[ "$cutover_done" -eq 0 && "$placeholder_present" -eq 1 && -n "$non_stub" ]]; then
  echo "::error title=MCP caller-auth guard (BLOCKER #2206)::A non-stub, non-@Vetoed read/proposal port is wired while McpEndpoint.resolveContext() still returns the hardcoded 'agent:mcp-anonymous' placeholder. This turns get_balance/list_accounts into an UNAUTHENTICATED read of any account. Land real caller identity + PSD2 consent binding (resolveContext) BEFORE or ATOMICALLY WITH the real port — or mark the class @Vetoed if it is intentionally not yet reachable. Offending impl(s): $(echo "$non_stub" | tr '\n' '; ')" >&2
  exit 1
fi

if [[ "$cutover_done" -eq 0 ]]; then
  echo "check-mcp-stub-ports-vs-caller-auth: OK — pre-cutover; placeholder identity in place, only stub (or @Vetoed) ports wired (phase 1)."
  exit 0
fi

# 3. POST-CUTOVER (#2401). Everything above is a pre-cutover guard, and ADR-0195 step 4 finished
#    the cutover: `agent:mcp-anonymous` no longer appears in McpEndpoint, so `placeholder_present`
#    is now permanently 0 and section 1 can never fire again. A guard whose trigger condition was
#    deleted by the fix it was guarding does not become harmless — it becomes a green that asserts
#    nothing, which is worse than no guard because it still reads as coverage.
#
#    So the invariant inverts. Before the cutover the placeholder was the accepted state and a real
#    port was the danger. After it, the placeholder itself is the danger: every MCP caller now
#    presents its own `sub`, and reintroducing a shared fallback id would silently collapse all of
#    them back onto the single `mcp-anonymous` charter's five-tool grant — a second caller
#    inheriting the first's authorization by construction, while per-caller identity still looks
#    correct in the audit trail. That is exactly the widening #2401 is open about, and no other
#    gate can see it.
#
#    Checked against CODE, not prose: Kotlin comments are stripped first (block comments NEST in
#    Kotlin, so a KDoc containing "/*" must not close the comment early), because this guard's own
#    documentation quotes the forbidden id and a naive text match would flag the explanation of the
#    bug instead of the bug.
fallback_reintroduced="$(kotlin_code_mentions "$SVC")"

if [[ -n "$fallback_reintroduced" ]]; then
  echo "::error title=MCP shared-fallback identity reintroduced (#2401)::Caller authentication has landed (ADR-0195 step 4): every MCP caller presents its own token 'sub' and the PDP is asked about that caller. A source file now hardcodes 'agent:mcp-anonymous' again, which collapses every caller back onto that single charter's five-tool grant — a new caller silently inherits the previous one's authorization while the audit trail still shows per-caller identity. Give the caller its own charter in openbank-libs/governance/agents.yaml instead. Offending file(s): $(echo "$fallback_reintroduced" | tr '\n' '; ')" >&2
  exit 1
fi

echo "check-mcp-stub-ports-vs-caller-auth: OK — caller auth landed, real ports permitted, and no shared fallback identity reintroduced."
