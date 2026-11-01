#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Decide whether a GitOps manifest change is ONLY a container image pin (Services CI selector).

  gitops-image-pin-only.py <base> <path>   exit 0 = inert (image tag/digest only), 1 = relevant
  gitops-image-pin-only.py --self-test     end-to-end cases in temporary git repositories
  gitops-image-pin-only.py --self-test --sabotage=<comparator|filemeta>
      same suite with one guard disabled; it MUST fail, which proves the suite can see the guard:
        comparator  trees always compare equal
        filemeta    the `git diff --raw` / `--summary` file-shape guards are skipped

Structural, not textual. Both versions are parsed with a duplicate-key-rejecting loader. Only
in workload kinds (Deployment, StatefulSet, DaemonSet, ReplicaSet, Job, CronJob, Rollout, Pod),
and only at their pod-template path, the `image` of each containers / initContainers /
ephemeralContainers item is reduced to its repository (tag and digest dropped). The trees must
then be equal, so a different repository is relevant. Anything else is unmasked (a Kyverno
policy's `containers:` pattern, ConfigMap data). Relevant too: parse errors, a document-count
change, added/deleted/renamed files, mode changes, symlinks, binaries, `*-opa-bundle*` files,
and any exception.
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

CONTAINER_LISTS = ("containers", "initContainers", "ephemeralContainers")
POD_SPEC_PATHS = {
    "Pod": ("spec",),
    "Deployment": ("spec", "template", "spec"),
    "StatefulSet": ("spec", "template", "spec"),
    "DaemonSet": ("spec", "template", "spec"),
    "ReplicaSet": ("spec", "template", "spec"),
    "Job": ("spec", "template", "spec"),
    "Rollout": ("spec", "template", "spec"),
    "CronJob": ("spec", "jobTemplate", "spec", "template", "spec"),
}
SABOTAGE = ""


def _load_all(text: str) -> list:
    import yaml  # absent PyYAML -> exception -> relevant

    class StrictLoader(yaml.SafeLoader):
        pass

    def construct_mapping(loader, node, deep=False):
        loader.flatten_mapping(node)
        seen = set()
        for key_node, _ in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in seen:
                raise ValueError(f"duplicate key {key!r}")
            seen.add(key)
        return yaml.SafeLoader.construct_mapping(loader, node, deep=deep)

    StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping)
    return list(yaml.load_all(text, Loader=StrictLoader))


def image_repository(image: str) -> str:
    """registry/name with the tag and digest removed (a port colon is kept)."""
    repo = image.split("@", 1)[0]
    slash = repo.rfind("/")
    colon = repo.rfind(":")
    return repo[:colon] if colon > slash else repo


def _mask_doc(doc):
    import copy

    if not isinstance(doc, dict) or doc.get("kind") not in POD_SPEC_PATHS:
        return doc
    doc = copy.deepcopy(doc)
    spec = doc
    for key in POD_SPEC_PATHS[doc["kind"]]:
        spec = spec.get(key) if isinstance(spec, dict) else None
    if not isinstance(spec, dict):
        return doc
    for field in CONTAINER_LISTS:
        items = spec.get(field)
        if isinstance(items, list):
            for item in items:
                if isinstance(item, dict) and isinstance(item.get("image"), str):
                    item["image"] = image_repository(item["image"])
    return doc


def trees_equal(old_docs: list, new_docs: list) -> bool:
    if SABOTAGE == "comparator":
        return True
    if len(old_docs) != len(new_docs):
        return False
    return [_mask_doc(d) for d in old_docs] == [_mask_doc(d) for d in new_docs]


def _git(*args: str, cwd: str | None = None) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout


def image_pin_only(base: str, path: str, cwd: str | None = None) -> bool:
    if not path.startswith("openbank-infra/gitops/") or not path.endswith((".yaml", ".yml")):
        return False
    if "-opa-bundle" in os.path.basename(path):
        return False
    if SABOTAGE != "filemeta":
        # Exactly one plain modification of a regular file: M, mode 100644 on both sides.
        raw = [r for r in _git("diff", "--raw", "--no-renames", "-z", base, "HEAD", "--", path, cwd=cwd).split("\0") if r]
        if len(raw) != 2:
            return False
        meta = raw[0].lstrip(":").split()
        if len(meta) < 5 or meta[0] != "100644" or meta[1] != "100644" or meta[4] != "M":
            return False
        if _git("diff", "--summary", "-M", base, "HEAD", "--", path, cwd=cwd).strip():
            return False  # rename / mode change / create / delete
    numstat = _git("diff", "--numstat", base, "HEAD", "--", path, cwd=cwd).split()
    if not numstat or numstat[0] == "-":  # binary
        return False
    old_docs = _load_all(_git("show", f"{base}:{path}", cwd=cwd))
    new_docs = _load_all(_git("show", f"HEAD:{path}", cwd=cwd))
    return trees_equal(old_docs, new_docs)


IMG = "r.invalid:5000/openbank-x:sandbox-aaa@sha256:00"
DEPLOY = f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: x
spec:
  template:
    spec:
      initContainers:
        - name: init
          image: r.invalid/init:1
      containers:
        - name: x
          image: {IMG}
          env:
            - name: A
              value: "1"
"""
CRON = f"""apiVersion: batch/v1
kind: CronJob
metadata:
  name: c
spec:
  schedule: "0 1 * * *"
  jobTemplate:
    spec:
      template:
        spec:
          containers:
            - name: c
              image: {IMG}
