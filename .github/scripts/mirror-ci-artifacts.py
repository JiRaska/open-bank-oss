#!/usr/bin/env python3
"""Mirror every ci/artifacts.lock entry into ECR Public (ADR-0366, step 2).

The lock (JSON, schema 1) and the resolver `.github/scripts/fetch-artifact.py` are the
single source of truth. This script reuses the resolver's mirror naming (`mirror_ref`)
and upstream download (`tier_upstream`, incl. the GitHub REST-API fallback and oci://
sources), so the mirror can never be written under a name the resolver does not read.

For each (entry, platform):
  1. ask the mirror (anonymous OCI read) whether the tag exists AND its content layer
     digest equals the lock's sha256 (an OCI layer digest is the sha256 of the bytes);
  2. if missing or stale: download upstream, verify sha256, push with `oras`.

Bytes whose sha256 differs from the lock are NEVER pushed. Any entry that cannot be
mirrored makes the run exit 1 after all entries were tried.

Usage:
  mirror-ci-artifacts.py plan|apply --lock ci/artifacts.lock --registry public.ecr.aws/<alias>
      [--extra NAME,VERSION,PLATFORM,URL,SHA256]   # e.g. oras itself, which bootstraps the job
  mirror-ci-artifacts.py bootstrap --registry ... --extra ... --out PATH
      # mirror first, then upstream; sha256 verified either way
  mirror-ci-artifacts.py --selftest

Stdlib only; run under `python3 -I`.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from typing import Callable

PREFIX = "ci-artifacts"
HERE = pathlib.Path(__file__).resolve().parent
RESOLVER = HERE / "fetch-artifact.py"
GENERIC_LAYER = "application/vnd.openbank.ci-artifact.v1"
HELM_LAYER = "application/vnd.cncf.helm.chart.content.v1.tar+gzip"
ARTIFACT_TYPE = "application/vnd.openbank.ci-artifact.v1+type"


class LockError(Exception):
    pass


def load_resolver(path: pathlib.Path = RESOLVER):
    if not path.is_file():
        raise LockError(f"{path.name} not found; the mirror reuses the resolver's naming and download")
    spec = importlib.util.spec_from_file_location("fetch_artifact", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)  # type: ignore[union-attr]
    return mod


@dataclass(frozen=True)
class Item:
    entry: dict
    platform: str
    plat: dict
    repo: str
    tag: str

    @property
    def sha256(self) -> str:
        return self.plat["sha256"]

    @property
    def label(self) -> str:
        return f"{self.entry['name']}@{self.entry['version']}/{self.platform}"

    def ref(self, registry: str) -> str:
        return f"{registry.rstrip('/')}/{PREFIX}/{self.repo}:{self.tag}"


def parse_extra(spec: str) -> dict:
    parts = spec.split(",")
    if len(parts) != 5:
        raise LockError(f"--extra wants NAME,VERSION,PLATFORM,URL,SHA256, got {spec!r}")
    name, version, platform, url, sha = parts
    return {"name": name, "kind": "binary-archive", "version": version,
            "platforms": {platform: {"url": url, "sha256": sha}}}


def expand(lock: dict, mirror_ref: Callable[[dict, str], tuple[str, str]]) -> list[Item]:
    """Validate the lock minimally and expand it into one item per (entry, platform).
    Full schema validation is check-artifact-lock.py's job; this guards what a push needs."""
    if not isinstance(lock.get("artifacts"), list):
        raise LockError("lock has no 'artifacts' list")
    items, errors, seen = [], [], set()
    for i, e in enumerate(lock["artifacts"]):
        name = e.get("name") if isinstance(e, dict) else None
        plats = e.get("platforms") if isinstance(e, dict) else None
        if not name or not e.get("version") or not isinstance(plats, dict) or not plats:
            errors.append(f"entry {i} ({name}): needs name, version and platforms")
            continue
        for platform, plat in plats.items():
            sha = str((plat or {}).get("sha256", ""))
            url = str((plat or {}).get("url", ""))
            if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
                errors.append(f"{name}/{platform}: sha256 is not 64 lowercase hex")
                continue
            if not url.startswith(("https://", "oci://")):
                errors.append(f"{name}/{platform}: url must be https:// or oci://")
                continue
            repo, tag = mirror_ref(e, platform)
            if (repo, tag) in seen:
                errors.append(f"{name}/{platform}: duplicate mirror ref {repo}:{tag}")
                continue
            seen.add((repo, tag))
            items.append(Item(e, platform, plat, repo, tag))
    if errors:
        raise LockError("; ".join(errors))
    return items


