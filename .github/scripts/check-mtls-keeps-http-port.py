#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""A service that REQUIRES client certificates must keep its plain-HTTP port.

In Quarkus, `quarkus.http.insecure-requests` defaults to `disabled` whenever
`quarkus.http.ssl.client-auth` is `required`, and `disabled` removes the HTTP listener
altogether. #10441 baked `client-auth: required` into seven services' `%prod` profile and
the first image built from it (ledger) listened on 8443 only: its `tcp-socket :http`
readiness probe got `connection refused` and the rollout stalled. Every plaintext
in-cluster caller would have failed the same way.

Rule: in every profile where client-auth resolves to `required`, insecure-requests must
resolve to `enabled` (explicitly). A profile inherits top-level values it does not override.

Usage: check-mtls-keeps-http-port.py [--enforce] [--self-test] [ROOT]
"""
from __future__ import annotations

import glob
import os
import sys

import yaml


def _get(node, dotted):
    """Resolve a dotted key through nested maps, accepting dotted keys at any level."""
    if not isinstance(node, dict):
        return None
    if dotted in node:
        return node[dotted]
    parts = dotted.split(".")
    for i in range(len(parts) - 1, 0, -1):
        head = ".".join(parts[:i])
        if head in node:
            found = _get(node[head], ".".join(parts[i:]))
            if found is not None:
                return found
    return None


CLIENT_AUTH = "quarkus.http.ssl.client-auth"
INSECURE = "quarkus.http.insecure-requests"


def findings_for(doc) -> list[str]:
    if not isinstance(doc, dict):
        return []
    profiles = {None: doc}
    for k, v in doc.items():
        if isinstance(k, str) and k.startswith("%") and isinstance(v, dict):
            for name in k.split(","):
                profiles[name.strip()] = v
    base_ca, base_ins = _get(doc, CLIENT_AUTH), _get(doc, INSECURE)
    out = []
    for name, node in profiles.items():
        ca = _get(node, CLIENT_AUTH) if name else base_ca
        ins = _get(node, INSECURE) if name else base_ins
        if name and ca is None:
            ca = base_ca
        if name and ins is None:
            ins = base_ins
        if str(ca).strip().lower() == "required" and str(ins).strip().lower() != "enabled":
            out.append(
                f"profile {name or '(default)'}: client-auth=required but insecure-requests="
                f"{ins!r} — Quarkus then drops the plain-HTTP port; set insecure-requests: enabled"
            )
    return out


def scan(root: str) -> tuple[int, list[str]]:
    files = sorted(glob.glob(os.path.join(root, "openbank-*/src/main/resources/application.yaml")))
    bad = []
    for f in files:
        with open(f, encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)
        bad += [f"{os.path.relpath(f, root)}: {m}" for m in findings_for(doc)]
    return len(files), bad


def self_test() -> int:
    bad = yaml.safe_load('"%prod":\n  quarkus:\n    http:\n      ssl:\n        client-auth: required\n')
    good = yaml.safe_load(
        '"%prod":\n  quarkus:\n    http:\n      insecure-requests: enabled\n'
        "      ssl:\n        client-auth: required\n"
    )
    dotted = yaml.safe_load('quarkus.http.ssl.client-auth: required\n"%dev":\n  quarkus.http.insecure-requests: enabled\n')
    disabled = yaml.safe_load('"%prod":\n  quarkus:\n    http:\n      insecure-requests: disabled\n      ssl:\n        client-auth: required\n')
    none = yaml.safe_load("quarkus:\n  http:\n    port: 8080\n")
    checks = [
        ("missing insecure-requests flagged", len(findings_for(bad)) == 1),
        ("explicit enabled passes", findings_for(good) == []),
        ("top-level required inherited, default profile flagged", len(findings_for(dotted)) >= 1),
        ("explicit disabled flagged", len(findings_for(disabled)) == 1),
        ("no client-auth passes", findings_for(none) == []),
    ]
    ok = True
    for label, passed in checks:
        print(f"  {'PASS' if passed else 'FAIL'}: {label}")
        ok &= passed
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    enforce = "--enforce" in argv
    args = [a for a in argv if not a.startswith("--")]
    root = args[0] if args else "."
    n, bad = scan(root)
    print(f"SUBJECTS={n}")
    print(f"mtls-keeps-http-port: {n} application.yaml subjects, {len(bad)} findings")
    for b in bad:
        print(f"  {b}")
    if n == 0:
        print("no subjects found — refusing to report clean")
        return 1
    return 1 if (bad and enforce) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
