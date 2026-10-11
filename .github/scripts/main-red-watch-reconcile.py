#!/usr/bin/env python3
"""Emit the exact watched workflow names for scheduled catch-up dispatch."""

import json
from pathlib import Path

import yaml


def watched_names(path: Path) -> list[str]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    trigger = document.get("on", document.get(True))
    names = trigger["workflow_run"]["workflows"]
    if not isinstance(names, list) or not names or len(names) > 100 or any(
        not isinstance(name, str) or not name for name in names
    ) or len(names) != len(set(names)):
        raise ValueError("Invalid watched workflow names; reconciliation cannot be partial")
    return names


if __name__ == "__main__":
    print(json.dumps(watched_names(Path(".github/workflows/main-red-watch.yml"))))
