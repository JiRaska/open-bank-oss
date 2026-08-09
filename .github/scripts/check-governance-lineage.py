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

WHAT IT CHECKS: for every service's `governance.yaml`, each `lineage` edge must be backed by
grep-verifiable code. An edge is checked in whichever service's code can actually witness it:

  - a `downstream` edge on S naming T is a claim about S's OWN code, verified in S;
  - an `upstream` edge on S naming T is a claim about T's code — so it is verified in T, exactly
    as if T had declared the mirrored downstream edge. It was originally called out of scope for
    that reason, but "someone else's code" is not "nobody's code": 63 upstream edges across 23
    services were never opened by anything, and a service declaring ONLY upstream was skipped
    outright. `openbank-aml-service` had 3 of its 6 upstream edges wrong — two topic sources it
    consumes nothing from, one API caller that mentions it only in prose (#2312, #2315).

Both directions normalize to the same `<source>-><target>:<relation>` key, so a relationship
declared from both ends is checked and allowlisted ONCE, not twice under two spellings.

The backing evidence, for the source service of the edge:
  - `relationType: api`  -> a `@RegisterRestClient(configKey = "<serviceName>")` somewhere under
    this service's `src/main/kotlin` (exact match, or matching once both sides have any trailing
    "-service" suffix stripped — `product-catalog` vs `product-catalog-service` naming varies
    across the fleet).
  - `relationType: topic` -> at least one topic this service PRODUCES (per mechanism 1's map) is
    actually consumed by `serviceName`, OR at least one topic `serviceName` produces is consumed
    by this service — a `topic` edge doesn't name the specific topic, just the service, so this is
    a service-level cross-reference, not a topic-name match.

A service with no `lineage:` key, or neither list, is skipped entirely (nothing declared,
nothing to verify). An edge naming a service directory that does not exist is a finding: an
un-resolvable claim is exactly the class this gate exists for. Findings may be allowlisted with a one-line reason (same idiom as mechanism 1)
for a legitimate case this heuristic can't see — e.g. a REST call made through a shared/generic
client class without a per-target configKey.

THE ALLOWLIST CAN GO STALE, AND THAT IS ALSO A FINDING (#3982). An entry survives its edge being
deleted, and it survives the code growing the very client its reason swore would never exist —
`openbank-interest-service->account-service` sat there reading "never a direct account-service
call" while the service held `@RegisterRestClient(configKey = "account-service")`. An exemption
list that can only grant is furniture, so an entry that no longer applies is reported at the same
severity as an unbacked edge.

WHAT THIS AUDIT STRUCTURALLY CANNOT SEE, and therefore what the allowlist is legitimately for: a
service that reaches its dependency without a per-target `@RegisterRestClient`. customer-edge is
the extreme case — a BFF with ZERO such interfaces, hopping via a Vert.x WebClient built from an
`openbank.edge.<svc>-url` @ConfigProperty. The independent witness for those is the GitOps
Deployment env (`http://<svc>.<ns>.svc:<port>`), which is also what gen-network-policies.py
derives the NetworkPolicies from — NetworkPolicies do NOT read this file's data, so a lineage
edit can never change what traffic is allowed.

ENFORCED since #3982 (ADR-0144 gate-graduation): findings are ::error:: annotations and a
non-zero exit under --enforce. `--self-test` exercises both failure directions on a synthetic
tree; a gate that has only ever passed is unfalsified.

stdlib + PyYAML (already installed earlier in the same CI job, matching check-slo-registry.py).
Usage: check-governance-lineage.py [--root .] [--rules openbank-libs/governance/rules.yaml]
                                   [--enforce] [--self-test]
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
    """Canonical form for comparing a service name against a `@RegisterRestClient` configKey.

    The fleet spells the same service three ways — `balance-service` (configKey and most
    governance.yaml entries), `openbank-balance-service` (directory name, and what an upstream
    edge's target resolves to), and bare `product-catalog` — so both the `openbank-` prefix and
    the `-service` suffix are stripped. Stripping only the suffix silently failed EVERY upstream
    api edge, because that side is always the full directory name.

    A trailing `-api` is stripped too, and that one was a silent false-POSITIVE source: nine
    configKeys in the fleet carry it (`account-api`, `account-service-api`, `balance-api`,
    `document-api`, `ledger-api`, `party-api`, `party-service-api`, `product-catalog-api`,
    `transaction-api`), so e.g. document-service's `ProductCatalogClient(configKey =
    "product-catalog-api")` — a real, live REST call — read as no backing code at all. No service
    directory in the tree ends in `-api`, so the strip cannot collapse two distinct services.
    """
    if name.startswith("openbank-"):
        name = name[len("openbank-"):]
    if name.endswith("-api"):
        name = name[: -len("-api")]
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


def load_governance_lineage(gov_path: pathlib.Path) -> tuple[list[dict], list[dict]]:
    """(downstream, upstream) edge lists declared in a service's governance.yaml."""
    if not gov_path.exists():
        return [], []
    data = yaml.safe_load(gov_path.read_text(encoding="utf-8")) or {}
    lineage = data.get("lineage") or {}
    return (lineage.get("downstream") or []), (lineage.get("upstream") or [])


def service_dir_for(root: pathlib.Path, name: str) -> pathlib.Path | None:
    """The directory of the service an edge names, tolerating the bare/`openbank-` spellings."""
    for candidate in (name, f"{name}-service", f"openbank-{name}", f"openbank-{name}-service"):
        path = root / candidate
        if path.is_dir():
            return path
    return None


def canonical(root: pathlib.Path, name: str) -> str:
    """One spelling per service, for the `<source>-><target>:<relation>` key.

    The docstring above has always claimed a relationship declared from both ends is checked and
    allowlisted ONCE. It was not: the key was built from whatever each side happened to spell, so
    `psd2-service -> openbank-sca-service` and `openbank-psd2-service -> sca-service` were two
    keys for one relationship (both counted, #3982), and the allowlist entry
    `openbank-transaction-service->account-service:api` did not cover the very same relationship
    declared from account-service's end. Resolving each side to its DIRECTORY name makes the
    promise true; a name with no directory in the tree (github, prometheus, temporal) keeps its
    literal spelling, since inventing `openbank-github` would be a lie.
    """
    service_dir = service_dir_for(root, name)
    return service_dir.name if service_dir is not None else name


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


def scan(root: pathlib.Path, rules_path: pathlib.Path) -> dict:
    """Everything the audit finds, as data — so --self-test can assert on it directly."""
    all_producers, all_consumers, _ = liveness.build_topic_maps(root)
    allowlist = load_allowlist(rules_path)

    service_dirs = sorted(d for d in root.glob("openbank-*") if (d / "governance.yaml").exists())

    violations: list[tuple[str, str]] = []
    allowlisted_hits: list[tuple[str, str]] = []
    seen: set[str] = set()
    unresolved: list[tuple[str, str]] = []
    checked = {"downstream": 0, "upstream": 0}
    rest_client_cache: dict[pathlib.Path, set[str]] = {}

    def rest_client_keys_of(path: pathlib.Path) -> set[str]:
        if path not in rest_client_cache:
            rest_client_cache[path] = find_rest_client_config_keys(path)
        return rest_client_cache[path]

    def topic_names(service: str, produced: bool) -> set[str]:
        table = all_producers if produced else all_consumers
        return {topic for topic, services in table.items() if service in services}

    def is_backed(source_dir: pathlib.Path, source: str, target: str, relation: str) -> bool:
        """Is there code in the SOURCE service witnessing `source -> target` over `relation`?"""
        if relation == "api":
            keys = rest_client_keys_of(source_dir)
            normalized_targets = {target, normalize_service_name(target)}
            normalized_keys = keys | {normalize_service_name(k) for k in keys}
            return bool(normalized_targets & normalized_keys)
        # topic: `relationType: topic` names a service, not a topic, so this is a service-level
        # cross-reference in either direction (#1021: both sides must be the full directory name).
        source_full = source if source.startswith("openbank-") else f"openbank-{source}"
        target_full = target if target.startswith("openbank-") else f"openbank-{target}"
        return bool(
            (topic_names(source_full, True) & topic_names(target_full, False))
            or (topic_names(target_full, True) & topic_names(source_full, False))
        )

    for service_dir in service_dirs:
        service = service_dir.name
        downstream, upstream = load_governance_lineage(service_dir / "governance.yaml")

        # Normalize both directions to (source, target): a downstream edge on S naming T is
        # S -> T; an upstream edge on S naming T is T -> S. Same key space, so a relationship
        # declared from both ends is one check and one allowlist entry.
        edges = [("downstream", service, edge) for edge in downstream]
        edges += [("upstream", edge.get("serviceName"), edge) for edge in upstream]

        for direction, source, edge in edges:
            other = edge.get("serviceName")
            relation = edge.get("relationType")
            if not other or not source or relation not in ("api", "topic"):
                continue
            target = other if direction == "downstream" else service
            source_dir = service_dir if direction == "downstream" else service_dir_for(root, source)
            edge_key = f"{canonical(root, source)}->{canonical(root, target)}:{relation}"
            checked[direction] += 1
            if edge_key in seen:
                continue
            seen.add(edge_key)

            if source_dir is None:
                # The edge names something with no directory in this tree: an external system
                # (github, prometheus, temporal) or a typo. Either way there is no code here to
                # witness it, and calling that a code-drift violation would be a category error —
                # so it is reported as its own class rather than folded into the count.
                unresolved.append((edge_key, source))
                continue

            if is_backed(source_dir, source, target, relation):
                continue
            if edge_key in allowlist:
                allowlisted_hits.append((edge_key, allowlist[edge_key]))
            else:
                violations.append((edge_key, relation))

    return {
        "violations": violations,
        "allowlisted_hits": allowlisted_hits,
        "stale_allowlist": sorted(set(allowlist) - {key for key, _ in allowlisted_hits}),
        "unresolved": sorted(set(unresolved)),
        "checked": checked,
        "seen": seen,
        "service_dirs": service_dirs,
    }


def _fixture(tmp: pathlib.Path, name: str, lineage: str, config_keys: tuple[str, ...] = ()) -> None:
    d = tmp / name
    (d).mkdir(parents=True, exist_ok=True)
    (d / "governance.yaml").write_text(f"dataDomain: test\nlineage:\n{lineage}", encoding="utf-8")
    if config_keys:
        src = d / "src" / "main" / "kotlin"
        src.mkdir(parents=True, exist_ok=True)
        body = "\n".join(
            f'@RegisterRestClient(configKey = "{k}")\ninterface C{i}' for i, k in enumerate(config_keys)
        )
        (src / "Clients.kt").write_text(body, encoding="utf-8")


def self_test() -> int:
    """Feed the audit inputs it MUST flag and inputs it MUST NOT, on a synthetic tree.

    A gate that has only ever passed is unfalsified, and this one's failure path is the whole
    point of it. Both directions are exercised: an unbacked edge, and an allowlist entry that no
    longer applies — the second in BOTH of its shapes (edge no longer declared / edge now backed),
    because they arise from different code and only one of them has ever been seen in the wild.
    """
    import tempfile

    failures: list[str] = []

    def check(label: str, condition: bool) -> None:
        print(f"  {'ok  ' if condition else 'FAIL'} {label}")
        if not condition:
            failures.append(label)

    with tempfile.TemporaryDirectory() as td:
        tmp = pathlib.Path(td)
        # alpha declares a downstream edge to beta and holds no client at all -> must be flagged.
        _fixture(tmp, "openbank-alpha-service", "  downstream:\n    - serviceName: beta-service\n      relationType: api\n")
        # beta calls gamma through a `-api`-suffixed configKey -> must NOT be flagged (#3982).
        _fixture(
            tmp,
            "openbank-beta-service",
            "  downstream:\n    - serviceName: gamma-service\n      relationType: api\n",
            config_keys=("gamma-api",),
        )
        # gamma declares the MIRROR of beta's edge under the other spelling -> one relationship,
        # not two (the canonical-key fix; this used to count twice).
        _fixture(tmp, "openbank-gamma-service", "  upstream:\n    - serviceName: openbank-beta-service\n      relationType: api\n")
        rules = tmp / "rules.yaml"

        def write_rules(*entries: str) -> None:
            body = "change_requirements:\n  lineage_code_audit:\n    allowlist:\n"
            body += "".join(f'      - edge: "{e}"\n        reason: "self-test"\n' for e in entries) or "      []\n"
            rules.write_text(body, encoding="utf-8")

        write_rules()
        r = scan(tmp, rules)
        keys = {k for k, _ in r["violations"]}
        check("[flags] an unbacked edge is a violation", "openbank-alpha-service->openbank-beta-service:api" in keys)
        check("[passes] a `-api` configKey counts as backing code", "openbank-beta-service->openbank-gamma-service:api" not in keys)
        check("[passes] a mirrored edge is ONE relationship, not two", len(r["seen"]) == 2)
        check("[passes] a clean-enough tree reports no stale allowlist", r["stale_allowlist"] == [])

        # Allowlisting the real finding must suppress it and NOT read as stale.
        write_rules("openbank-alpha-service->openbank-beta-service:api")
        r = scan(tmp, rules)
        check("[passes] an allowlisted edge is suppressed", r["violations"] == [])
        check("[passes] a USED allowlist entry is not stale", r["stale_allowlist"] == [])

        # Direction 2a: an entry for a relationship nothing declares.
        write_rules("openbank-alpha-service->openbank-beta-service:api", "openbank-alpha-service->openbank-gamma-service:api")
        r = scan(tmp, rules)
        check(
            "[flags] allowlist entry whose edge is no longer declared",
            r["stale_allowlist"] == ["openbank-alpha-service->openbank-gamma-service:api"],
        )

        # Direction 2b: an entry for an edge that IS backed by code — the exemption is unnecessary.
        write_rules("openbank-alpha-service->openbank-beta-service:api", "openbank-beta-service->openbank-gamma-service:api")
        r = scan(tmp, rules)
        check(
            "[flags] allowlist entry whose edge is now backed by code",
            r["stale_allowlist"] == ["openbank-beta-service->openbank-gamma-service:api"],
        )

        # An edge naming nothing in the tree is its own class, never a code-drift violation.
        _fixture(tmp, "openbank-delta-service", "  upstream:\n    - serviceName: github\n      relationType: api\n")
        write_rules("openbank-alpha-service->openbank-beta-service:api")
        r = scan(tmp, rules)
        check("[passes] an edge naming no in-tree service is not a violation", r["violations"] == [])
        check("[flags] ...but it IS reported as unresolved", any("github" in k for k, _ in r["unresolved"]))

    print(
        f"check-governance-lineage --self-test: {'PASS' if not failures else 'FAIL'} "
        f"({10 - len(failures)}/10 cases, both directions)"
    )
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--rules", default="openbank-libs/governance/rules.yaml")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true", dest="self_test")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    result = scan(root, root / args.rules)
    violations = result["violations"]
    allowlisted_hits = result["allowlisted_hits"]
    stale_allowlist = result["stale_allowlist"]
    unresolved = result["unresolved"]
    checked = result["checked"]
    seen = result["seen"]
    service_dirs = result["service_dirs"]

    for edge_key, source in sorted(set(unresolved)):
        print(
            f"::notice::lineage-code-audit: {edge_key} names '{source}', which has no service "
            f"directory in this repo — an external system or a stale name. Not counted as a "
            f"code-drift violation: there is no in-tree code that could witness it either way."
        )

    for edge_key, reason in sorted(allowlisted_hits):
        print(f"::notice::lineage-code-audit: {edge_key} has no backing code found — allowlisted: {reason}")

    # An allowlist that only ever GRANTS is furniture: an entry stays after its edge is deleted,
    # or after the code that was invisible to the heuristic grows a real @RegisterRestClient, and
    # nothing ever says so. Then the list reads as a set of live exemptions when some of it is
    # archaeology, and the next reader trusts a reason nobody has re-checked. So a stale entry is
    # a finding in its own right, same severity as an unbacked edge (issue #3982).
    for edge_key in stale_allowlist:
        annotation = "error" if args.enforce else "warning"
        print(
            f"::{annotation}::lineage-code-audit: rules.yaml lineage_code_audit.allowlist entry "
            f"{edge_key} no longer applies — that relationship is either no longer declared in any "
            f"governance.yaml, or it is now backed by code the audit can see. Delete the entry. An "
            f"allowlist that cannot go stale stops being a list of decisions and becomes furniture."
        )

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
        f"check-governance-lineage: {checked['downstream']} downstream + {checked['upstream']} "
        f"upstream edge(s) declared across {len(service_dirs)} service(s) with a governance.yaml, "
        f"{len(seen)} distinct relationship(s) checked; {len(violations)} unallowlisted "
        f"violation(s), {len(allowlisted_hits)} allowlisted, {len(stale_allowlist)} stale "
        f"allowlist entr(ies), {len(set(unresolved))} naming no in-tree service."
    )

    if (violations or stale_allowlist) and args.enforce:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
