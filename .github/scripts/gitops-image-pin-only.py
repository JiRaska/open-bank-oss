#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Decide whether a GitOps manifest change is ONLY a container image pin (Services CI selector).

  gitops-image-pin-only.py <base> <path>   exit 0 = inert (image pins only), 1 = relevant
  gitops-image-pin-only.py --self-test     end-to-end cases in a temporary git repository
  gitops-image-pin-only.py --self-test --sabotage   same, with the comparator forced to "equal"
                                                    (must FAIL: proves the suite can see it)

Structural, not textual: both versions are parsed with a duplicate-key-rejecting loader, the
value of every `image` key that sits directly in a containers / initContainers /
ephemeralContainers list item is replaced by a placeholder, and the trees must then be equal.
Everything else is RELEVANT and so keeps the conservative full-fleet fallback: parse errors,
a document-count change, added/deleted/renamed files, mode changes, symlinks, binaries,
OPA bundles, and any ConfigMap change (ConfigMaps are never masked). Any exception is relevant too.
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

CONTAINER_LISTS = {"containers", "initContainers", "ephemeralContainers"}
PLACEHOLDER = "<image-pin>"
SABOTAGE = False


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


def _mask(node, parent_key=None):
    if isinstance(node, dict):
        return {k: _mask(v, k) for k, v in node.items()}
    if isinstance(node, list):
        out = []
        for item in node:
            if parent_key in CONTAINER_LISTS and isinstance(item, dict) and isinstance(item.get("image"), str):
                item = {**item, "image": PLACEHOLDER}
            out.append(_mask(item))
        return out
    return node


def trees_equal(old_docs: list, new_docs: list) -> bool:
    if SABOTAGE:
        return True
    if len(old_docs) != len(new_docs):
        return False
    # A ConfigMap is never masked: its data is compared verbatim, so any change there is relevant.
    def mask_doc(d):
        return d if isinstance(d, dict) and d.get("kind") == "ConfigMap" else _mask(d)
    return [mask_doc(d) for d in old_docs] == [mask_doc(d) for d in new_docs]


def _git(*args: str, cwd: str | None = None) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout


def image_pin_only(base: str, path: str, cwd: str | None = None) -> bool:
    if not path.startswith("openbank-infra/gitops/") or not path.endswith((".yaml", ".yml")):
        return False
    if "-opa-bundle" in os.path.basename(path):
        return False
    # Exactly one plain modification of a regular file: M, mode 100644 on both sides.
    raw = _git("diff", "--raw", "--no-renames", "-z", base, "HEAD", "--", path, cwd=cwd).split("\0")
    raw = [r for r in raw if r]
    if len(raw) != 2:
        return False
    meta = raw[0].lstrip(":").split()
    if len(meta) < 5 or meta[0] != "100644" or meta[1] != "100644" or meta[4] != "M":
        return False
    summary = _git("diff", "--summary", "-M", base, "HEAD", "--", path, cwd=cwd).strip()
    if summary:  # rename / mode change / create / delete
        return False
    numstat = _git("diff", "--numstat", base, "HEAD", "--", path, cwd=cwd).split()
    if not numstat or numstat[0] == "-":  # binary
        return False
    old_docs = _load_all(_git("show", f"{base}:{path}", cwd=cwd))
    new_docs = _load_all(_git("show", f"HEAD:{path}", cwd=cwd))
    return trees_equal(old_docs, new_docs)


DEPLOY = """apiVersion: apps/v1
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
          image: r.invalid/openbank-x:sandbox-aaa@sha256:00
          env:
            - name: A
              value: "1"
"""
P = "openbank-infra/gitops/components/x/x.yaml"


