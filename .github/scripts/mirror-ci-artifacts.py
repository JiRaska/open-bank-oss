#!/usr/bin/env python3
"""Mirror every ci/artifacts.lock entry into ECR Public (ADR-0366, step 2).

For each lock entry:
  1. ask the mirror (anonymous `oras manifest fetch`) whether the tag exists AND
     its single layer digest equals the lock's sha256 (an OCI layer digest is
     the sha256 of the bytes, so this is a content check, not a name check);
  2. if missing or stale: download from the upstream URL, verify sha256, push.

A downloaded file whose digest does not match the lock is NEVER pushed. Any
entry that cannot be mirrored makes the script exit 1 after processing all.

Lock schema (YAML or JSON; YAML is a JSON superset):
  artifacts:
    - name: gitleaks            # [a-z0-9][a-z0-9._-]*; = ECR repo ci-artifacts/<name>
      version: 8.24.0
      platform: linux-amd64     # or "any" (charts, platform-independent archives)
      url: https://...          # https only
      sha256: <64 hex>
A top-level list instead of the `artifacts:` mapping is accepted too.

Usage:
  mirror-ci-artifacts.py plan  --lock ci/artifacts.lock --registry public.ecr.aws/<alias>
  mirror-ci-artifacts.py apply --lock ci/artifacts.lock --registry public.ecr.aws/<alias>
  mirror-ci-artifacts.py --selftest
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

PREFIX = "ci-artifacts"
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9._-]*$")
TAG_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
REQUIRED = ("name", "version", "platform", "url", "sha256")
LAYER_MEDIA_TYPE = "application/vnd.openbank.ci-artifact.v1"
ARTIFACT_TYPE = "application/vnd.openbank.ci-artifact.v1+type"


class LockError(Exception):
    pass


@dataclass(frozen=True)
class Entry:
    name: str
    version: str
    platform: str
    url: str
    sha256: str

    @property
    def tag(self) -> str:
        return self.version if self.platform in ("", "any") else f"{self.version}-{self.platform}"

    def ref(self, registry: str) -> str:
        return f"{registry.rstrip('/')}/{PREFIX}/{self.name}:{self.tag}"

    @property
    def filename(self) -> str:
        return self.url.rstrip("/").rsplit("/", 1)[-1] or self.name


def parse_lock(text: str) -> list[Entry]:
    try:
        import yaml  # type: ignore

        doc = yaml.safe_load(text)
    except ImportError:
        doc = json.loads(text)
    if isinstance(doc, dict):
        doc = doc.get("artifacts")
    if not isinstance(doc, list):
        raise LockError("lock must be a list of entries or a mapping with an 'artifacts' list")
    entries, seen, errors = [], set(), []
    for i, raw in enumerate(doc):
        if not isinstance(raw, dict):
            errors.append(f"entry {i}: not a mapping")
            continue
        missing = [k for k in REQUIRED if not str(raw.get(k, "")).strip()]
        if missing:
            errors.append(f"entry {i} ({raw.get('name')}): missing {', '.join(missing)}")
            continue
        e = Entry(*(str(raw[k]).strip() for k in REQUIRED))
        e = Entry(e.name, e.version, e.platform, e.url, e.sha256.lower())
        if not NAME_RE.match(e.name):
            errors.append(f"{e.name}: invalid name")
        if not SHA_RE.match(e.sha256):
            errors.append(f"{e.name}: sha256 is not 64 hex chars")
        if not e.url.startswith("https://"):
            errors.append(f"{e.name}: url must be https")
        if not TAG_RE.match(e.tag):
            errors.append(f"{e.name}: tag '{e.tag}' is not a valid OCI tag")
        key = (e.name, e.tag)
        if key in seen:
            errors.append(f"{e.name}: duplicate version/platform {e.tag}")
        seen.add(key)
        entries.append(e)
    if errors:
        raise LockError("; ".join(errors))
    return entries


# --- side-effecting operations, injectable for the self-test -----------------

def mirror_layer_digest(ref: str) -> str | None:
    """Digest of the single layer at ref, or None if the tag does not exist."""
    p = subprocess.run(["oras", "manifest", "fetch", ref], capture_output=True, text=True)
    if p.returncode != 0:
        if re.search(r"not ?found|404|MANIFEST_UNKNOWN|NAME_UNKNOWN", p.stderr, re.I):
            return None
        raise RuntimeError(f"cannot query mirror for {ref}: {p.stderr.strip()}")
    layers = json.loads(p.stdout).get("layers") or []
    return layers[0].get("digest") if len(layers) == 1 else "invalid:layer-count"


def download(url: str, dest: Path) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": "openbank-ci-artifact-mirror"})
    with urllib.request.urlopen(req, timeout=300) as r, dest.open("wb") as f:  # noqa: S310 (https enforced)
        while chunk := r.read(1 << 20):
            f.write(chunk)


def push(ref: str, path: Path, e: Entry) -> None:
    cmd = [
        "oras", "push", ref,
        "--artifact-type", ARTIFACT_TYPE,
        "--annotation", f"org.opencontainers.image.source={e.url}",
        "--annotation", f"org.opencontainers.image.version={e.version}",
        "--annotation", f"tech.openbank.ci-artifact.platform={e.platform}",
        f"{path.name}:{LAYER_MEDIA_TYPE}",
    ]
    subprocess.run(cmd, check=True, cwd=path.parent)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


# --- core --------------------------------------------------------------------

def plan(entries: list[Entry], registry: str, probe: Callable[[str], str | None]) -> tuple[list, list, list]:
    """Returns (present, todo[(entry, reason)], errors[str])."""
    present, todo, errors = [], [], []
    for e in entries:
        try:
            d = probe(e.ref(registry))
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{e.name}@{e.tag}: {exc}")
            continue
        if d == f"sha256:{e.sha256}":
            present.append(e)
        else:
            todo.append((e, "missing" if d is None else f"stale (mirror has {d})"))
    return present, todo, errors


def mirror_one(e: Entry, registry: str, workdir: Path,
               fetch: Callable[[str, Path], None] = download,
               pusher: Callable[[str, Path, Entry], None] = push) -> None:
    d = workdir / f"{e.name}-{e.tag}"
    d.mkdir(parents=True, exist_ok=True)
    path = d / e.filename
    fetch(e.url, path)
    got = sha256_file(path)
    if got != e.sha256:
        path.unlink(missing_ok=True)
        raise LockError(f"{e.name}@{e.tag}: sha256 mismatch: lock {e.sha256}, upstream served {got}; NOT pushed")
    pusher(e.ref(registry), path, e)


def run(mode: str, lock: Path, registry: str) -> int:
    if not lock.exists():
        print(f"::notice::{lock} not present yet (ADR-0366 step 1 not merged); nothing to mirror.")
        return 0
    try:
        entries = parse_lock(lock.read_text())
    except (LockError, ValueError) as exc:
        print(f"::error::invalid lock {lock}: {exc}")
        return 1
    present, todo, errors = plan(entries, registry, mirror_layer_digest)
    for e in present:
        print(f"ok       {e.ref(registry)}")
    for e, why in todo:
        print(f"{'plan' if mode == 'plan' else 'mirror'}   {e.ref(registry)}  ({why})")
    if mode == "apply":
        with tempfile.TemporaryDirectory() as tmp:
            for e, _ in todo:
                try:
                    mirror_one(e, registry, Path(tmp))
                    print(f"pushed   {e.ref(registry)}")
                except Exception as exc:  # noqa: BLE001
                    errors.append(str(exc) + (
                        f" (if the repository does not exist, add '{e.name}' to "
                        "ci_artifact_mirror_names in openbank-infra/aws/envs/sandbox-platform/ci-artifact-mirror.tf)"
                        if isinstance(exc, subprocess.CalledProcessError) else ""))
    for err in errors:
        print(f"::error::{err}")
    print(f"summary: {len(entries)} entries, {len(present)} present, {len(todo)} to mirror, {len(errors)} errors")
    return 1 if errors else 0


# --- self-test ---------------------------------------------------------------

def selftest() -> int:
    payload = b"hello mirror\n"
    good = hashlib.sha256(payload).hexdigest()
    lock = f"""artifacts:
  - {{name: tool, version: "1.0.0", platform: linux-amd64, url: "https://example.invalid/tool.tgz", sha256: {good}}}
  - {{name: chart, version: "2.0.0", platform: any, url: "https://example.invalid/chart.tgz", sha256: "{'0' * 64}"}}
