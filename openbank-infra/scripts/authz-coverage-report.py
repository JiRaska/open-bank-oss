#!/usr/bin/env python3
"""authz coverage report — static @Authorize vs rest.rego analysis (ADR-0034 Phase 5, #266).

Before a service's AUTHZ_ENFORCE flips from advisory to enforce, every action its
@Authorize-annotated endpoints declare must have at least one plausible allow path
in openbank-libs/governance/policies/rest.rego for every legitimate caller type.
Advisory mode hides the gap: the interceptor logs the would-be deny and lets the
request through; enforce turns each uncovered (action, caller) pair into a 403.

Since the Phase 5 rollout (issues #263/#266) some services also mount a per-service
REST extension — an `allowed_reasons` block inlined as a heredoc in that service's
openbank-infra/gitops/components/<svc>/gen-<svc>-opa-bundle.sh (e.g. pid_rest_ext.rego,
copilot_rest_ext.rego), merged into the same `openbank.rest` package at the OPA bundle
level (ADR-0034). Those extension reasons are NOT in rest.rego, so without reading them
this tool reports false-negative "uncovered"/"partial" for actions the deployed bundle
already allows. discover_ext_rego() below pulls the heredoc out of every generator script
found and feeds its allowed_reasons into the classification, so the report reflects what
is actually deployed, not just the shared base policy.

This tool statically inventories every @Authorize(action, resource) in the fleet
and classifies each action against the rest.rego reason rules:

    *.read / *.list / *.search    operator-read-any (HUMAN OPERATOR/ADMIN);
                                  compliance-read-any (.read); party-self-service
                                  (resource-scoped read/list where id == JWT sub)
    customer.*                    customer-self-action (any authenticated HUMAN)
    notification.* / device.*     edge-service-notification (customer-edge's
                                  client_credentials identity, HUMAN principal.id ==
                                  "service-account-openbank-edge" — NOT a SERVICE
                                  principal.type; see M2M caveat below)
    other verbs WITH resource     operator-on-own-tenant ONLY (HUMAN OPERATOR whose
                                  tenant matches resource.attributes.tenant)
    other verbs WITHOUT resource  NO allow path — operator-on-own-tenant requires
                                  input.resource; nothing else matches a non-read,
                                  non-customer, non-notification action

M2M caveat (the Phase-5 blocker, resolved for customer-edge, open elsewhere): OPA's
input.principal.type is NEVER "SERVICE" in this fleet — AuthorizeInterceptor.
principalType() only ever emits ANONYMOUS/AI_AGENT/HUMAN (M2M calls carry a Keycloak
client_credentials JWT, which the interceptor classifies as HUMAN), and no Keycloak
client is ever granted ROLE_SERVICE. Any rego rule gated on `principal.type ==
"SERVICE"` is dead code that can never fire. edge-service-notification was fixed to
gate on the edge's verified principal.id instead (see rest.rego); any OTHER
service-to-service caller (e.g. a money-path write endpoint like
transaction-service -> ledger.create) still has NO allow path today and will 403
under enforce unless it is verified against actual caller code and given the same
identity-based treatment. Static analysis cannot see runtime callers — check the
OPA advisory decision logs (AuditEvent decision_reason) before any flip.

Output: a Markdown report (stdout). Not a CI gate — an analysis tool for the
per-service flip checklist on #266.

Usage:  python3 openbank-infra/scripts/authz-coverage-report.py [--money-path-only]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

AUTHORIZE_RE = re.compile(r'@Authorize\(\s*action\s*=\s*"([^"]+)"(?:\s*,\s*resource\s*=\s*"([^"]*)")?')
# rest.rego's read-family allow rules (operator-read-any, party-self-service) match ONLY
# {"list","read"}; compliance-read-any matches only ".read". NO rule matches ".search", so a
# *.search action has no allow path and must NOT be classified covered.
READ_VERBS = ("read", "list")

# --- service-local ext rego extraction (ADR-0034, issues #263/#266) ------------------------
# Generator scripts assign the ext rego to a shell var via `VAR=$(cat << 'REGO' ... REGO )`
# and separately echo its ConfigMap data key as `<name>_rest_ext.rego: |` right before
# dumping it — that echo is the authoritative filename (not the shell var name, which is
# not standardised, e.g. PID_REST_EXT / COPILOT_REST_EXT).
EXT_HEREDOC_RE = re.compile(r"\$\(\s*cat\s*<<-?\s*'?REGO'?\s*\n(.*?)\nREGO\s*\n\)", re.DOTALL)
EXT_FILENAME_RE = re.compile(r'echo\s+"\s*(\w+_rest_ext\.rego):\s*\|"')
REASON_HEAD_RE = re.compile(r'allowed_reasons\s+contains\s+"([^"]+)"\s+if\s*\{')
ACTION_EQ_RE = re.compile(r'input\.action\s*==\s*"([^"]+)"')
ACTION_IN_RE = re.compile(r'input\.action\s+in\s+\{([^}]*)\}')
ACTION_PREFIX_RE = re.compile(r'startswith\(\s*input\.action\s*,\s*"([^"]+)"\s*\)')
QUOTED_RE = re.compile(r'"([^"]+)"')

# (kind, value, reason, ext_filename) — kind "exact" matches action == value,
# "prefix" matches action.startswith(value).
ExtRule = tuple[str, str, str, str]


def _extract_block(text: str, brace_start: int) -> str:
    """text[brace_start] == '{'; return the body up to its matching close brace.

    A plain non-greedy regex breaks here because bodies routinely contain their own
    braces (e.g. `input.action in {"a", "b"}`), so the first `}` is not the block end.
    """
    depth = 0
    for i in range(brace_start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[brace_start + 1 : i]
    raise ValueError("unbalanced braces in ext rego allowed_reasons block")


def _parse_ext_rego(rego_text: str, ext_filename: str) -> list[ExtRule]:
    rules: list[ExtRule] = []
    for m in REASON_HEAD_RE.finditer(rego_text):
        reason = m.group(1)
        body = _extract_block(rego_text, m.end() - 1)
        for am in ACTION_EQ_RE.finditer(body):
            rules.append(("exact", am.group(1), reason, ext_filename))
        for am in ACTION_IN_RE.finditer(body):
            for qm in QUOTED_RE.finditer(am.group(1)):
                rules.append(("exact", qm.group(1), reason, ext_filename))
        for am in ACTION_PREFIX_RE.finditer(body):
            rules.append(("prefix", am.group(1), reason, ext_filename))
    return rules


def _resolve_service_module(name: str, module_dirs: set[str]) -> str | None:
    """Map a name fragment (a gitops component dir, or a gen-<name>-opa-bundle.sh
    filename's <name>) to the openbank-* service module whose @Authorize inventory it
    governs. Naming isn't 1:1 (e.g. "notifications" -> openbank-notification-service;
    "fraud-service" -> openbank-fraud-service, no double "-service"; "domestic-payment"
    -> openbank-domestic-payment, no "-service" suffix at all), so try the plausible
    variants against the module dirs actually present on disk.
    """
    candidates = [f"openbank-{name}-service", f"openbank-{name}"]
    if name.endswith("s"):
        singular = name[:-1]
        candidates += [f"openbank-{singular}-service", f"openbank-{singular}"]
    for c in candidates:
        if c in module_dirs:
            return c
    return None


GEN_SCRIPT_NAME_RE = re.compile(r"^gen-(.+)-opa-bundle\.sh$")


def discover_ext_rego(root: Path, module_dirs: set[str]) -> dict[str, list[ExtRule]]:
    """Extract every service-local ext rego heredoc from gen-<svc>-opa-bundle.sh generators.

    Returns service module dir -> list of ExtRule. A generator with no ext rego heredoc
    (e.g. customer-edge, notifications today — they only mount the shared rest.rego) is
    simply absent from the result.
    """
    by_service: dict[str, list[ExtRule]] = {}
    for gen_script in sorted(root.glob("openbank-infra/gitops/components/*/gen-*-opa-bundle.sh")):
        component = gen_script.parent.name
        text = gen_script.read_text(encoding="utf-8", errors="replace")
        heredocs = [hm.group(1) for hm in EXT_HEREDOC_RE.finditer(text)]
        if not heredocs:
            continue
        filenames = [fm.group(1) for fm in EXT_FILENAME_RE.finditer(text)]
        if len(filenames) != len(heredocs):
            print(
                f"warn: {gen_script}: found {len(heredocs)} ext rego heredoc(s) but "
                f"{len(filenames)} '<name>_rest_ext.rego: |' echo(s) — skipping "
                "(generator doesn't match the expected convention)",
                file=sys.stderr,
            )
            continue
        # Resolve from the generator's OWN filename first, not the parent directory —
        # several components (e.g. "payments") host multiple services' generators in
        # one shared gitops directory, so the directory name alone cannot distinguish
        # gen-transaction-opa-bundle.sh from gen-clearing-opa-bundle.sh in the same dir
        # (this silently dropped 7/16 money-path services' ext rego before this fix).
        name_match = GEN_SCRIPT_NAME_RE.match(gen_script.name)
        service = None
        if name_match:
            service = _resolve_service_module(name_match.group(1), module_dirs)
        if service is None:
            service = _resolve_service_module(component, module_dirs)
        if service is None:
            print(
                f"warn: {gen_script}: cannot map generator filename or component "
                f"'{component}' to an openbank-*-service module dir — skipping its ext rego",
                file=sys.stderr,
            )
            continue
        for body, filename in zip(heredocs, filenames, strict=False):
            try:
                rules = _parse_ext_rego(body, filename)
            except ValueError as e:
                # A single malformed allowed_reasons block (e.g. unbalanced braces from a
                # hand-edited generator) must not crash the whole report — every other
                # malformed-input case in this loop is a skip-with-warning, and one bad
                # heredoc shouldn't erase ext-rego coverage for every other service too.
                print(
                    f"warn: {gen_script}: failed to parse '{filename}' ext rego "
                    f"({e}) — skipping this heredoc, its allow rules will NOT be "
                    "reflected in the report",
                    file=sys.stderr,
                )
                continue
            by_service.setdefault(service, []).extend(rules)
    return by_service


def ext_covered(rules: list[ExtRule], action: str) -> tuple[str, str] | None:
    """Return (comma-joined reasons, ext filename) if any ext rule allows this action."""
    reasons: list[str] = []
    ext_filename = ""
    for kind, value, reason, filename in rules:
        hit = (kind == "exact" and action == value) or (kind == "prefix" and action.startswith(value))
        if hit:
            if reason not in reasons:
                reasons.append(reason)
            ext_filename = filename
    if not reasons:
        return None
    return ", ".join(sorted(reasons)), ext_filename


def money_path_services(root: Path) -> list[str]:
    rules = (root / "openbank-libs/governance/rules.yaml").read_text()
    # Entries may carry a trailing `# ADR-…` comment (fraud/billing do), so an anchored
    # `\S+\n` would silently drop those lines. Match the whole list line, extract the token.
    m = re.search(r"^money_path_services:\n((?:[ \t]+-[ \t]+\S.*\n)+)", rules, re.MULTILINE)
    if not m:
        sys.exit("cannot parse money_path_services from rules.yaml")
    return re.findall(r"-[ \t]+(\S+)", m.group(1))


def classify(action: str, resource: str) -> tuple[str, str]:
    """Return (coverage, note) for one @Authorize declaration."""
    verb = action.rsplit(".", 1)[-1]
    if action.startswith("customer."):
        return "covered", "customer-self-action (any authenticated HUMAN)"
    if action.split(".", 1)[0] in ("notification", "device"):
        return "covered", "edge-service-notification (edge identity) + read rules"
    if verb in READ_VERBS:
        scoped = " + party-self-service (resource-scoped)" if resource else ""
        return "covered", f"operator-read-any / compliance-read-any{scoped}"
    if resource:
        return "partial", (
            "operator-on-own-tenant ONLY — requires HUMAN OPERATOR with matching "
            "resource tenant attribute; NO M2M (SERVICE) path"
        )
    return "uncovered", (
        "NO allow path: non-read action without a resource — operator-on-own-tenant "
        "requires input.resource; no SERVICE rule matches"
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--money-path-only", action="store_true")
    args = ap.parse_args()

    root = Path(".")
    mp = set(money_path_services(root))

    inventory: dict[str, list[tuple[str, str, str]]] = {}
    module_dirs: set[str] = set()
    for kt in root.glob("openbank-*/src/main/kotlin/**/*.kt"):
        service = kt.parts[0]
        module_dirs.add(service)
        for m in AUTHORIZE_RE.finditer(kt.read_text(encoding="utf-8", errors="replace")):
            inventory.setdefault(service, []).append((m.group(1), m.group(2) or "", str(kt)))

    ext_index = discover_ext_rego(root, module_dirs)

    print("# @Authorize coverage vs rest.rego (static) — ADR-0034 Phase 5 flip readiness\n")
    print("Caller legend: covered = an allow reason exists for the expected human caller; "
          "partial = human-operator-only path (no M2M); uncovered = no allow path at all. "
          "A `covered` row attributed to a service-local ext rego reflects an allow path from "
          "that service's own gen-<svc>-opa-bundle.sh extension, not the shared rest.rego.\n")

    totals = {"covered": 0, "partial": 0, "uncovered": 0}
    for service in sorted(inventory):
        if args.money_path_only and service not in mp:
            continue
        rows = inventory[service]
        flag = " (money-path)" if service in mp else ""
        ext_rules = ext_index.get(service, [])
        print(f"## {service}{flag}\n")
        if ext_rules:
            ext_files = sorted({filename for _, _, _, filename in ext_rules})
            print(f"Service-local ext rego in effect: {', '.join(f'`{f}`' for f in ext_files)}\n")
        print("| action | resource | coverage | allow path |")
        print("|---|---|---|---|")
        seen: set[tuple[str, str]] = set()
        for action, resource, _ in sorted(rows):
            if (action, resource) in seen:
                continue
            seen.add((action, resource))
            hit = ext_covered(ext_rules, action)
            if hit:
                reasons, ext_filename = hit
                cov, note = "covered", f"{reasons} (service-local ext: `{ext_filename}`)"
            else:
                cov, note = classify(action, resource)
            totals[cov] += 1
            marker = {"covered": "✅", "partial": "⚠️", "uncovered": "❌"}[cov]
            print(f"| `{action}` | `{resource or '—'}` | {marker} {cov} | {note} |")
        print()

    print(f"**Totals:** {totals['covered']} covered · {totals['partial']} partial · "
          f"{totals['uncovered']} uncovered\n")
    print("> Static approximation only. Before any AUTHZ_ENFORCE flip, confirm against the "
          "OPA advisory decision logs (AuditEvent decision_reason) that no legitimate caller "
          "hits a would-be deny — especially M2M callers, which this analysis cannot see and "
          "which never present principal.type == \"SERVICE\" (see the M2M caveat above).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