# --- side-effecting operations, injectable for the self-test -----------------

def mirror_digest(ref: str) -> str | None:
    """Content-layer digest at ref, None if the tag does not exist. Raises if unanswerable."""
    p = subprocess.run(["oras", "manifest", "fetch", ref], capture_output=True, text=True)
    if p.returncode != 0:
        if any(s in p.stderr.lower() for s in ("not found", "404", "manifest_unknown", "name_unknown")):
            return None
        raise RuntimeError(f"cannot query mirror for {ref}: {p.stderr.strip()}")
    layers = json.loads(p.stdout).get("layers") or []
    chosen = [x for x in layers if x.get("mediaType") == HELM_LAYER] or layers
    return chosen[0].get("digest") if len(chosen) == 1 else "invalid:layer-count"


def push(ref: str, path: pathlib.Path, item: Item) -> None:
    media = HELM_LAYER if item.entry.get("kind") == "helm-chart" else GENERIC_LAYER
    subprocess.run([
        "oras", "push", ref,
        "--artifact-type", ARTIFACT_TYPE,
        "--annotation", f"org.opencontainers.image.source={item.plat['url']}",
        "--annotation", f"org.opencontainers.image.version={item.entry['version']}",
        "--annotation", f"tech.openbank.ci-artifact.platform={item.platform}",
        f"{path.name}:{media}",
    ], check=True, cwd=path.parent)


# --- core --------------------------------------------------------------------

def plan(items: list[Item], registry: str, probe: Callable[[str], str | None]):
    present, todo, errors = [], [], []
    for it in items:
        try:
            d = probe(it.ref(registry))
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{it.label}: {exc}")
            continue
        if d == f"sha256:{it.sha256}":
            present.append(it)
        else:
            todo.append((it, "missing" if d is None else f"stale (mirror has {d})"))
    return present, todo, errors


def mirror_one(it: Item, registry: str, workdir: pathlib.Path,
               download: Callable[[Item], bytes], pusher: Callable[[str, pathlib.Path, Item], None]) -> None:
    data = download(it)
    got = hashlib.sha256(data).hexdigest()
    if got != it.sha256:
        raise LockError(f"{it.label}: sha256 mismatch: lock {it.sha256}, upstream served {got}; NOT pushed")
    d = workdir / it.repo.replace("/", "_") / it.tag
    d.mkdir(parents=True, exist_ok=True)
    name = it.plat["url"].rstrip("/").rsplit("/", 1)[-1] or "artifact"
    if it.plat["url"].startswith("oci://"):
        name = f"{name}-{it.entry['version']}.tgz"
    path = d / name
    path.write_bytes(data)
    pusher(it.ref(registry), path, it)


def repo_hint(it: Item) -> str:
    return (f" (if repository {PREFIX}/{it.repo} does not exist yet, run `tofu apply` in "
            "openbank-infra/aws/envs/sandbox-platform; repositories are derived from the lock)")


