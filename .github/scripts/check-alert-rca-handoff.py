"""Bind the weekly RCA read-only result to its privileged publisher."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

SHA = re.compile(r"[0-9a-f]{40}\Z")


def files(root: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("RCA handoff contains a symlink")
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if relative == "handoff.json":
            continue
        if relative != "recurrences.json" and not relative.startswith("docs/runbooks/alert-rca/"):
            raise ValueError(f"unexpected RCA handoff path: {relative}")
        result[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    if "recurrences.json" not in result:
        raise ValueError("RCA handoff lacks recurrences.json")
    return result


def build(root: Path, source_sha: str, observation_count: int, ledger_exit: int) -> dict[str, object]:
    if not SHA.fullmatch(source_sha) or observation_count < 0 or ledger_exit not in (0, 2):
        raise ValueError("invalid RCA source identity or outcome")
    content = json.loads((root / "recurrences.json").read_text())
    if not isinstance(content, list):
        raise TypeError("RCA recurrence result is not an array")
    return {
        "sourceSha": source_sha,
        "observationCount": observation_count,
        "ledgerExit": ledger_exit,
        "recurrenceCount": len(content),
        "files": files(root),
    }


def verify(root: Path, source_sha: str, observation_count: int, ledger_exit: int) -> None:
    manifest = json.loads((root / "handoff.json").read_text())
    expected = build(root, source_sha, observation_count, ledger_exit)
    if manifest != expected:
        raise ValueError("RCA handoff does not match source, outcome or file digests")


def self_test(tmp: Path) -> None:
    root = tmp / "rca-handoff"
    (root / "docs/runbooks/alert-rca").mkdir(parents=True)
    (root / "docs/runbooks/alert-rca/ledger.jsonl").write_text("{}\n")
    (root / "recurrences.json").write_text("[]")
    sha = "a" * 40
    manifest = build(root, sha, 0, 2)
    (root / "handoff.json").write_text(json.dumps(manifest))
    verify(root, sha, 0, 2)
    for bad in (("b" * 40, 0, 2), (sha, 1, 2), (sha, 0, 0)):
        try:
            verify(root, *bad)
        except ValueError:
            pass
        else:
            raise AssertionError("wrong source or outcome accepted")
    (root / "docs/runbooks/alert-rca/ledger.jsonl").write_text("changed\n")
    try:
        verify(root, sha, 0, 2)
    except ValueError:
        pass
    else:
        raise AssertionError("changed evidence accepted")
    (root / "extra.txt").write_text("unexpected")
    try:
        verify(root, sha, 0, 2)
    except ValueError:
        pass
    else:
        raise AssertionError("unexpected handoff path accepted")
    print("RCA handoff: source, outcome, digest and path rejection passed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path)
    parser.add_argument("--source-sha")
    parser.add_argument("--observation-count", type=int)
    parser.add_argument("--ledger-exit", type=int)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        import tempfile

        with tempfile.TemporaryDirectory() as directory:
            self_test(Path(directory))
        return
    if args.root is None or args.source_sha is None or args.observation_count is None or args.ledger_exit is None:
        parser.error("root, source SHA, observation count and ledger exit are required")
    if args.write:
        (args.root / "handoff.json").write_text(
            json.dumps(build(args.root, args.source_sha, args.observation_count, args.ledger_exit), sort_keys=True),
        )
    else:
        verify(args.root, args.source_sha, args.observation_count, args.ledger_exit)


if __name__ == "__main__":
    main()
