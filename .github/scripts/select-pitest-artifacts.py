#!/usr/bin/env python3
"""Select one authoritative PIT report per Admin UI evidence destination."""

import json
import re
import sys


ARTIFACT_NAME = re.compile(r"pitest-[a-z0-9]+(?:-[a-z0-9]+)*\Z")


def select_artifacts(inventory):
    latest_by_name = {}
    for artifact in inventory.get("artifacts", []):
        name = artifact.get("name", "")
        if not name.startswith("pitest-") or artifact.get("expired"):
            continue
        if not ARTIFACT_NAME.fullmatch(name):
            raise ValueError(f"unsafe PIT artifact name: {name!r}")
        artifact_id = artifact.get("id")
        if type(artifact_id) is not int or artifact_id <= 0:
            raise ValueError(f"invalid PIT artifact id for {name}")
        previous = latest_by_name.get(name)
        rank = (artifact.get("created_at") or "", artifact_id)
        if previous is None or rank > (previous.get("created_at") or "", previous["id"]):
            latest_by_name[name] = artifact

    # The enforced authz lane and the advisory full-module lane both upload a
    # libs-runtime report. The full report is the component-wide UI evidence;
    # the authz result remains independently enforced and retained in Actions.
    if "pitest-openbank-libs-runtime" in latest_by_name:
        latest_by_name.pop("pitest-authz", None)

    selected = []
    destinations = set()
    for name, artifact in sorted(latest_by_name.items()):
        component = "openbank-libs-runtime" if name == "pitest-authz" else name.removeprefix("pitest-")
        if component in destinations:
            raise ValueError(f"multiple PIT artifacts target {component}")
        destinations.add(component)
        selected.append((artifact["id"], name, component))
    return selected


if __name__ == "__main__":
    try:
        for artifact_id, name, component in select_artifacts(json.load(sys.stdin)):
            print(artifact_id, name, component)
    except (ValueError, TypeError, KeyError) as exc:
        print(f"PIT artifact selection failed: {exc}", file=sys.stderr)
        sys.exit(1)
