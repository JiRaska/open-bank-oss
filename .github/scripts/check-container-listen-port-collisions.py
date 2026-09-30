#!/usr/bin/env python3
"""Fail when two listeners in ONE container resolve to the same TCP port.

Why: a container that binds the same port twice dies at startup with
`bind: address already in use` and crashloops. Kubernetes cannot see it
(containerPort is informational), ArgoCD reports Synced, and the sibling
Deployments in the same file keep working, so review reads the file as fine.

Concretely: distribution (the `registry` image) v3 ships a config that starts a
debug/metrics server on :5001 (`http.debug.addr`). quay-cache set
`REGISTRY_HTTP_ADDR=:5001` and crashlooped; its siblings on :5000/:5002 did not.
The collision was with a listener the manifest never names -- an IMAGE DEFAULT.

Two rules, both decidable from the manifest alone:
  1. Every `*_ADDR` / `*_PORT` env value in one container that names a port
     (`:5001`, `0.0.0.0:5001`, `5001`) must be unique within that container.
  2. A distribution/registry container must set `REGISTRY_HTTP_DEBUG_ADDR`
     explicitly, so its implicit :5001 listener is part of rule 1 instead of an
     invisible default. (Setting it to a distinct port, or to "" to disable.)

Scope: every YAML document under openbank-infra/gitops that carries a pod spec
(`containers:` / `initContainers:` lists). Unparseable (templated) files are
skipped and counted, never silently treated as clean.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

ADDR_KEY_RE = re.compile(r"(_ADDR|_ADDRESS|_PORT|_LISTEN)$")
PORT_RE = re.compile(r"^(?:[\w.\-\[\]:]*:)?(\d{2,5})$")
REGISTRY_IMAGE_RE = re.compile(r"(^|/)(library/)?registry(:|@|$)|(^|/)distribution/distribution(:|@|$)")
REGISTRY_DEFAULT_DEBUG_PORT = 5001


def _port(value: object) -> int | None:
    if value is None:
        return None
    m = PORT_RE.match(str(value).strip())
    return int(m.group(1)) if m else None


def check_container(c: dict) -> list[str]:
    findings: list[str] = []
    env = {e.get("name"): e.get("value") for e in (c.get("env") or []) if isinstance(e, dict)}
    listeners: dict[int, list[str]] = {}
    for name, value in env.items():
        if not name or not ADDR_KEY_RE.search(name):
            continue
        p = _port(value)
        if p is not None:
            listeners.setdefault(p, []).append(name)
    image = str(c.get("image") or "")
    if REGISTRY_IMAGE_RE.search(image):
        if "REGISTRY_HTTP_DEBUG_ADDR" not in env:
            findings.append(
                "distribution/registry container does not set REGISTRY_HTTP_DEBUG_ADDR; the image "
                f"default starts a debug server on :{REGISTRY_DEFAULT_DEBUG_PORT} that no reviewer sees"
            )
            listeners.setdefault(REGISTRY_DEFAULT_DEBUG_PORT, []).append(
                "REGISTRY_HTTP_DEBUG_ADDR(image default)"
            )
    for port, names in sorted(listeners.items()):
        if len(names) > 1:
            findings.append(f"port {port} bound by more than one listener: {', '.join(names)}")
    return findings


def _pod_containers(node):
    if isinstance(node, dict):
        for key in ("containers", "initContainers"):
            lst = node.get(key)
            if isinstance(lst, list) and all(isinstance(x, dict) for x in lst):
                yield from lst
        for v in node.values():
            yield from _pod_containers(v)
    elif isinstance(node, list):
        for v in node:
            yield from _pod_containers(v)


def scan_text(text: str, label: str) -> tuple[int, list[str]]:
    count, out = 0, []
    for doc in yaml.safe_load_all(text):
        for c in _pod_containers(doc):
            count += 1
            for f in check_container(c):
                out.append(f"{label}: container '{c.get('name')}': {f}")
    return count, out


def scan(root: Path) -> int:
    base = root / "openbank-infra" / "gitops"
    subjects, findings, skipped = 0, [], 0
    for path in sorted(list(base.rglob("*.yaml")) + list(base.rglob("*.yml"))):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        if "containers:" not in text:
            continue
        try:
            n, f = scan_text(text, str(path.relative_to(root)))
        except yaml.YAMLError:
            skipped += 1
            continue
        subjects += n
        findings += f
    for f in findings:
        print(f"::error::{f}")
    print(f"SUBJECTS={subjects}")
    print(f"container-listen-port-collisions: {subjects} containers scanned, "
          f"{skipped} unparseable files skipped, {len(findings)} finding(s)")
    if subjects == 0:
        print("::error::scanned zero containers -- the probe measured nothing")
        return 1
    return 1 if findings else 0


OLD_QUAY = """
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
        - name: registry
          image: docker.io/library/registry:3.1.2
          env:
            - name: REGISTRY_HTTP_ADDR
              value: ":5001"
"""
FIXED_QUAY = OLD_QUAY + """            - name: REGISTRY_HTTP_DEBUG_ADDR
              value: ":5100"
"""
EXPLICIT_CLASH = OLD_QUAY + """            - name: REGISTRY_HTTP_DEBUG_ADDR
              value: "0.0.0.0:5001"
"""
GENERIC_CLASH = """
kind: Pod
spec:
  containers:
    - name: app
      image: example/app:1
      env:
        - {name: HTTP_ADDR, value: ":8080"}
        - {name: METRICS_PORT, value: "8080"}
"""
GENERIC_OK = """
kind: Pod
spec:
  containers:
    - name: app
      image: example/app:1
      env:
        - {name: HTTP_ADDR, value: ":8080"}
        - {name: METRICS_PORT, value: "9090"}
"""


def self_test() -> int:
    cases = [
        ("pre-fix quay-cache (implicit debug :5001)", OLD_QUAY, True),
        ("explicit debug addr on the serving port", EXPLICIT_CLASH, True),
        ("generic two env listeners on one port", GENERIC_CLASH, True),
        ("fixed quay-cache", FIXED_QUAY, False),
        ("generic distinct ports", GENERIC_OK, False),
    ]
    ok = True
    for name, text, must_flag in cases:
        _, f = scan_text(text, name)
        verdict = bool(f) == must_flag
        ok &= verdict
        print(f"{'PASS' if verdict else 'FAIL'}: {name} -> {len(f)} finding(s)")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    return self_test() if a.self_test else scan(Path(a.root))


if __name__ == "__main__":
    sys.exit(main())
