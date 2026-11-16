#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""An Argo CD Application's helm.valuesObject must not contain a null.

Kubernetes drops null fields when it stores an object, so an Application whose desired
`spec.source.helm.valuesObject` disables a chart default with `key: null` can never equal its
live copy: the parent app (here `root`) stays OutOfSync forever, self-heal re-applies every
cycle, and the standing OutOfSync hides any real drift in the same app. `ci-metrics-gateway`
and `rum-gateway` held root OutOfSync that way from 2026-11-12. Write such a block as the
`values: |` string instead — Helm reads the same content and a string keeps its nulls.

  check-argocd-valuesobject-null.py [ROOT]     scan ROOT/openbank-infra/gitops/apps
  check-argocd-valuesobject-null.py --self-test
"""
from __future__ import annotations

import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

APPS = pathlib.Path("openbank-infra/gitops/apps")


def null_paths(node, path=""):
    if node is None:
        yield path or "."
    elif isinstance(node, dict):
        for k, v in node.items():
            yield from null_paths(v, f"{path}.{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from null_paths(v, f"{path}[{i}]")


def scan(root: pathlib.Path) -> tuple[int, list[str]]:
    checked, findings = 0, []
    for f in sorted((root / APPS).glob("*.yaml")):
        for doc in gatelib.load_yaml_all(f):
            if not isinstance(doc, dict) or doc.get("kind") != "Application":
                continue
            spec = doc.get("spec") or {}
            sources = spec.get("sources") or ([spec["source"]] if spec.get("source") else [])
            for src in sources:
                helm = (src or {}).get("helm") or {}
                if "valuesObject" not in helm:
                    continue
                checked += 1
                for p in null_paths(helm["valuesObject"]):
                    findings.append(f"{f.relative_to(root)}: {doc['metadata']['name']}: "
                                    f"valuesObject{p} is null — write the block as `values: |`")
    return checked, findings


def self_test() -> int:
    bad = ("apiVersion: argoproj.io/v1alpha1\nkind: Application\nmetadata: {name: x}\n"
           "spec:\n  source:\n    helm:\n      valuesObject:\n        a: {b: null}\n")
    good = bad.replace("valuesObject:\n        a: {b: null}", "values: |\n        a: {b: null}")
    for text, want in ((bad, 1), (good, 0)):
        with tempfile.TemporaryDirectory() as d:
            (pathlib.Path(d) / APPS).mkdir(parents=True)
            (pathlib.Path(d) / APPS / "x.yaml").write_text(text)
            got = len(scan(pathlib.Path(d))[1])
        if got != want:
            print(f"SELF-TEST FAIL: expected {want} finding(s), got {got}")
            return 1
    print("SELF-TEST PASS")
    return 0


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(".")
    checked, findings = scan(root)
    for line in findings:
        print(f"FAIL {line}")
    gatelib.subjects(checked, "Helm sources with valuesObject")
    if findings:
        return 1
    print(f"PASS argocd valuesObject null gate: {checked} source(s), no nulls")
    return 0


if __name__ == "__main__":
    sys.exit(main())