def self_test() -> int:
    cases = [  # (name, mutate(repo) -> None, want_inert)
        ("tag bump", lambda r: _write(r, P, DEPLOY.replace("sandbox-aaa", "sandbox-bbb")), True),
        ("digest bump", lambda r: _write(r, P, DEPLOY.replace("sha256:00", "sha256:11")), True),
        ("init image bump", lambda r: _write(r, P, DEPLOY.replace("init:1", "init:2")), True),
        ("indent change", lambda r: _write(r, P, DEPLOY.replace("          env:", "        env:")), False),
        ("pure remove", lambda r: _write(r, P, DEPLOY.replace('            - name: A\n              value: "1"\n', "")), False),
        ("pure add", lambda r: _write(r, P, DEPLOY + "---\nkind: Service\n"), False),
        ("document separator", lambda r: _write(r, P, DEPLOY.replace("spec:\n  template", "---\nspec:\n  template")), False),
        # `- image:` (a container list item) becomes `image:` (a key of `containers`): structure differs
        ("dash-image swap", lambda r: _write(r, P, DEPLOY.replace("      containers:\n        - name: x\n          image: r.invalid/openbank-x:sandbox-aaa@sha256:00\n          env:", "      containers:\n        name: x\n        image: r.invalid/openbank-x:sandbox-bbb@sha256:00\n        env:").replace("            - name: A\n              value:", "          - name: A\n            value:")), False),
        ("image+env change", lambda r: _write(r, P, DEPLOY.replace("sandbox-aaa", "sandbox-bbb").replace('"1"', '"2"')), False),
        ("duplicate key", lambda r: _write(r, P, DEPLOY.replace("  name: x\n", "  name: x\n  name: y\n", 1)), False),
        ("non-container image key", None, False),
        ("configmap data", None, False),
        ("opa bundle", None, False),
        ("rename", None, False),
        ("mode change", None, False),
    ]
    ok = True
    for name, mutate, want in cases:
        with tempfile.TemporaryDirectory() as d:
            env_repo(d)
            path = P
            if name == "configmap data":
                cm = DEPLOY + "---\nkind: ConfigMap\ndata:\n  image: a\n"
                _write(d, path, cm); _commit(d)
                _write(d, path, cm.replace("image: a", "image: b"))
            elif name == "opa bundle":
                path = "openbank-infra/gitops/components/x/x-opa-bundle.yaml"
                _write(d, path, DEPLOY); _commit(d)
                _write(d, path, DEPLOY.replace("sandbox-aaa", "sandbox-bbb"))
            else:
                _write(d, path, DEPLOY if name != "non-container image key" else DEPLOY + "---\nkind: Foo\nimage: a\n")
                _commit(d)
                if name == "rename":
                    newp = path.replace("x.yaml", "y.yaml")
                    _git("mv", path, newp, cwd=d)
                    _write(d, newp, DEPLOY.replace("sandbox-aaa", "sandbox-bbb"))
                    _commit(d)
                    got = image_pin_only("HEAD~1", path, cwd=d) or image_pin_only("HEAD~1", newp, cwd=d)
                    ok &= _report(name, got, want)
                    continue
                if name == "mode change":
                    os.chmod(Path(d, path), 0o755)
                    _write(d, path, DEPLOY.replace("sandbox-aaa", "sandbox-bbb"), keep_mode=True)
                elif name == "non-container image key":
                    _write(d, path, DEPLOY + "---\nkind: Foo\nimage: b\n")
                else:
                    mutate(d)
            _commit(d)
            try:
                got = image_pin_only("HEAD~1", path, cwd=d)
            except Exception:
                got = False
            ok &= _report(name, got, want)
    print(f"SUBJECTS={len(cases)}", file=sys.stderr)
    print("self-test:", "PASS" if ok else "FAIL", f"({len(cases)} cases{', SABOTAGED comparator' if SABOTAGE else ''})")
    return 0 if ok else 1


def _report(name: str, got: bool, want: bool) -> bool:
    if got != want:
        print(f"FAIL: {name}: inert={got}, want {want}")
        return False
    return True


def env_repo(d: str) -> None:
    _git("init", "-q", "-b", "main", cwd=d)
    _git("config", "user.email", "t@example.invalid", cwd=d)
    _git("config", "user.name", "t", cwd=d)
    _git("config", "commit.gpgsign", "false", cwd=d)
    _git("config", "core.fileMode", "true", cwd=d)


def _write(d: str, rel: str, text: str, keep_mode: bool = False) -> None:
    p = Path(d, rel)
    p.parent.mkdir(parents=True, exist_ok=True)
    mode = p.stat().st_mode if keep_mode and p.exists() else None
    p.write_text(text)
    if mode is not None:
        os.chmod(p, mode)


def _commit(d: str) -> None:
    _git("add", "-A", cwd=d)
    _git("-c", "core.hooksPath=/dev/null", "commit", "-q", "--allow-empty", "-m", "c", cwd=d)


def main(argv: list[str]) -> int:
    global SABOTAGE
    if "--self-test" in argv:
        SABOTAGE = "--sabotage" in argv
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
    for k in ("GIT_INDEX_FILE", "GIT_DIR", "GIT_WORK_TREE"):
        if "--self-test" in sys.argv:
            os.environ.pop(k, None)
    sys.exit(main(sys.argv[1:]))
