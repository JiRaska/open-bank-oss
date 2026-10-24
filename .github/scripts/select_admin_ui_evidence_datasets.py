#!/usr/bin/env python3
"""Select only the evidence datasets sourced by a completed workflow."""

from __future__ import annotations

import argparse
import json

ALL_DATASETS = ["gate-health", "readiness", "test-intelligence", "security-kpis", "content"]
WORKFLOW_DATASETS = {
    "CI": ["gate-health", "readiness", "test-intelligence", "content"],
    "Services CI": ["test-intelligence"],
    "Security KPI snapshot": ["security-kpis"],
}


def select_datasets(event: str, workflow_name: str = "", dataset: str = "all") -> list[str]:
    if event == "workflow_run":
        if workflow_name not in WORKFLOW_DATASETS:
            raise ValueError("unsupported evidence producer workflow")
        return WORKFLOW_DATASETS[workflow_name]
    if event == "schedule":
        return ALL_DATASETS
    if event == "workflow_dispatch":
        if dataset == "all":
            return ALL_DATASETS
        if dataset not in ALL_DATASETS:
            raise ValueError("unsupported evidence dataset")
        return [dataset]
    raise ValueError("unsupported evidence trigger")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", required=True)
    parser.add_argument("--workflow-name", default="")
    parser.add_argument("--dataset", default="all")
    args = parser.parse_args()
    try:
        selected = select_datasets(args.event, args.workflow_name, args.dataset)
    except ValueError as exc:
        parser.error(str(exc))
    print(json.dumps(selected, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