"""
    failures = []

    def check(cond: bool, msg: str) -> None:
        if not cond:
            failures.append(msg)

    entries = parse_lock(lock)
    check([e.tag for e in entries] == ["1.0.0-linux-amd64", "2.0.0"], "tag derivation")
    check(entries[0].ref("public.ecr.aws/x") == "public.ecr.aws/x/ci-artifacts/tool:1.0.0-linux-amd64", "ref")

    # plan: present / missing / stale / probe error
    mirror = {entries[0].ref("r"): f"sha256:{good}", entries[1].ref("r"): "sha256:" + "f" * 64}
    pres, todo, errs = plan(entries, "r", lambda ref: mirror.get(ref))
    check([e.name for e in pres] == ["tool"] and [e.name for e, _ in todo] == ["chart"]
          and todo[0][1].startswith("stale") and not errs, "plan present/stale")
    _, todo, _ = plan(entries, "r", lambda ref: None)
    check(len(todo) == 2 and all(w == "missing" for _, w in todo), "plan missing")

    def boom(ref):
        raise RuntimeError("registry down")
    _, todo, errs = plan(entries, "r", boom)
    check(len(errs) == 2 and not todo, "probe error must be an error, not 'missing'")

    pushed: list[str] = []

    def fetch_payload(data: bytes):
        return lambda url, dest: dest.write_bytes(data)

    with tempfile.TemporaryDirectory() as tmp:
        mirror_one(entries[0], "r", Path(tmp), fetch_payload(payload), lambda r, p, e: pushed.append(r))
        check(pushed == [entries[0].ref("r")], "matching sha is pushed")

        # MUST-FAIL: tampered bytes are never pushed.
        pushed.clear()
        try:
            mirror_one(entries[0], "r", Path(tmp), fetch_payload(payload + b"x"), lambda r, p, e: pushed.append(r))
            failures.append("sha mismatch did not raise")
        except LockError as exc:
            check("NOT pushed" in str(exc), "mismatch message")
        check(pushed == [], "sha mismatch was PUSHED")

    # Lock validation must reject bad input.
    for bad, why in [
        ("artifacts: [{name: a, version: '1', platform: any, url: 'https://x/a', sha256: abc}]", "short sha"),
        ("artifacts: [{name: a, version: '1', platform: any, url: 'http://x/a', sha256: " + good + "}]", "http url"),
        ("artifacts: [{name: a, version: '1', platform: any, url: 'https://x/a'}]", "missing sha"),
        ("artifacts: [{name: a, version: '1', platform: any, url: 'https://x/a', sha256: " + good + "},"
         " {name: a, version: '1', platform: any, url: 'https://x/b', sha256: " + good + "}]", "duplicate"),
        ("{}", "no artifacts"),
    ]:
        try:
            parse_lock(bad)
            failures.append(f"lock accepted: {why}")
        except LockError:
            pass

    # Absent lock: notice + success.
    check(run("plan", Path("/nonexistent/ci/artifacts.lock"), "r") == 0, "absent lock tolerated")

    for f in failures:
        print(f"SELFTEST FAIL: {f}")
    print("selftest:", "FAIL" if failures else "PASS")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", nargs="?", choices=["plan", "apply"])
    ap.add_argument("--lock", default="ci/artifacts.lock", type=Path)
    ap.add_argument("--registry", default=os.environ.get("MIRROR_REGISTRY", ""))
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if not a.mode or not a.registry:
        ap.error("mode and --registry (or MIRROR_REGISTRY) are required")
    return run(a.mode, a.lock, a.registry)


if __name__ == "__main__":
    sys.exit(main())
