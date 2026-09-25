#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Wait sparsely for GitHub to index both sides of a dependency comparison.

dependency-review-action polls a missing snapshot about every ten seconds. A
30-minute wait can exhaust the installation's API quota before the snapshot is
indexed. Keep the same fail-closed basis requirement with seven bounded probes.
"""

import base64
import json
import os
import re
import subprocess
import sys
import time

DELAYS = (0, 60, 120, 240, 480, 600, 300)  # 30 minutes, seven probes
WARNING = "x-github-dependency-graph-snapshot-warnings"


def _gh(*args: str) -> str:
    result = subprocess.run(
        ["gh", "api", *args], capture_output=True, text=True, check=False
    )
    if result.returncode:
        if "API rate limit exceeded for installation" in result.stderr:
            raise RuntimeError("GitHub installation API quota is exhausted")
        raise RuntimeError(
            f"GitHub dependency API unavailable (exit {result.returncode})"
        )
    return result.stdout


def _indexed(response: str) -> bool:
    headers, separator, body = response.partition("\r\n\r\n")
    if not separator:
        headers, separator, body = response.partition("\n\n")
    if not separator or not re.match(
        r"^HTTP/\S+ 200(?: |\r?$)", headers.splitlines()[0]
    ):
        raise ValueError("dependency comparison did not return HTTP 200 headers")
    if not isinstance(json.loads(body), list):
        raise TypeError("dependency comparison body is not an array")
    for line in headers.splitlines()[1:]:
        name, sep, value = line.partition(":")
        if sep and name.lower() == WARNING and value.strip():
            try:
                message = base64.b64decode(value.strip(), validate=True).decode("utf-8")
            except (ValueError, UnicodeDecodeError) as exc:
                raise ValueError("malformed dependency snapshot warning") from exc
            counts = re.search(
                r"base SHA \((\d+)\) and the head SHA \((\d+)\)", message
            )
            if not counts:
                raise ValueError("unknown dependency snapshot warning")
            base_count, head_count = map(int, counts.groups())
            if not base_count or not head_count:
                return False
            print(f"Snapshot comparison indexed: base={base_count}, head={head_count}")
    return True


def wait_for_snapshot(query, sleep=time.sleep, delays=DELAYS) -> bool:
    for delay in delays:
        if delay:
            sleep(delay)
        try:
            if _indexed(query()):
                return True
        except RuntimeError as exc:
            if "quota is exhausted" in str(exc):
                raise
            print(f"::warning::{exc}; will retry within bounded window", flush=True)
        except (ValueError, TypeError) as exc:
            # An unknown response is not evidence of a valid graph.
            print(f"::warning::{exc}; will retry within bounded window", flush=True)
    return False


def main() -> int:
    repo = os.environ["GITHUB_REPOSITORY"]
    base, head = os.environ["BASE_SHA"], os.environ["HEAD_SHA"]
    try:
        comparison = json.loads(_gh(f"repos/{repo}/compare/{base}...{head}"))
        merge_base = comparison["merge_base_commit"]["sha"]
        if not re.fullmatch(r"[0-9a-f]{40}", merge_base):
            raise ValueError("invalid merge-base SHA")
        query = lambda: _gh(
            "-i", f"repos/{repo}/dependency-graph/compare/{merge_base}...{head}"
        )
        if wait_for_snapshot(query):
            print(
                "Both dependency snapshots are indexed; running the full policy review."
            )
            return 0
    except (KeyError, ValueError, TypeError, RuntimeError) as exc:
        print(f"::error title=Dependency basis unavailable::{exc}", file=sys.stderr)
        return 1
    print(
        "::error title=Dependency basis unavailable::Snapshots were not indexed within 30 minutes.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
