#!/usr/bin/env python3
"""Ensure Admin UI embedded Grafana targets always render a value."""

import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "openbank-admin-ui/src/components/insights/catalog.ts"
DASHBOARD_DIR = ROOT / "openbank-infra/gitops/components/observability"


def embedded_panels() -> dict[str, set[int]]:
    source = CATALOG.read_text()
    constants = {
        "PAYMENT_INSIGHTS": "dashboard-openbank-payment-sla.yaml",
        "LEDGER_INSIGHTS": "dashboard-openbank-ledger-integrity.yaml",
        "HEALTH_INSIGHTS": "dashboard-openbank-slo.yaml",
        "EVENT_INSIGHTS": "dashboard-openbank-event-bus.yaml",
        "AI_INSIGHTS": "dashboard-openbank-ai.yaml",
    }
    result: dict[str, set[int]] = {}
    for name, filename in constants.items():
        match = re.search(rf"export const {name}.*?= \[(.*?)\n\]", source, re.S)
        if not match:
            raise ValueError(f"cannot find {name} in {CATALOG}")
        result[filename] = {int(value) for value in re.findall(r"\bid:\s*(\d+)", match.group(1))}
    return result


def main() -> int:
    failures: list[str] = []
    for filename, panel_ids in embedded_panels().items():
        document = yaml.safe_load((DASHBOARD_DIR / filename).read_text())
        dashboard = json.loads(next(iter(document["data"].values())))
        panels = {panel.get("id"): panel for panel in dashboard["panels"]}
        for panel_id in panel_ids:
            panel = panels.get(panel_id)
            if panel is None:
                failures.append(f"{filename}: missing embedded panel {panel_id}")
                continue
            for target in panel.get("targets", []):
                expression = target.get("expr", "")
                if not re.search(r"\bor\s+vector\((?:0|1|100)\)\s*$", expression):
                    failures.append(
                        f"{filename}: panel {panel_id} target {target.get('refId', '?')} "
                        "has no top-level empty-series fallback"
                    )
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("All Admin UI embedded Grafana targets have a top-level empty-series fallback.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
