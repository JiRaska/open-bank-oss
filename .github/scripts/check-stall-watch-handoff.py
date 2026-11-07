"""Fail closed on malformed candidate data crossing the stall watch job boundary."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGITS = re.compile(r"[1-9][0-9]*\Z")
REASONS = {"mandatory-build", "no-first-step", "post-action"}


def positive_int(value: object) -> bool:
    return type(value) is int and value > 0


def nonempty(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate(queued: object, stalled: object) -> int:
    if not isinstance(queued, list) or len(queued) > 25:
        raise ValueError("queued handoff is not a bounded array")
    if not isinstance(stalled, list) or len(stalled) > 100:
        raise ValueError("stalled handoff is not a bounded array")
    for item in queued:
        if not isinstance(item, dict) or not (
            positive_int(item.get("runId"))
            and positive_int(item.get("pr"))
            and isinstance(item.get("headSha"), str)
            and SHA.fullmatch(item["headSha"])
        ):
            raise ValueError("queued handoff has an invalid run, PR or head identity")
    for item in stalled:
        if not isinstance(item, dict) or not (
            all(isinstance(item.get(key), str) and DIGITS.fullmatch(item[key]) for key in ("runId", "attempt", "jobId"))
            and item.get("reason") in REASONS
            and all(nonempty(item.get(key)) for key in ("job", "step", "startedAt", "ageMinutes", "url", "runUrl"))
        ):
            raise ValueError("stalled handoff has an invalid run, job or classifier identity")
    return len(queued) + len(stalled)


def self_test() -> None:
    queued = [{"runId": 1, "pr": 2, "headSha": "a" * 40}]
    stalled = [{
        "runId": "1", "attempt": "1", "jobId": "3", "reason": "mandatory-build",
        "job": "Build + test", "step": "Build + test", "startedAt": "2026-01-01T00:00:00Z",
        "ageMinutes": "46", "url": "https://github.com/example/job/3", "runUrl": "https://github.com/example/run/1",
    }]
    assert validate(queued, stalled) == 2
    bad = [({**queued[0], "headSha": "not-a-sha"}, stalled), (queued[0], [{**stalled[0], "jobId": "3;exit"}])]
    for first, second in bad:
        try:
            validate(first if isinstance(first, list) else [first], second)
        except ValueError:
            pass
        else:
            raise AssertionError("malformed handoff accepted")
    for malformed in (None, {}, [queued[0]] * 26):
        try:
            validate(malformed, [])
        except ValueError:
            pass
        else:
            raise AssertionError("unbounded or missing handoff accepted")
    for malformed in ([{**stalled[0], "reason": "unknown"}], [{**stalled[0], "attempt": "0"}]):
        try:
            validate([], malformed)
        except ValueError:
            pass
        else:
            raise AssertionError("invalid stalled identity accepted")
    print("stall watch handoff: 7 fail-closed cases passed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queued", type=Path)
    parser.add_argument("--stalled", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.queued is None or args.stalled is None:
        parser.error("both --queued and --stalled are required")
    count = validate(json.loads(args.queued.read_text()), json.loads(args.stalled.read_text()))
    print(count)


if __name__ == "__main__":
    main()
