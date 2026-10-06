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
# The fleet producer commonly completes around minute 15. A probe at minute 15
# followed by minute 25 can miss that completion by seconds and add ten idle
# minutes to every dependency review. Keep the same 30-minute budget, but bound
# producer-observation gaps to five minutes (ten probes total).
PRODUCER_DELAYS = (0, 60, 120, 240, 300, 240, 180, 240, 240, 180)
FINAL_DELAYS = (0, 10, 30, 60)  # indexing can briefly regress after the first ready response
WARNING = "x-github-dependency-graph-snapshot-warnings"
PRODUCER = "Submit fleet dependency graph"


class TerminalBaseGraphError(RuntimeError):
    """The immutable comparison base has no successful graph producer."""


class TerminalHeadGraphError(RuntimeError):
    """The PR-head producer finished without submitting a successful graph."""


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


def _snapshot_state(response: str) -> str:
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
            if counts:
                base_count, head_count = map(int, counts.groups())
            elif re.fullmatch(r"No snapshots were found for the base SHA [0-9a-f]{40}\.", message):
                return "missing_base"
            elif re.fullmatch(r"No snapshots were found for the head SHA [0-9a-f]{40}\.", message):
                return "missing_head"
            else:
                raise ValueError("unknown dependency snapshot warning")
            if not base_count:
                return "missing_base"
            if not head_count:
                return "missing_head"
            print(f"Snapshot comparison indexed: base={base_count}, head={head_count}")
    return "ready"


def _indexed(response: str) -> bool:
    return _snapshot_state(response) == "ready"


def _base_producer_verdict(response: str) -> str:
    """Classify all pages of Checks API results without mistaking absence for failure."""
    decoder = json.JSONDecoder()
    pages = []
    offset = 0
    while offset < len(response):
        while offset < len(response) and response[offset].isspace():
            offset += 1
        if offset == len(response):
            break
        page, offset = decoder.raw_decode(response, offset)
        if not isinstance(page, dict) or not isinstance(page.get("check_runs"), list):
            raise ValueError("invalid base producer checks response")
        pages.append(page)
    if not pages:
        raise ValueError("empty base producer checks response")
    matches = [
        run
        for page in pages
        for run in page["check_runs"]
        if isinstance(run, dict) and run.get("name") == PRODUCER
    ]
    if any(run.get("conclusion") == "success" for run in matches):
        return "success"
    if any(run.get("status") != "completed" for run in matches):
        return "pending"
    return "terminal" if matches else "unknown"


def wait_for_head_producer(query, sleep=time.sleep, delays=PRODUCER_DELAYS) -> bool:
    """Wait for graph submission before spending the snapshot-indexing window.

    A separate workflow builds the Gradle graph. Starting the 30-minute indexing
    clock while that workflow is queued or building spent ten minutes of it on
    #11497 before GitHub had any head graph to index.
    """
    terminal_seen = 0
    for delay in delays:
        if delay:
            sleep(delay)
        try:
            verdict = _base_producer_verdict(query())
            if verdict == "success":
                return True
            if verdict == "terminal":
                # A rerun can be requested while the previous failed check is still the
                # only visible one. Confirm terminal state at the next sparse probe.
                terminal_seen += 1
                if terminal_seen == 2:
                    raise TerminalHeadGraphError(
                        "PR-head producer finished without a successful dependency graph"
                    )
            else:
                terminal_seen = 0
        except TerminalHeadGraphError:
            raise
        except RuntimeError as exc:
            if "quota is exhausted" in str(exc):
                raise
            terminal_seen = 0
            print(f"::warning::{exc}; will retry producer check within bounded window", flush=True)
        except (ValueError, TypeError) as exc:
            terminal_seen = 0
            print(f"::warning::{exc}; will retry producer check within bounded window", flush=True)
    return False


def wait_for_snapshot(query, sleep=time.sleep, delays=DELAYS, base_verdict=None) -> bool:
    for delay in delays:
        if delay:
            sleep(delay)
        try:
            state = _snapshot_state(query())
            if state == "ready":
                return True
            # The base SHA is immutable. When only the head snapshot is pending,
            # asking for its producer check on every probe spends installation
            # quota without adding evidence or changing the decision.
            if (
                state == "missing_base"
                and base_verdict is not None
                and base_verdict() == "terminal"
            ):
                raise TerminalBaseGraphError(
                    "merge-base producer finished without a successful dependency graph"
                )
        except TerminalBaseGraphError:
            raise
        except RuntimeError as exc:
            if "quota is exhausted" in str(exc):
                raise
            print(f"::warning::{exc}; will retry within bounded window", flush=True)
        except (ValueError, TypeError) as exc:
            # An unknown response is not evidence of a valid graph.
            print(f"::warning::{exc}; will retry within bounded window", flush=True)
    return False


def main() -> int:
    final_check = sys.argv[1:] == ["--final-check"]
    if sys.argv[1:] and not final_check:
        print("usage: wait_dependency_review_snapshot.py [--final-check]", file=sys.stderr)
        return 2
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
        base_verdict = lambda: _base_producer_verdict(
            _gh("--paginate", f"repos/{repo}/commits/{merge_base}/check-runs?per_page=100")
        )
        if not final_check:
            head_verdict = lambda: _gh(
                "--paginate", f"repos/{repo}/commits/{head}/check-runs?per_page=100"
            )
            if not wait_for_head_producer(head_verdict):
                print(
                    "::error title=Dependency producer unavailable::PR-head graph was not "
                    "submitted within 30 minutes.",
                    file=sys.stderr,
                )
                return 1
        if wait_for_snapshot(
            query,
            delays=FINAL_DELAYS if final_check else DELAYS,
            base_verdict=None if final_check else base_verdict,
        ):
            print(
                "Both dependency snapshots are indexed; the comparison has a valid basis."
            )
            return 0
    except (KeyError, ValueError, TypeError, RuntimeError) as exc:
        print(f"::error title=Dependency basis unavailable::{exc}", file=sys.stderr)
        return 1
    print(
        "::error title=Dependency basis unavailable::Snapshots were not indexed within "
        f"{'the final 100-second check' if final_check else '30 minutes'}.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
