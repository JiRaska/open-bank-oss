#!/usr/bin/env python3
"""Select only the evidence datasets sourced by a completed workflow."""

from __future__ import annotations

import argparse
import json
import re

ALL_DATASETS = ["gate-health", "readiness", "test-intelligence", "security-kpis", "content"]
WORKFLOW_DATASETS = {
    "CI": ["gate-health", "readiness", "test-intelligence", "content"],
    "Services CI": ["test-intelligence"],
    "Security KPI snapshot": ["security-kpis"],
    "Performance gate": ["test-intelligence"],
    "Perf baseline": ["test-intelligence"],
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


WORKFLOW_PATHS = {
    "CI": ".github/workflows/ci.yml",
    "Services CI": ".github/workflows/services-ci.yml",
    "Security KPI snapshot": ".github/workflows/security-kpis.yml",
    "Performance gate": ".github/workflows/perf-gate.yml",
    "Perf baseline": ".github/workflows/perf-baseline.yml",
}
PERFORMANCE_WORKFLOWS = {"Performance gate", "Perf baseline"}
TERMINAL_CONCLUSIONS = {"success", "failure", "cancelled", "timed_out", "action_required", "neutral", "skipped"}


def select_source(event: str, *, event_sha: str, repository: str,
                  workflow_name: str = "", workflow_path: str = "",
                  workflow_sha: str = "", workflow_repository: str = "",
                  workflow_branch: str = "", workflow_status: str = "",
                  workflow_conclusion: str = "") -> str:
    """Read only trusted producers; a failed performance attempt must replace old success.

    Performance evidence is recollected with the current catalogue and collector. Its
    own immutable run identity remains on each observation, separate from publisher code.
    """
    if not re.fullmatch(r"[0-9a-f]{40}", event_sha):
        raise ValueError("evidence source must be a full commit SHA")
    if event != "workflow_run":
        if event not in {"schedule", "workflow_dispatch"}:
            raise ValueError("unsupported evidence trigger")
        return event_sha
    if workflow_name not in WORKFLOW_PATHS or workflow_path != WORKFLOW_PATHS[workflow_name]:
        raise ValueError("unexpected evidence workflow path")
    if (not repository or workflow_repository != repository or workflow_branch != "main"
            or workflow_status != "completed" or not re.fullmatch(r"[0-9a-f]{40}", workflow_sha)):
        raise ValueError("evidence producer is not a completed trusted main run")
    if workflow_name in PERFORMANCE_WORKFLOWS:
        if workflow_conclusion not in TERMINAL_CONCLUSIONS:
            raise ValueError("performance attempt has no terminal verdict")
        return event_sha
    if workflow_conclusion != "success":
        raise ValueError("evidence producer did not succeed")
    return event_sha if workflow_name == "Security KPI snapshot" else workflow_sha


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", required=True)
    parser.add_argument("--workflow-name", default="")
    parser.add_argument("--dataset", default="all")
    parser.add_argument("--with-source", action="store_true")
    for name in ("event-sha", "repository", "workflow-path", "workflow-sha", "workflow-repository",
                 "workflow-branch", "workflow-status", "workflow-conclusion"):
        parser.add_argument("--" + name, default="")
    args = parser.parse_args()
    try:
        selected = select_datasets(args.event, args.workflow_name, args.dataset)
        if args.with_source:
            source_sha = select_source(
                args.event, event_sha=args.event_sha, repository=args.repository,
                workflow_name=args.workflow_name, workflow_path=args.workflow_path,
                workflow_sha=args.workflow_sha, workflow_repository=args.workflow_repository,
                workflow_branch=args.workflow_branch, workflow_status=args.workflow_status,
                workflow_conclusion=args.workflow_conclusion,
            )
            selected = {"sha": source_sha, "datasets": selected}
    except ValueError as exc:
        parser.error(str(exc))
    print(json.dumps(selected, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
