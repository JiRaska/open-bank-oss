#!/usr/bin/env python3
"""governance.yaml lineage-vs-code audit (ADR-0160 mechanism 2, rules.yaml: lineage_code_audit).

WHY THIS EXISTS: standing-order-service's governance.yaml declares a `downstream` lineage edge to
transaction-service (`relationType: api`, `description: creates`) — copy-pasted boilerplate with
no backing code. The service's only `@RegisterRestClient` calls sepa-payment-service, never
transaction-service. Nothing ever cross-checked the claim against the actual fleet, the same root
cause as issue #889 (a *different* unverified claim — "this event has a consumer" rather than
"this API edge exists" — but the same failure shape: prose describing runtime behaviour, never
checked against code). This script closes the general case for `governance.yaml` lineage claims,
reusing check-event-consumer-liveness.py's fleet-wide topic map (ADR-0160 mechanism 1) for the
`topic` half instead of re-scanning the fleet a second time.

WHAT IT CHECKS: for every service's `governance.yaml`, each `lineage.downstream` edge (an
UPSTREAM edge is a claim about another service's code, not this one's, so it is out of scope here
— the corresponding downstream edge on the OTHER service's own governance.yaml is what verifies
that relationship) must be backed by grep-verifiable code in the SAME service:
  - `relationType: api`  -> a `@RegisterRestClient(configKey = "<serviceName>")` somewhere under
    this service's `src/main/kotlin` (exact match, or matching once both sides have any trailing
    "-service" suffix stripped — `product-catalog` vs `product-catalog-service` naming varies
    across the fleet).
  - `relationType: topic` -> at least one topic this service PRODUCES (per mechanism 1's map) is
    actually consumed by `serviceName`, OR at least one topic `serviceName` produces is consumed
    by this service — a `topic` edge doesn't name the specific topic, just the service, so this is
    a service-level cross-reference, not a topic-name match.

A service with no `lineage:` key, or no `downstream` list, is skipped entirely (nothing declared,
nothing to verify). Findings may be allowlisted with a one-line reason (same idiom as mechanism 1)
for a legitimate case this heuristic can't see — e.g. a REST call made through a shared/generic
client class without a per-target configKey.

ADVISORY (ADR-0144 gate-graduation): findings are ::warning:: annotations; exits 0 unless invoked
with --enforce. Not yet run against the full fleet to establish a first-scan baseline — do that
before setting target_enforce_date to a real graduation deadline.

stdlib + PyYAML (already installed earlier in the same CI job, matching check-slo-registry.py).
Usage: check-governance-lineage.py [--root .] [--rules openbank-libs/governance/rules.yaml] [--enforce]
"""
from __future__ import annotations

import argparse
import importlib.util
import pathlib
import re
import sys

import yaml

_LIVENESS_SCRIPT = pathlib.Path(__file__).parent / "check-event-consumer-liveness.py"
_spec = importlib.util.spec_from_file_location("check_event_consumer_liveness", _LIVENESS_SCRIPT)
liveness = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(liveness)

REGISTER_REST_CLIENT = re.compile(r'@RegisterRestClient\s*\(\s*configKey\s*=\s*"([^"]+)"')


def normalize_service_name(name: str) -> str:
    return name[: -len("-service")] if name.endswith("-service") else name


def find_rest_client_config_keys(service_dir: pathlib.Path) -> set[str]:
    """Every configKey a service's @RegisterRestClient interfaces declare — the ground-truth
    "this service actually calls that service over REST" signal, not application.yaml's
    rest-client: block (config binding can lag behind or be structured differently)."""
    keys: set[str] = set()
    src = service_dir / "src" / "main" / "kotlin"
    if not src.exists():
        return keys
    for kt_file in src.rglob("*.kt"):
        text = kt_file.read_text(encoding="utf-8", errors="ignore")
        for m in REGISTER_REST_CLIENT.finditer(text):
            keys.add(m.group(1))
    return keys


def load_governance_downstream(gov_path: pathlib.Path) -> list[dict]:
    if not gov_path.exists():
        return []
    data = yaml.safe_load(gov_path.read_text(encoding="utf-8")) or {}
    lineage = data.get("lineage") or {}
    return lineage.get("downstream") or []


def load_allowlist(rules_path: pathlib.Path) -> dict[str, str]:
    if not rules_path.exists():
        return {}
    data = yaml.safe_load(rules_path.read_text(encoding="utf-8")) or {}
    rule = (data.get("change_requirements") or {}).get("lineage_code_audit") or {}
    entries = rule.get("allowlist") or []
    allowlist: dict[str, str] = {}
    for entry in entries:
        key = entry.get("edge")
        reason = entry.get("reason", "")
        if key:
            allowlist[key] = reason
    return allowlist


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--rules", default="openbank-libs/governance/rules.yaml")
    parser.add_argument("--enforce", action="store_true")
    args = parser.parse_args()

    root = pathlib.Path(args.root)
    all_producers, all_consumers, _ = liveness.build_topic_maps(root)
    allowlist = load_allowlist(root / args.rules)

    service_dirs = sorted(d for d in root.glob("openbank-*") if (d / "governance.yaml").exists())

    violations: list[tuple[str, str]] = []
    allowlisted_hits: list[tuple[str, str]] = []
    checked = 0

    for service_dir in service_dirs:
        service = service_dir.name
        downstream = load_governance_downstream(service_dir / "governance.yaml")
        if not downstream:
            continue

        rest_client_keys = None  # lazily computed only if an api edge is present
        for edge in downstream:
            target = edge.get("serviceName")
            relation = edge.get("relationType")
            if not target or relation not in ("api", "topic"):
                continue
            checked += 1
            edge_key = f"{service}->{target}:{relation}"

            if relation == "api":
                if rest_client_keys is None:
                    rest_client_keys = find_rest_client_config_keys(service_dir)
                normalized_targets = {target, normalize_service_name(target)}
                normalized_keys = rest_client_keys | {normalize_service_name(k) for k in rest_client_keys}
                backed = bool(normalized_targets & normalized_keys)
            else:  # topic
                produces = {t for t, producers in all_producers.items() if service in producers}
                consumes = {t for t, consumers in all_consumers.items() if service in consumers}
                target_produces = {t for t, producers in all_producers.items() if target in producers}
                target_consumes = {t for t, consumers in all_consumers.items() if target in consumers}
                backed = bool((produces & target_consumes) or (target_produces & consumes))

            if backed:
                continue
            if edge_key in allowlist:
                allowlisted_hits.append((edge_key, allowlist[edge_key]))
            else:
                violations.append((edge_key, relation))

    for edge_key, reason in sorted(allowlisted_hits):
        print(f"::notice::lineage-code-audit: {edge_key} has no backing code found — allowlisted: {reason}")

    for edge_key, relation in sorted(violations):
        annotation = "error" if args.enforce else "warning"
        kind = "a matching @RegisterRestClient(configKey=...)" if relation == "api" else "any actual topic overlap"
        print(
            f"::{annotation}::lineage-code-audit: governance.yaml edge {edge_key} has no backing code — "
            f"{kind} not found. If this is a real dependency implemented differently than this "
            f"heuristic expects, add it to rules.yaml: lineage_code_audit.allowlist with a one-line "
            f"reason. If not, this is the #889 failure class — a lineage claim nobody verified."
        )

    print(
        f"check-governance-lineage: {checked} downstream edge(s) checked across "
        f"{len(service_dirs)} service(s) with a governance.yaml; {len(violations)} unallowlisted "
        f"violation(s), {len(allowlisted_hits)} allowlisted."
    )

    if violations and args.enforce:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
