#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The ESO cert-reader grant must cover exactly the secrets ExternalSecrets actually read.

WHY THIS EXISTS
---------------
Rolling campaign-service into the sandbox hit five independent boot failures, all on `main` with
every required check green (#2872). The first was:

    secrets "campaign-service" is forbidden

The component declared a `KafkaUser` and an `ExternalSecret` reading its cert, and the Role that
lets External Secrets Operator read those secrets — `eso-kafka-cert-reader` in namespace
`messaging` — grants by `resourceNames`, a HAND-KEPT list. Adding a service to the fleet means
editing that list, and nothing said so. The pod could not start; nothing in the repo disagreed with
itself in a way any gate could see, because no gate read the two artifacts together.

That is the recurring shape in this codebase: a gate whose SCOPE is a hand-kept list of the thing it
covers reads as *passing* when the list is short, never as *unchecked* — the same defect as the pact
drift scope (#2284) and the Kafka ACL baseline (#2945).

WHAT IT CHECKS — and why it derives rather than declares
--------------------------------------------------------
Both directions, against a set DERIVED from the ExternalSecrets themselves:

  required = every `remoteRef.key` (and `dataFrom[].extract.key`) used by an ExternalSecret whose
             `spec.secretStoreRef.name` is the kafka cert store
  granted  = the Role's `resourceNames`

  1. required - granted  ⇒ ERROR. This IS the #2851 failure: the workload cannot start.
  2. granted  - required ⇒ ERROR. A standing grant nobody uses is privilege with no purpose, and a
     stale entry is how a list stops describing reality.

Deriving from the ExternalSecrets rather than from `KafkaUser` objects is deliberate and is what
removes the need for an exception list. `openbank-cluster-cluster-ca-cert` is read by 30
ExternalSecrets and is not a KafkaUser — a KafkaUser-based rule would flag it forever and someone
would add an allowlist entry, which is the very construct this gate exists to retire. Measured on
`origin/main` at the time of writing: 38 required, 38 granted, zero drift in either direction, with
no declared exceptions at all. A rule that needs no exceptions on a real tree is usually the right
rule.

WHAT IT DOES NOT CHECK
----------------------
That the secret exists in the source store, that the ServiceAccount ESO runs as is the one this Role
is bound to, or that the RoleBinding exists. Those are one layer further out and would need cluster
state; this compares two artifacts in the tree.

Usage:  check-kafka-cert-reader-grant.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra/gitops"
ROLE_NAME = "eso-kafka-cert-reader"
STORE_NAME = "kafka-messaging-certs"


def documents(root: pathlib.Path):
    for path in sorted(root.rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
        except (yaml.YAMLError, OSError, UnicodeDecodeError):
            # A manifest this script cannot parse is not this script's finding — `yamllint` and
            # `Validate manifests` own that. Skipping keeps one broken file from masking the
            # comparison for every other one.
            continue
        for doc in docs:
            if isinstance(doc, dict):
                yield path, doc


def analyse(root: pathlib.Path) -> tuple[dict[str, str], set[str], bool]:
    """Return ({required key: the file that reads it}, granted keys, role_found)."""
    required: dict[str, str] = {}
    granted: set[str] = set()
    role_found = False

    for path, doc in documents(root):
        meta = doc.get("metadata") or {}
        kind = doc.get("kind")

        if kind == "ExternalSecret":
            spec = doc.get("spec") or {}
            if ((spec.get("secretStoreRef") or {}).get("name")) != STORE_NAME:
                continue
            rel = str(path.relative_to(REPO)) if path.is_relative_to(REPO) else str(path)
            for item in spec.get("data") or []:
                key = (item.get("remoteRef") or {}).get("key")
                if key:
                    required.setdefault(key, rel)
            for item in spec.get("dataFrom") or []:
                key = (item.get("extract") or {}).get("key")
                if key:
                    required.setdefault(key, rel)

        elif kind == "Role" and meta.get("name") == ROLE_NAME:
            role_found = True
            for rule in doc.get("rules") or []:
                if "secrets" in (rule.get("resources") or []) and rule.get("resourceNames"):
                    granted |= set(rule["resourceNames"])

    return required, granted, role_found


def findings(root: pathlib.Path = GITOPS) -> list[str]:
    required, granted, role_found = analyse(root)

    if not role_found:
        # Never silently pass on a missing Role: an absent grant list and an empty one look the
        # same from a set difference, and "0 findings" would be the most dangerous possible answer.
        return [f"the Role {ROLE_NAME} was not found under {root} — this gate cannot compare "
                f"anything, and its silence would mean nothing. Did it move or get renamed?"]
    if not required:
        return [f"no ExternalSecret referencing secretStoreRef {STORE_NAME} was found — either the "
                f"store was renamed or the scan is broken. Not reporting a clean run on that."]

    out = []
    for key in sorted(set(required) - granted):
        out.append(
            f"{key} is read by an ExternalSecret ({required[key]}) but is NOT in "
            f"{ROLE_NAME}'s resourceNames. External Secrets Operator will be refused with "
            f"'secrets \"{key}\" is forbidden' and the workload will not start (#2872/#2851)."
        )
    for key in sorted(granted - set(required)):
        out.append(
            f"{key} is granted by {ROLE_NAME} but no ExternalSecret reads it. Either the "
            f"ExternalSecret was removed and the grant was not, or the name drifted — a standing "
            f"grant nobody uses is privilege with no purpose."
        )
    return out


def selftest() -> int:
    """Build fixture trees the rules MUST flag and MUST NOT, and run the real analysis on them."""
    import tempfile

    def tree(dirpath: pathlib.Path, reads: list[str], grants: list[str], role: bool = True) -> None:
        dirpath.mkdir(parents=True, exist_ok=True)
        for i, key in enumerate(reads):
            (dirpath / f"es-{i}.yaml").write_text(yaml.safe_dump({
                "apiVersion": "external-secrets.io/v1", "kind": "ExternalSecret",
                "metadata": {"name": f"es-{i}", "namespace": "messaging"},
                "spec": {"secretStoreRef": {"name": STORE_NAME},
                         "data": [{"remoteRef": {"key": key}}]},
            }), encoding="utf-8")
        if role:
            (dirpath / "role.yaml").write_text(yaml.safe_dump({
                "apiVersion": "rbac.authorization.k8s.io/v1", "kind": "Role",
                "metadata": {"name": ROLE_NAME, "namespace": "messaging"},
                "rules": [{"apiGroups": [""], "resources": ["secrets"],
                           "verbs": ["get"], "resourceNames": grants}],
            }), encoding="utf-8")

    cases = [
        # (label, reads, grants, role present, expected finding count)
        ("a read with no grant — the #2851 failure", ["a", "b"], ["a"], True, 1),
        ("a grant nothing reads", ["a"], ["a", "b"], True, 1),
        ("both drifts at once", ["a", "c"], ["a", "b"], True, 2),
        ("exact agreement", ["a", "b"], ["a", "b"], True, 0),
        # The CA cert is not a KafkaUser. A KafkaUser-derived rule would flag it forever; deriving
        # from the ExternalSecrets means it needs no exception. Pinned so nobody "simplifies" the
        # derivation back to KafkaUser objects.
        ("a non-KafkaUser secret both read and granted",
         ["openbank-cluster-cluster-ca-cert"], ["openbank-cluster-cluster-ca-cert"], True, 0),
        # An absent Role must be loud. A set difference against an empty grant list would otherwise
        # report every read as missing, or — if the reads were also empty — report a clean run.
        ("the Role is missing entirely", ["a"], [], False, 1),
    ]

    for label, reads, grants, role, want in cases:
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            tree(root / "components" / "x", reads, grants, role)
            got = findings(root)
        if len(got) != want:
            print(f"selftest FAIL: {label} — expected {want} finding(s), got {len(got)}: {got}")
            return 1

    # An empty tree must NOT read as clean — that is the shape in which this gate would be green
    # about a renamed store or a broken scan.
    with tempfile.TemporaryDirectory() as d:
        if not findings(pathlib.Path(d)):
            print("selftest FAIL: an empty tree produced no finding — the gate would pass on nothing.")
            return 1

    print(f"selftest OK: {len(cases)} fixture(s) covering both drift directions, a non-KafkaUser "
          f"secret, a missing Role and an empty tree.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test")
    args = ap.parse_args()
    if args.self_test:
        return selftest()

    found = findings()
    for line in found:
        print(("::error::" if args.enforce else "::warning::") + line)
    required, granted, _ = analyse(GITOPS)
    print(f"check-kafka-cert-reader-grant: {len(required)} secret(s) read from {STORE_NAME}, "
          f"{len(granted)} granted by {ROLE_NAME} — "
          f"{'clean.' if not found else f'{len(found)} finding(s) above.'}")
    return 1 if found and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
