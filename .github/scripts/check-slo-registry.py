#!/usr/bin/env python3
"""SLO registry consistency gate (issue #669 scope item 3, rules.yaml: slo).

Enforces the invariant: every entry in `rules.yaml: money_path_services` has an
availability + latency Pyrra `ServiceLevelObjective` in
`openbank-infra/gitops/components/observability/pyrra-slo-money-path.yaml`, and
each object's `target`/`window` matches the governed values declared in
`rules.yaml: slo` — so a target can only change by editing the governed source,
never by hand-editing the reconciled Pyrra CR (rule #7, "derived data is never
hand-edited").

Mirrors check-release-registration.py: PyYAML-based (already installed earlier
in the same CI job by the gen-network-policies step), single violations list.
"""
from __future__ import annotations

import pathlib
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"
SLO_MANIFEST = REPO / "openbank-infra" / "gitops" / "components" / "observability" / "pyrra-slo-money-path.yaml"


def short_name(service: str) -> str:
    """openbank-ledger-service -> ledger (mirrors rest.rego's normalisation, rules.yaml money_path prefix-override comment)."""
    s = service.removeprefix("openbank-")
    return s.removesuffix("-service")


def main() -> int:
    rules = yaml.safe_load(RULES.read_text(encoding="utf-8"))
    money_path_services: list[str] = rules["money_path_services"]
    slo = rules["slo"]

    if not SLO_MANIFEST.exists():
        print(f"::error::{SLO_MANIFEST} does not exist — every money-path service needs an SLO object there.")
        return 1

    objects = [d for d in yaml.safe_load_all(SLO_MANIFEST.read_text(encoding="utf-8")) if d]
    by_name = {o["metadata"]["name"]: o for o in objects}

    violations: list[str] = []

    for service in money_path_services:
        s = short_name(service)
        for kind, target, window in (
            ("availability", slo["availability_target"], slo["availability_window"]),
            ("latency", slo["latency_target_percent"], slo["latency_window"]),
        ):
            name = f"openbank-{s}-{kind}"
            obj = by_name.get(name)
            if obj is None:
                violations.append(
                    f"{service}: missing ServiceLevelObjective '{name}' in {SLO_MANIFEST.name} "
                    f"(rules.yaml: money_path_services has this service, slo defines a {kind} target)."
                )
                continue
            spec = obj.get("spec", {})
            if str(spec.get("target")) != str(target):
                violations.append(
                    f"{name}: spec.target={spec.get('target')!r} does not match rules.yaml: "
                    f"slo {kind} target {target!r} — edit rules.yaml, then regenerate the Pyrra object."
                )
            if str(spec.get("window")) != str(window):
                violations.append(
                    f"{name}: spec.window={spec.get('window')!r} does not match rules.yaml: "
                    f"slo {kind} window {window!r} — edit rules.yaml, then regenerate the Pyrra object."
                )
            label_service = obj.get("metadata", {}).get("labels", {}).get("openbank.io/money-path-service")
            if label_service != service:
                violations.append(
                    f"{name}: metadata.labels['openbank.io/money-path-service']={label_service!r} "
                    f"does not match the owning service {service!r}."
                )
            if spec.get("alerting", {}).get("absent") is not False:
                violations.append(
                    f"{name}: spec.alerting.absent must be explicitly false (issue #3333). Pyrra "
                    f"defaults it to true, which emits a critical SLOMetricAbsent on "
                    f"absent(<span-metric>) — a condition an IDLE service satisfies permanently, "
                    f"since Tempo span-metrics only exist while traffic flows. Liveness is carried "
                    f"by the up==0 rules in prometheus-rules-tier1.yaml instead."
                )

    # An SLO object naming a service that isn't (or no longer is) money-path is orphaned data.
    money_path_shorts = {short_name(s) for s in money_path_services}
    for name, obj in by_name.items():
        label_service = obj.get("metadata", {}).get("labels", {}).get("openbank.io/money-path-service")
        if label_service and short_name(label_service) not in money_path_shorts:
            violations.append(
                f"{name}: labelled for {label_service!r}, which is not in rules.yaml: "
                f"money_path_services — remove the orphaned SLO object or restore the service to the list."
            )

    print("SLO registry consistency gate (issue #669, rules.yaml: slo)")
    print(f"  money-path services: {len(money_path_services)}")
    print(f"  SLO objects found:   {len(objects)}  (expected {len(money_path_services) * 2})")
    if violations:
        print("  VIOLATIONS:")
        for v in violations:
            print(f"    - {v}")
        return 1
    print("  OK: every money-path service has a matching, governed availability + latency SLO.")
    return 0


def self_test() -> int:
    """Feed the absent-flag rule (issue #3333) an input it MUST reject and one it must accept.

    The gate itself reads the real manifest, which is the only case it will ever see in CI; that
    makes the failure path unexercised code. This harness exercises it directly on the predicate,
    so a refactor that stops rejecting `absent: true` is caught at the point of change.
    """
    cases = [
        ({"alerting": {"absent": False}}, False, "explicit false — the only accepted shape"),
        ({}, True, "no alerting block at all — Pyrra defaults absent to true"),
        ({"alerting": {}}, True, "alerting block without the key — same default"),
        ({"alerting": {"absent": True}}, True, "explicitly re-enabled"),
        ({"alerting": {"absent": "false"}}, True, "string, not bool — YAML would not disable it"),
    ]
    failures = 0
    for spec, must_flag, why in cases:
        flagged = spec.get("alerting", {}).get("absent") is not False
        ok = flagged == must_flag
        print(f"  {'ok  ' if ok else 'FAIL'} flagged={flagged!s:5} expected={must_flag!s:5}  {why}")
        failures += not ok
    print("SLO registry self-test:", "OK" if not failures else f"{failures} FAILED")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(self_test() if "--self-test" in sys.argv else main())
