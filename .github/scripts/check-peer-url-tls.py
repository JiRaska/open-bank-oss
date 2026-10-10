#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A service-to-service URL must use the provider's TLS listener when the provider has one.

Every in-cluster REST call carries a bearer token. A Deployment env value of the form
`http://<svc>.<ns>.svc:<port>` sends that token in cleartext, and the sandbox runs no mesh
that would encrypt it underneath. Whether a plaintext call is avoidable is decidable from
the manifests alone: the provider's Service either exposes a TLS port (named `https`, or
port 8443) or it does not. Where it does, the caller has no excuse — it must use it.

Rule: for every env `value:` under openbank-infra/gitops that names `http://<svc>.<ns>.svc`,
if the Service `<svc>` in namespace `<ns>` exposes a TLS port, that is a finding. Callers of
a provider WITHOUT a TLS listener are not findings here (the provider's cutover is the fix,
and is tracked separately); a ratchet baseline holds today's findings, a new one fails, and
a baseline entry that no longer occurs fails too so the list can only shrink.

Usage: check-peer-url-tls.py [--enforce] [--self-test] [--write-baseline] [ROOT]
"""
from __future__ import annotations

import glob
import os
import re
import sys
import tempfile

import yaml

URL_RE = re.compile(r"\bhttp://([a-z0-9-]+)\.([a-z0-9-]+)\.svc(?:\.cluster\.local)?(?::(\d+))?")
BASELINE_REL = ".github/scripts/peer-url-tls-baseline.txt"
WORKLOAD_KINDS = {"Deployment", "StatefulSet", "DaemonSet", "Rollout", "Job", "CronJob"}


def _docs(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return [d for d in yaml.safe_load_all(fh) if isinstance(d, dict)]
    except (yaml.YAMLError, UnicodeDecodeError):
        return []


def _containers(doc):
    spec = doc.get("spec") or {}
    if doc.get("kind") == "CronJob":
        spec = ((spec.get("jobTemplate") or {}).get("spec")) or {}
    pod = ((spec.get("template") or {}).get("spec")) or {}
    return (pod.get("containers") or []) + (pod.get("initContainers") or [])


def _tls_services(files):
    tls = set()
    for f in files:
        for d in _docs(f):
            if d.get("kind") != "Service":
                continue
            md = d.get("metadata") or {}
            for p in (d.get("spec") or {}).get("ports") or []:
                if p.get("name") == "https" or p.get("port") == 8443:
                    tls.add((md.get("name"), md.get("namespace")))
    return tls


def findings(root):
    base = os.path.join(root, "openbank-infra", "gitops")
    files = sorted(glob.glob(os.path.join(base, "**", "*.yaml"), recursive=True))
    tls = _tls_services(files)
    out = set()
    findings.subjects = 0
    for f in files:
        rel = os.path.relpath(f, root)
        for d in _docs(f):
            if d.get("kind") not in WORKLOAD_KINDS:
                continue
            wl = (d.get("metadata") or {}).get("name")
            findings.subjects += 1
            for c in _containers(d):
                for e in c.get("env") or []:
                    v = e.get("value")
                    if not isinstance(v, str):
                        continue
                    for m in URL_RE.finditer(v):
                        if (m.group(1), m.group(2)) in tls:
                            out.add(f"{rel}|{wl}|{e.get('name')}|{m.group(1)}.{m.group(2)}")
    return out


def _baseline(root):
    p = os.path.join(root, BASELINE_REL)
    if not os.path.exists(p):
        return set()
    with open(p, encoding="utf-8") as fh:
        return {ln.strip() for ln in fh if ln.strip() and not ln.startswith("#")}


def run(root, enforce):
    found, base = findings(root), _baseline(root)
    print(f"SUBJECTS={findings.subjects}")
    new, stale = sorted(found - base), sorted(base - found)
    for n in new:
        print(f"::error::peer-url-tls: cleartext call to a TLS-capable provider: {n} "
              "— use https://<svc>.<ns>.svc:8443 and the private-CA trust store")
    for s in stale:
        print(f"::error::peer-url-tls: baseline entry no longer occurs, remove it: {s}")
    print(f"peer-url-tls: {len(found)} plaintext call(s) to TLS-capable providers, "
          f"{len(base)} baselined, {len(new)} new, {len(stale)} stale")
    return 1 if (enforce and (new or stale)) else 0


def _write(root, rel, text):
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(text)


SVC = """apiVersion: v1
kind: Service
metadata: {{name: {name}, namespace: {ns}}}
spec:
  ports:
{ports}
"""
DEP = """apiVersion: apps/v1
kind: Deployment
metadata: {{name: caller, namespace: c}}
spec:
  template:
    spec:
      containers:
        - name: app
          env:
            - name: PEER_URL
              value: {url}
"""


def self_test():
    ok = True

    def case(label, url, ports, expect):
        nonlocal ok
        with tempfile.TemporaryDirectory() as root:
            _write(root, "openbank-infra/gitops/components/p/svc.yaml",
                   SVC.format(name="p-service", ns="p", ports=ports))
            _write(root, "openbank-infra/gitops/components/c/dep.yaml", DEP.format(url=url))
            got = len(findings(root))
            rc = run(root, True)
            res = (got, rc)
        if res != expect:
            ok = False
        print(f"self-test {'PASS' if res == expect else 'FAIL'}: {label} -> {res}, want {expect}")

    tls_ports = "    - {name: http, port: 8171}\n    - {name: https, port: 8443}"
    plain_ports = "    - {name: http, port: 8171}"
    # known-positive: plaintext to a provider that HAS a TLS listener
    case("http to TLS-capable provider", "http://p-service.p.svc:8171", tls_ports, (1, 1))
    case("http with cluster.local suffix", "http://p-service.p.svc.cluster.local:8171",
         tls_ports, (1, 1))
    # known-negatives
    case("https to TLS-capable provider", "https://p-service.p.svc:8443", tls_ports, (0, 0))
    case("http to provider with no TLS listener", "http://p-service.p.svc:8171",
         plain_ports, (0, 0))
    # baseline: a baselined finding passes, a stale entry fails
    with tempfile.TemporaryDirectory() as root:
        _write(root, "openbank-infra/gitops/components/p/svc.yaml",
               SVC.format(name="p-service", ns="p", ports=tls_ports))
        _write(root, "openbank-infra/gitops/components/c/dep.yaml",
               DEP.format(url="http://p-service.p.svc:8171"))
        _write(root, BASELINE_REL, "\n".join(sorted(findings(root))) + "\n")
        r1 = run(root, True)
        _write(root, "openbank-infra/gitops/components/c/dep.yaml",
               DEP.format(url="https://p-service.p.svc:8443"))
        r2 = run(root, True)
    good = (r1, r2) == (0, 1)
    ok = ok and good
    print(f"self-test {'PASS' if good else 'FAIL'}: baselined passes, stale fails -> {(r1, r2)}")
    return 0 if ok else 1


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    root = args[0] if args else "."
    if "--self-test" in argv:
        return self_test()
    if "--write-baseline" in argv:
        _write(root, BASELINE_REL,
               "# Plaintext service URLs to providers that already expose a TLS listener.\n"
               "# Ratchet: only removals. Generated by check-peer-url-tls.py --write-baseline.\n"
               + "".join(f"{x}\n" for x in sorted(findings(root))))
        return 0
    return run(root, "--enforce" in argv)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
