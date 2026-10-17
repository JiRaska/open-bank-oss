#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Rewrite sandbox GitOps image references to this run's pushed digests."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def rewrite(root: Path, registry: str, tags_json: str, digests_json: str) -> dict[str, int]:
    tags = json.loads(tags_json, object_pairs_hook=_unique_object)
    digests = json.loads(digests_json, object_pairs_hook=_unique_object)
    if not isinstance(tags, dict) or not isinstance(digests, dict) or set(tags) != set(digests):
        raise ValueError("tag and digest maps must cover exactly the same services")

    gitops = root / "openbank-infra/gitops/components"
    if not gitops.is_dir():
        raise ValueError(f"GitOps component directory is missing: {gitops}")

    counts: dict[str, int] = {}
    for service, tag in tags.items():
        if not isinstance(service, str) or not re.fullmatch(r"openbank-[a-z0-9-]+", service):
            raise ValueError(f"invalid service name: {service!r}")
        if not isinstance(tag, str) or not re.fullmatch(r"sandbox-[A-Za-z0-9._-]+", tag):
            raise ValueError(f"invalid sandbox image tag for {service}")
        digest = digests[service]
        if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise ValueError(f"invalid pushed image digest for {service}")

        image = f"{registry}/{service}"
        pattern = re.compile(re.escape(image) + r":sandbox-[A-Za-z0-9._-]+(?:@sha256:[0-9a-f]{64})?")
        replacement = f"{image}:{tag}@{digest}"
        count = 0
        for manifest in sorted((*gitops.rglob("*.yaml"), *gitops.rglob("*.yml"))):
            original = manifest.read_text(encoding="utf-8")
            updated, changed = pattern.subn(replacement, original)
            if changed:
                manifest.write_text(updated, encoding="utf-8")
                count += changed
        counts[service] = count
    return counts


def main() -> int:
    if len(sys.argv) != 5:
        print(f"usage: {sys.argv[0]} <root> <registry> <tags-json> <digests-json>", file=sys.stderr)
        return 2
    try:
        counts = rewrite(Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4])
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"reconcile image pin rewrite rejected: {exc}", file=sys.stderr)
        return 1
    for service, count in counts.items():
        if count:
            print(f"[{service}] pinned {count} manifest image(s) by exact digest")
        else:
            print(f"WARN: no GitOps manifest references {service}:sandbox-* — skip")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