def run(mode: str, lock_path: pathlib.Path, registry: str, extras: list[str]) -> int:
    if not lock_path.exists():
        print(f"::notice::{lock_path} not present yet (ADR-0366 step 1 not merged); nothing to mirror.")
        return 0
    try:
        resolver = load_resolver()
        lock = resolver.load_lock(lock_path)
        lock.setdefault("artifacts", [])
        lock["artifacts"] = list(lock["artifacts"]) + [parse_extra(x) for x in extras]
        items = expand(lock, resolver.mirror_ref)
    except (LockError, ValueError) as exc:
        print(f"::error::invalid lock {lock_path}: {exc}")
        return 1
    present, todo, errors = plan(items, registry, mirror_digest)
    for it in present:
        print(f"ok       {it.ref(registry)}")
    for it, why in todo:
        print(f"{'plan  ' if mode == 'plan' else 'mirror'}   {it.ref(registry)}  ({why})")
    if mode == "apply":
        with tempfile.TemporaryDirectory() as tmp:
            for it, _ in todo:
                try:
                    mirror_one(it, registry, pathlib.Path(tmp),
                               lambda i: resolver.tier_upstream(i.entry, i.plat), push)
                    print(f"pushed   {it.ref(registry)}")
                except Exception as exc:  # noqa: BLE001
                    hint = repo_hint(it) if isinstance(exc, subprocess.CalledProcessError) else ""
                    errors.append(f"{it.label}: {exc}{hint}")
    for err in errors:
        print(f"::error::{err}")
    print(f"summary: {len(items)} items, {len(present)} present, {len(todo)} to mirror, {len(errors)} errors")
    return 1 if errors else 0


def bootstrap(registry: str, extra: str, out: pathlib.Path) -> int:
    """Fetch one --extra item mirror-first, then upstream; sha256 verified either way."""
    resolver = load_resolver()
    entry = parse_extra(extra)
    (platform, plat), = entry["platforms"].items()
    repo, tag = resolver.mirror_ref(entry, platform)
    host, _, ns = registry.partition("/")
    tiers = [
        ("mirror", lambda: resolver.oci_pull(f"https://{host}", f"{ns}/{PREFIX}/{repo}".lstrip("/"), tag)),
        ("upstream", lambda: resolver.tier_upstream(entry, plat)),
    ]
    for tier, fetch in tiers:
        try:
            data = fetch()
        except Exception as exc:  # noqa: BLE001
            print(f"bootstrap: {tier} unavailable ({exc})", file=sys.stderr)
            continue
        if hashlib.sha256(data).hexdigest() != plat["sha256"]:
            print(f"::warning::bootstrap: {tier} served bytes that do not match the pin; rejected")
            continue
        out.write_bytes(data)
        print(f"bootstrap: {entry['name']} {entry['version']} verified from {tier}")
        return 0
    print(f"::error::bootstrap: no verified copy of {entry['name']} {entry['version']}")
    return 1


# --- self-test ---------------------------------------------------------------