"""
ROLLOUT = DEPLOY.replace("apiVersion: apps/v1\nkind: Deployment", "apiVersion: argoproj.io/v1alpha1\nkind: Rollout")
MULTI = DEPLOY + "---\napiVersion: v1\nkind: Service\nmetadata:\n  name: x\n---\n" + CRON
KYVERNO = """apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: pin
spec:
  rules:
    - name: no-latest
      validate:
        pattern:
          spec:
            containers:
              - image: "!*:latest"
"""
CM = DEPLOY + "---\napiVersion: v1\nkind: ConfigMap\ndata:\n  image: a\n"
P = "openbank-infra/gitops/components/x/x.yaml"
OPA = "openbank-infra/gitops/components/x/x-opa-bundle.yaml"


def bump(text: str) -> str:
    return text.replace("sandbox-aaa", "sandbox-bbb")


# (name, path, before, after, special, want_inert)
CASES = [
    ("tag bump", P, DEPLOY, bump(DEPLOY), None, True),
    ("digest bump", P, DEPLOY, DEPLOY.replace("sha256:00", "sha256:11"), None, True),
    ("init image bump", P, DEPLOY, DEPLOY.replace("init:1", "init:2"), None, True),
    ("cronjob bump", P, CRON, bump(CRON), None, True),
    ("rollout bump", P, ROLLOUT, bump(ROLLOUT), None, True),
    ("multi-doc workload bump", P, MULTI, bump(MULTI), None, True),
    ("indent change", P, DEPLOY, DEPLOY.replace("          env:", "        env:"), None, False),
    ("pure remove", P, DEPLOY, DEPLOY.replace('            - name: A\n              value: "1"\n', ""), None, False),
    ("pure add", P, DEPLOY, DEPLOY + "---\nkind: Service\n", None, False),
    ("document separator", P, DEPLOY, DEPLOY.replace("spec:\n  template", "---\nspec:\n  template"), None, False),
    ("dash-image swap", P, DEPLOY, DEPLOY.replace(
        f"      containers:\n        - name: x\n          image: {IMG}\n          env:\n            - name: A\n              value:",
        f"      containers:\n        name: x\n        image: {IMG}\n        env:\n          - name: A\n            value:"), None, False),
    ("image+env change", P, DEPLOY, bump(DEPLOY).replace('"1"', '"2"'), None, False),
    ("repo swap", P, DEPLOY, DEPLOY.replace("openbank-x:", "openbank-y:"), None, False),
    ("registry swap", P, DEPLOY, DEPLOY.replace("r.invalid:5000/", "r2.invalid:5000/"), None, False),
    ("duplicate key", P, DEPLOY, DEPLOY.replace("  name: x\n", "  name: x\n  name: y\n", 1), None, False),
    ("non-workload image key", P, DEPLOY + "---\nkind: Foo\nimage: a\n", DEPLOY + "---\nkind: Foo\nimage: b\n", None, False),
    ("kyverno image pattern", P, KYVERNO, KYVERNO.replace("!*:latest", "!*:dev"), None, False),
    ("configmap data", P, CM, CM.replace("image: a", "image: b"), None, False),
    ("opa bundle", OPA, DEPLOY, bump(DEPLOY), None, False),
    ("rename", P, DEPLOY, bump(DEPLOY), "rename", False),
    ("mode change", P, DEPLOY, bump(DEPLOY), "mode", False),
]


def run_case(name, path, before, after, special) -> bool:
    with tempfile.TemporaryDirectory() as d:
        env_repo(d)
        _write(d, path, before)
        _commit(d)
        paths = [path]
        if special == "rename":
            newp = path.replace("x.yaml", "y.yaml")
            _git("mv", path, newp, cwd=d)
            path = newp
            paths.append(newp)
        _write(d, path, after)
        if special == "mode":
            os.chmod(Path(d, path), 0o755)
        _commit(d)
        got = False
        for p in paths:
            try:
                got = got or image_pin_only("HEAD~1", p, cwd=d)
            except Exception:
                pass
        return got


def self_test() -> int:
    ok = True
    for name, path, before, after, special, want in CASES:
        got = run_case(name, path, before, after, special)
        if got != want:
            print(f"FAIL: {name}: inert={got}, want {want}")
            ok = False
    print(f"SUBJECTS={len(CASES)}", file=sys.stderr)
    print("self-test:", "PASS" if ok else "FAIL", f"({len(CASES)} cases{', SABOTAGE=' + SABOTAGE if SABOTAGE else ''})")
    return 0 if ok else 1


def env_repo(d: str) -> None:
    for args in (("init", "-q", "-b", "main"), ("config", "user.email", "t@example.invalid"),
                 ("config", "user.name", "t"), ("config", "commit.gpgsign", "false"),
                 ("config", "core.fileMode", "true")):
        _git(*args, cwd=d)


def _write(d: str, rel: str, text: str) -> None:
    p = Path(d, rel)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)


def _commit(d: str) -> None:
    _git("add", "-A", cwd=d)
    _git("-c", "core.hooksPath=/dev/null", "commit", "-q", "--allow-empty", "-m", "c", cwd=d)


def main(argv: list[str]) -> int:
    global SABOTAGE
    if "--self-test" in argv:
        for a in argv:
            if a.startswith("--sabotage="):
                SABOTAGE = a.split("=", 1)[1]
                if SABOTAGE not in ("comparator", "filemeta"):
                    print(f"unknown sabotage {SABOTAGE!r}", file=sys.stderr)
                    return 2
        for k in ("GIT_INDEX_FILE", "GIT_DIR", "GIT_WORK_TREE"):
            os.environ.pop(k, None)
        return self_test()
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        return 0 if image_pin_only(argv[0], argv[1]) else 1
    except Exception as exc:  # fail closed
        print(f"gitops-image-pin-only: {argv[1]}: {exc} -> relevant", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
