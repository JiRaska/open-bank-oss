#!/usr/bin/env python3
"""Integrity of `ci/artifacts.lock`, the trust anchor of the CI artifact resolver (ADR-0366).

Gate `ci-artifact-lock-integrity` (enforced). The resolver accepts bytes from any tier only when
their sha256 equals the lock's, so a malformed lock is a supply-chain hole rather than a style
nit: a missing digest has nothing to compare, a duplicated name makes "which entry did CI use"
unanswerable, and a `latest` tag pins nothing. Checked here:

  * JSON parses, with duplicate keys REJECTED (json.loads keeps the last one silently);
  * `schema == 1`, `artifacts` a non-empty list;
  * each entry: name (unique, lower-case slug), kind, version (non-empty, not `latest`),
    platforms (non-empty map) each with an https:// or oci:// url and a 64-char lower-hex sha256;
  * no `latest` tag anywhere: not as version, not as `:latest` in a url or mirror ref.

Usage: check-artifact-lock.py [--lock PATH] | --self-test
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
LOCK = REPO / "ci" / "artifacts.lock"
KINDS = {"binary-archive", "binary", "helm-chart"}
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9._/-]*$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
PLATFORM_RE = re.compile(r"^(any|[a-z]+-[a-z0-9]+)$")
LATEST_RE = re.compile(r":latest\b|/latest/|^latest$", re.IGNORECASE)


def _no_dupes(pairs):
    seen: dict = {}
    for key, value in pairs:
        if key in seen:
            raise ValueError(f"duplicate key {key!r}")
        seen[key] = value
    return seen


def check_text(text: str) -> tuple[list[str], int]:
    """Return (problems, entry count)."""
    try:
        doc = json.loads(text, object_pairs_hook=_no_dupes)
    except ValueError as exc:
        return [f"lock is not valid JSON: {exc}"], 0
    if not isinstance(doc, dict):
        return ["lock must be a JSON object"], 0
    problems: list[str] = []
    if doc.get("schema") != 1:
        problems.append(f"schema must be 1, got {doc.get('schema')!r}")
    arts = doc.get("artifacts")
    if not isinstance(arts, list) or not arts:
        return problems + ["artifacts must be a non-empty list"], 0

    names: set[str] = set()
    for i, art in enumerate(arts):
        where = f"artifacts[{i}]"
        if not isinstance(art, dict):
            problems.append(f"{where}: must be an object")
            continue
        name = art.get("name")
        if not isinstance(name, str) or not NAME_RE.match(name):
            problems.append(f"{where}: name {name!r} must be a lower-case slug")
        elif name in names:
            problems.append(f"{where}: duplicate name {name!r}")
        else:
            names.add(name)
            where = name
        if art.get("kind") not in KINDS:
            problems.append(f"{where}: kind {art.get('kind')!r} not in {sorted(KINDS)}")
        version = art.get("version")
        if not isinstance(version, str) or not version.strip():
            problems.append(f"{where}: version must be a non-empty string")
        elif LATEST_RE.search(version):
            problems.append(f"{where}: version 'latest' pins nothing")
        mirror = art.get("mirror")
        if mirror is not None and (not isinstance(mirror, str) or ":" not in mirror
                                   or LATEST_RE.search(mirror)):
            problems.append(f"{where}: mirror {mirror!r} must be '<repository>:<tag>', never :latest")
        plats = art.get("platforms")
        if not isinstance(plats, dict) or not plats:
            problems.append(f"{where}: platforms must be a non-empty map")
            continue
        for plat, spec in plats.items():
            pw = f"{where} [{plat}]"
            if not PLATFORM_RE.match(plat):
                problems.append(f"{pw}: platform must be 'any' or '<os>-<arch>'")
            if not isinstance(spec, dict):
                problems.append(f"{pw}: must be an object")
                continue
            url = spec.get("url")
            if not isinstance(url, str) or not url.startswith(("https://", "oci://")):
                problems.append(f"{pw}: url must be https:// or oci://, got {url!r}")
            elif LATEST_RE.search(url):
                problems.append(f"{pw}: url uses a latest tag: {url}")
            sha = spec.get("sha256")
            if not isinstance(sha, str) or not SHA_RE.match(sha):
                problems.append(f"{pw}: sha256 must be 64 lower-case hex chars, got {sha!r}")
    return problems, len(arts)


def _entry(**over):
    base = {"name": "tool", "kind": "binary-archive", "version": "1.0.0",
            "platforms": {"linux-amd64": {"url": "https://example.invalid/tool-1.0.0.tgz",
                                          "sha256": "a" * 64}}}
    base.update(over)
    return base


def self_test() -> int:
    good = json.dumps({"schema": 1, "artifacts": [_entry(), _entry(name="other", mirror="ci/other:1.0.0")]})
    must_fail = {
        "duplicate name": json.dumps({"schema": 1, "artifacts": [_entry(), _entry()]}),
        "duplicate JSON key": '{"schema": 1, "schema": 1, "artifacts": [' + json.dumps(_entry()) + "]}",
        "short sha256": json.dumps({"schema": 1, "artifacts": [_entry(platforms={"linux-amd64": {
            "url": "https://example.invalid/x", "sha256": "abc"}})]}),
        "upper-case sha256": json.dumps({"schema": 1, "artifacts": [_entry(platforms={"linux-amd64": {
            "url": "https://example.invalid/x", "sha256": "A" * 64}})]}),
        "missing sha256": json.dumps({"schema": 1, "artifacts": [_entry(platforms={"linux-amd64": {
            "url": "https://example.invalid/x"}})]}),
        "version latest": json.dumps({"schema": 1, "artifacts": [_entry(version="latest")]}),
        "url :latest": json.dumps({"schema": 1, "artifacts": [_entry(platforms={"any": {
            "url": "oci://example.invalid/chart:latest", "sha256": "a" * 64}})]}),
        "mirror :latest": json.dumps({"schema": 1, "artifacts": [_entry(mirror="ci/tool:latest")]}),
        "http url": json.dumps({"schema": 1, "artifacts": [_entry(platforms={"any": {
            "url": "http://example.invalid/x", "sha256": "a" * 64}})]}),
        "no platforms": json.dumps({"schema": 1, "artifacts": [_entry(platforms={})]}),
        "wrong schema": json.dumps({"schema": 2, "artifacts": [_entry()]}),
        "empty artifacts": json.dumps({"schema": 1, "artifacts": []}),
        "not json": "{schema: 1",
    }
    problems, count = check_text(good)
    if problems or count != 2:
        print(f"FAIL self-test: a valid lock was rejected: {problems}", file=sys.stderr)
        return 1
    for label, text in must_fail.items():
        problems, _ = check_text(text)
        if not problems:
            print(f"FAIL self-test: must-FAIL case '{label}' passed", file=sys.stderr)
            return 1
    print(f"PASS self-test: valid lock accepted, {len(must_fail)} must-FAIL cases rejected")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    lock = pathlib.Path(argv[argv.index("--lock") + 1]) if "--lock" in argv else LOCK
    try:
        text = lock.read_text()
    except OSError as exc:
        print(f"FAIL: cannot read {lock}: {exc}", file=sys.stderr)
        print("SUBJECTS=0")
        return 1
    problems, count = check_text(text)
    for p in problems:
        print(f"FAIL {lock.name}: {p}", file=sys.stderr)
    if not problems:
        print(f"PASS {lock.name}: {count} artifact(s), names unique, every digest sha256-shaped, no latest")
    print(f"SUBJECTS={count}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
