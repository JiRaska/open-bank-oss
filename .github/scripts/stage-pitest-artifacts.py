#!/usr/bin/env python3
"""Stage PIT artifacts by evidence lane, never by their shared Gradle module."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import stat
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

ARTIFACT_NAME = re.compile(r"pitest-[a-z0-9]+(?:-[a-z0-9]+)*\Z")


def select_run(completed_inventory: dict, all_inventory: dict) -> int | None:
    """Require two API views to agree before choosing a PIT evidence attempt."""
    def completed_runs(inventory: dict) -> dict[int, tuple[str, str]]:
        runs = inventory.get("workflow_runs")
        if not isinstance(runs, list):
            raise ValueError("PIT run inventory has no workflow_runs list")
        if inventory.get("total_count", len(runs)) != len(runs):
            raise ValueError("PIT run inventory is incomplete; refusing older evidence")
        selected: dict[int, tuple[str, str]] = {}
        for run in runs:
            if not isinstance(run, dict):
                raise ValueError("PIT run inventory contains a non-object entry")
            if run.get("status") != "completed":
                continue
            run_id = run.get("id")
            started = run.get("run_started_at") or run.get("created_at")
            created = run.get("created_at")
            if not isinstance(run_id, int) or run_id <= 0 or not isinstance(started, str) or not isinstance(created, str):
                raise ValueError("PIT run inventory contains incomplete provenance")
            if run_id in selected:
                raise ValueError(f"PIT run inventory duplicates run {run_id}")
            selected[run_id] = (started, created)
        return selected

    filtered = completed_runs(completed_inventory)
    unfiltered = completed_runs(all_inventory)
    if filtered != unfiltered:
        raise ValueError("PIT run inventories disagree; refusing possibly stale evidence")
    return max(filtered, key=lambda run_id: (filtered[run_id][0], run_id)) if filtered else None


def lane(name: str) -> str:
    if not ARTIFACT_NAME.fullmatch(name):
        raise ValueError(f"invalid PIT artifact name: {name!r}")
    owner = "openbank-libs-runtime-authz" if name == "pitest-authz" else name.removeprefix("pitest-")
    if not owner.startswith("openbank-"):
        raise ValueError(f"PIT artifact has no OpenBank evidence lane: {name!r}")
    return owner


def plan(inventory: dict) -> list[tuple[int, str, str]]:
    """Reject ambiguous evidence before the first archive is downloaded or extracted."""
    artifacts = inventory.get("artifacts")
    if not isinstance(artifacts, list):
        raise ValueError("artifact inventory is missing its artifacts list")
    if inventory.get("total_count", len(artifacts)) != len(artifacts):
        raise ValueError("PIT artifact inventory is incomplete; refusing a partial snapshot")
    planned: list[tuple[int, str, str]] = []
    ids: set[int] = set()
    names: set[str] = set()
    lanes: set[str] = set()
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise ValueError("PIT artifact inventory contains a non-object entry")
        name = artifact.get("name")
        if not isinstance(name, str) or not name.startswith("pitest-") or artifact.get("expired"):
            continue
        artifact_id = artifact.get("id")
        if not isinstance(artifact_id, int) or artifact_id <= 0:
            raise ValueError(f"PIT artifact {name!r} has no valid id")
        owner = lane(name)
        if artifact_id in ids or name in names or owner in lanes:
            raise ValueError(f"duplicate PIT artifact id, name, or evidence lane: {name!r} -> {owner!r}")
        ids.add(artifact_id)
        names.add(name)
        lanes.add(owner)
        planned.append((artifact_id, name, owner))
    return planned


def stage(archive: Path, owner: str, root: Path, expected_run_id: str | None = None) -> None:
    """Validate and unpack into a fresh private directory, then publish once."""
    if not re.fullmatch(r"openbank-[a-z0-9]+(?:-[a-z0-9]+)*", owner):
        raise ValueError(f"invalid PIT evidence lane: {owner!r}")
    destination = root / owner / "build" / "reports" / "pitest"
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        raise ValueError(f"PIT evidence lane already exists: {destination}")
    temporary = Path(tempfile.mkdtemp(prefix=".pitest-stage-", dir=destination.parent))
    try:
        with zipfile.ZipFile(archive) as source:
            seen: set[str] = set()
            for entry in source.infolist():
                path = PurePosixPath(entry.filename)
                if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] == ".":
                    raise ValueError(f"unsafe PIT archive path: {entry.filename!r}")
                if entry.filename in seen or stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError(f"duplicate or symlink PIT archive entry: {entry.filename!r}")
                seen.add(entry.filename)
                target = temporary.joinpath(*path.parts)
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(entry) as input_file, target.open("xb") as output_file:
                        shutil.copyfileobj(input_file, output_file)
        if not (temporary / "mutations.xml").is_file() and not (temporary / "test-intelligence-run.json").is_file():
            raise ValueError(f"PIT artifact has neither XML nor run envelope: {archive}")
        envelope_path = temporary / "test-intelligence-run.json"
        if envelope_path.is_file():
            envelope = json.loads(envelope_path.read_text())
            if envelope.get("component") != owner:
                raise ValueError(f"PIT run envelope belongs to another lane: {envelope_path}")
            if expected_run_id is not None and str(envelope.get("run", {}).get("id")) != expected_run_id:
                raise ValueError(f"PIT run envelope belongs to another run: {envelope_path}")
        os.rename(temporary, destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("plan")
    selecting = subcommands.add_parser("select-run")
    selecting.add_argument("completed_inventory", type=Path)
    selecting.add_argument("all_inventory", type=Path)
    staging = subcommands.add_parser("stage")
    staging.add_argument("archive", type=Path)
    staging.add_argument("owner")
    staging.add_argument("root", type=Path)
    staging.add_argument("--run-id")
    args = parser.parse_args()
    try:
        if args.command == "select-run":
            run_id = select_run(
                json.loads(args.completed_inventory.read_text()),
                json.loads(args.all_inventory.read_text()),
            )
            if run_id is not None:
                print(run_id)
        elif args.command == "plan":
            for artifact_id, name, owner in plan(json.load(sys.stdin)):
                print(f"{artifact_id}\t{name}\t{owner}")
        else:
            stage(args.archive, args.owner, args.root, args.run_id)
    except (ValueError, OSError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        print(f"PIT staging refused: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