def selftest() -> int:
    failures: list[str] = []

    def check(cond: bool, msg: str) -> None:
        if not cond:
            failures.append(msg)

    def mirror_ref(e: dict, p: str) -> tuple[str, str]:  # same shape as the resolver's
        return e["name"].lower(), e["version"] if p == "any" else f"{e['version']}-{p}"

    payload = b"hello mirror\n"
    good = hashlib.sha256(payload).hexdigest()
    lock = json.loads(json.dumps({"schema": 1, "artifacts": [
        {"name": "tool", "kind": "binary-archive", "version": "1.0.0",
         "platforms": {"linux-amd64": {"url": "https://example.invalid/tool.tgz", "sha256": good}}},
        {"name": "helm-chart/x", "kind": "helm-chart", "version": "2.0.0",
         "platforms": {"any": {"url": "oci://example.invalid/charts/x", "sha256": "0" * 64}}},
    ]}))
    items = expand(lock, mirror_ref)
    check([(i.repo, i.tag) for i in items] == [("tool", "1.0.0-linux-amd64"), ("helm-chart/x", "2.0.0")], "expand")
    check(items[1].ref("public.ecr.aws/a") == "public.ecr.aws/a/ci-artifacts/helm-chart/x:2.0.0", "ref")

    mirror = {items[0].ref("r"): f"sha256:{good}", items[1].ref("r"): "sha256:" + "f" * 64}
    pres, todo, errs = plan(items, "r", lambda ref: mirror.get(ref))
    check([i.repo for i in pres] == ["tool"] and len(todo) == 1 and todo[0][1].startswith("stale")
          and not errs, "plan present/stale")
    _, todo, _ = plan(items, "r", lambda ref: None)
    check(len(todo) == 2 and all(w == "missing" for _, w in todo), "plan missing")

    def boom(ref):
        raise RuntimeError("registry down")
    _, todo, errs = plan(items, "r", boom)
    check(len(errs) == 2 and not todo, "probe error must be an error, not 'missing'")

    pushed: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        mirror_one(items[0], "r", pathlib.Path(tmp), lambda i: payload, lambda r, p, i: pushed.append(r))
        check(pushed == [items[0].ref("r")], "matching sha is pushed")
        pushed.clear()
        try:  # MUST-FAIL: tampered bytes are never pushed
            mirror_one(items[0], "r", pathlib.Path(tmp), lambda i: payload + b"x",
                       lambda r, p, i: pushed.append(r))
            failures.append("sha mismatch did not raise")
        except LockError as exc:
            check("NOT pushed" in str(exc), "mismatch message")
        check(pushed == [], "sha mismatch was PUSHED")

    bad_cases = [
        ({"artifacts": [{"name": "a", "version": "1", "platforms": {"any": {"url": "https://x/a", "sha256": "abc"}}}]}, "short sha"),
        ({"artifacts": [{"name": "a", "version": "1", "platforms": {"any": {"url": "http://x/a", "sha256": good}}}]}, "http url"),
        ({"artifacts": [{"name": "a", "version": "1", "platforms": {}}]}, "no platforms"),
        ({"artifacts": [{"name": "a", "version": "1", "platforms": {"any": {"url": "https://x/a", "sha256": good}}},
                        {"name": "a", "version": "1", "platforms": {"any": {"url": "https://x/b", "sha256": good}}}]}, "duplicate"),
        ({}, "no artifacts"),
    ]
    for bad, why in bad_cases:
        try:
            expand(bad, mirror_ref)
            failures.append(f"lock accepted: {why}")
        except LockError:
            pass
    check(parse_extra(f"oras,1.3.6,linux-amd64,https://x/o.tgz,{good}")["platforms"]["linux-amd64"]["sha256"] == good,
          "parse_extra")
    check(run("plan", pathlib.Path("/nonexistent/ci/artifacts.lock"), "r", []) == 0, "absent lock tolerated")

    # The real resolver, when present, must agree with the naming the tofu derivation assumes.
    if RESOLVER.is_file():
        r = load_resolver()
        check(r.mirror_ref({"name": "helm-chart/alloy", "version": "1.13.0"}, "any") == ("helm-chart/alloy", "1.13.0"),
              "resolver mirror_ref (chart)")
        check(r.mirror_ref({"name": "promtool", "version": "3.14.0"}, "linux-amd64") == ("promtool", "3.14.0-linux-amd64"),
              "resolver mirror_ref (binary)")

    for f in failures:
        print(f"SELFTEST FAIL: {f}")
    print("selftest:", "FAIL" if failures else "PASS")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", nargs="?", choices=["plan", "apply", "bootstrap"])
    ap.add_argument("--lock", default="ci/artifacts.lock", type=pathlib.Path)
    ap.add_argument("--registry", default="")
    ap.add_argument("--extra", action="append", default=[])
    ap.add_argument("--out", type=pathlib.Path)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if not a.mode or not a.registry:
        ap.error("mode and --registry are required")
    if a.mode == "bootstrap":
        if len(a.extra) != 1 or not a.out:
            ap.error("bootstrap needs exactly one --extra and --out")
        return bootstrap(a.registry, a.extra[0], a.out)
    return run(a.mode, a.lock, a.registry, a.extra)


if __name__ == "__main__":
    sys.exit(main())
