#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate that reconcile services map to this run's actual build-push image tag."""

from __future__ import annotations

import json
import re
import sys
from typing import Any


class DuplicateKeyError(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def verify(services_json: str, tags_json: str, build_tag: str, sha: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("workflow SHA must be a full lowercase 40-hex commit")
    if build_tag != f"sandbox-{sha[:8]}":
        raise ValueError("build-push tag does not identify the workflow SHA")

    services = json.loads(services_json)
    if (
        not isinstance(services, list)
        or not services
        or any(not isinstance(service, str) or not re.fullmatch(r"openbank-[a-z0-9-]+", service) for service in services)
        or len(set(services)) != len(services)
    ):
        raise ValueError("service list must contain unique valid service names")

    tags = json.loads(tags_json, object_pairs_hook=_unique_object)
    if not isinstance(tags, dict) or set(tags) != set(services):
        raise ValueError("image tag map must cover exactly the requested services")
    if any(not isinstance(tag, str) or tag != build_tag for tag in tags.values()):
        raise ValueError("every service tag must equal the actual build-push tag")


def main() -> int:
    if len(sys.argv) != 5:
        print(
            f"usage: {sys.argv[0]} <services-json> <image-tags-json> <build-push-tag> <workflow-sha>",
            file=sys.stderr,
        )
        return 2
    try:
        verify(*sys.argv[1:])
    except (DuplicateKeyError, ValueError, json.JSONDecodeError) as exc:
        print(f"reconcile image evidence rejected: {exc}", file=sys.stderr)
        return 1
    print("reconcile image evidence matches this run's exact build-push output")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
