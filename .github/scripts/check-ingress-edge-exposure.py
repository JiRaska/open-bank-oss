#!/usr/bin/env python3
"""Every Ingress in gitops must declare its edge exposure (ADR-0324, mixed edge).

The edge NLBs are public. Which HOSTS are public is decided per Ingress by the
annotation `openbank.io/edge-exposure: public | internal`; the Kyverno policy
edge-internal-host-allowlist writes the source allow-list onto `internal` ones from
a non-public Secret. An Ingress with no annotation would be silently public, so a
new host must choose.

Checked:
  1. every `kind: Ingress` under openbank-infra/gitops declares the annotation with
     a valid value;
  2. every Helm `valuesObject` in gitops/apps with an enabled `ingress:` block sets it
     in `ingress.annotations` (charts render the Ingress, the repo holds only values);
  3. no Ingress hand-writes `nginx.ingress.kubernetes.io/whitelist-source-range` —
     the policy owns it, and a literal CIDR in this public repo is a leak.

Usage: check-ingress-edge-exposure.py [ROOT] | --self-test
"""
import os
import sys
import tempfile

import yaml

ANN = "openbank.io/edge-exposure"
VALID = {"public", "internal"}
OWNED = "nginx.ingress.kubernetes.io/whitelist-source-range"


def _docs(path):
    try:
        with open(path) as fh:
            return [d for d in yaml.safe_load_all(fh) if isinstance(d, dict)]
    except (yaml.YAMLError, OSError):
        return []


def _ingress_blocks(node):
    """Yield enabled `ingress:` mappings anywhere inside Helm values."""
    if isinstance(node, dict):
        ing = node.get("ingress")
        if isinstance(ing, dict) and ing.get("enabled") is True:
            yield ing
        for v in node.values():
            yield from _ingress_blocks(v)
    elif isinstance(node, list):
        for v in node:
            yield from _ingress_blocks(v)


def check(root):
    findings, subjects = [], 0
    base = os.path.join(root, "openbank-infra", "gitops")
    for dirpath, _, files in os.walk(base):
        for name in sorted(files):
            if not name.endswith((".yaml", ".yml")):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            for doc in _docs(path):
                if doc.get("kind") == "Ingress":
                    subjects += 1
                    ann = (doc.get("metadata") or {}).get("annotations") or {}
                    where = f"{rel}: Ingress {(doc.get('metadata') or {}).get('name')}"
                    if ann.get(ANN) not in VALID:
                        findings.append(f"{where} lacks {ANN}: public|internal")
                    if OWNED in ann:
                        findings.append(f"{where} hand-sets {OWNED}; the Kyverno policy owns it")
                elif doc.get("kind") == "Application":
                    vals = (((doc.get("spec") or {}).get("source") or {}).get("helm") or {}).get("valuesObject")
                    for ing in _ingress_blocks(vals):
                        subjects += 1
                        ann = ing.get("annotations") or {}
                        where = f"{rel}: Helm ingress of Application {(doc.get('metadata') or {}).get('name')}"
                        if ann.get(ANN) not in VALID:
                            findings.append(f"{where} lacks {ANN}: public|internal in ingress.annotations")
                        if OWNED in ann:
                            findings.append(f"{where} hand-sets {OWNED}; the Kyverno policy owns it")
    return findings, subjects


def _write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)


def self_test():
    ing = "apiVersion: networking.k8s.io/v1\nkind: Ingress\nmetadata:\n  name: x\n{ann}spec: {{}}\n"
    app = ("apiVersion: argoproj.io/v1alpha1\nkind: Application\nmetadata:\n  name: a\nspec:\n  source:\n"
           "    helm:\n      valuesObject:\n        web:\n          ingress:\n            enabled: true\n{ann}")
    cases = [
        ("declared public", ing.format(ann=f"  annotations:\n    {ANN}: public\n"), 0),
        ("declared internal", ing.format(ann=f"  annotations:\n    {ANN}: internal\n"), 0),
        ("missing", ing.format(ann=""), 1),
        ("invalid value", ing.format(ann=f"  annotations:\n    {ANN}: yes\n"), 1),
        ("hand-set allow-list", ing.format(ann=f"  annotations:\n    {ANN}: internal\n    {OWNED}: 10.0.0.0/8\n"), 1),
        ("label instead of annotation", ing.format(ann=f"  labels:\n    {ANN}: public\n"), 1),
        ("helm ingress declared", app.format(ann=f"            annotations:\n              {ANN}: public\n"), 0),
        ("helm ingress missing", app.format(ann="            annotations: {}\n"), 1),
        ("helm ingress disabled is not a subject", app.format(ann="").replace("enabled: true", "enabled: false"), 0),
    ]
    bad = 0
    for label, text, want in cases:
        with tempfile.TemporaryDirectory() as root:
            _write(root, "openbank-infra/gitops/components/c/i.yaml", text)
            got = len(check(root)[0])
        ok = got == want
        bad += not ok
        print(f"  {'ok  ' if ok else 'FAIL'} {label}: {got} finding(s), want {want}")
    print(f"self-test {'ok' if not bad else 'FAILED'}: ingress edge-exposure gate ({len(cases)} cases)")
    return 1 if bad else 0


def main():
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    findings, subjects = check(root)
    print(f"SUBJECTS={subjects}  # Ingresses + enabled Helm ingress blocks under openbank-infra/gitops")
    for f in findings:
        print(f"::error::{f}")
    if findings:
        print(f"FAIL: {len(findings)} Ingress(es) without a declared edge exposure (ADR-0324).")
        return 1
    print("OK: every gitops Ingress declares openbank.io/edge-exposure.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
