#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Refuse an OPA bundle that client-side apply cannot install.

THE FAILURE THIS EXISTS TO PREVENT

Kubernetes caps `metadata.annotations` at 262144 bytes for all annotations combined.
Client-side apply writes the ENTIRE applied object into
`kubectl.kubernetes.io/last-applied-configuration`, so a ConfigMap larger than roughly
that ceiling can never be applied that way: the API server rejects it with

    ConfigMap "<name>" is invalid: metadata.annotations: Too long

and Argo CD retries the sync forever. Nothing goes red in a way anyone reads. The
Application sits `OutOfSync`/`Progressing`, which is indistinguishable from a rollout
still in progress, and the POLICY IN THE CLUSTER SILENTLY FREEZES at the last version
that happened to fit while git moves on. Measured 2026-08-02: five Applications —
ledger, psd2, balances, consent, lending, four of them money-path — were serving OPA
policy that did not match `main`, and had been for as long as their bundle was oversized.

Server-side apply tracks ownership in `metadata.managedFields` and writes no such
annotation, so it has no ceiling of this kind. The fix is therefore not "shrink the
bundle" but "apply it server-side", and this check enforces exactly that pairing:

    a bundle over the ceiling is fine  IF AND ONLY IF  its Application uses
    ServerSideApply=true.

WHY THE SIZE ALONE IS NOT THE RULE. The bundles all carry the same shared policy sources,
so they sit within a few kilobytes of each other. A pure size limit would either fire on
the whole fleet or be set so high it never fires. What actually matters is whether the
apply mechanism can carry the object, and that is a property of the pair.

(Until #3357 they also embedded all 168 KB of `rules.yaml` verbatim, which is what put
them against the ceiling in the first place. They now embed the ~21 KB derived subset
`openbank-libs/governance/rules-opa-data.yaml`. That buys headroom, not an exemption —
the pairing rule below is what holds, and it is deliberately unchanged.)

DELIBERATELY NOT CHECKED: whether the live cluster matches. This runs on a PR, where
there is no cluster to ask, and a gate that quietly degrades to "could not check" is
worse than one with a stated scope.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import yaml

# metadata.annotations ceiling, in bytes, for all annotations combined.
# k8s.io/apimachinery: TotalAnnotationSizeLimitB = 256 * 1024
ANNOTATION_LIMIT = 262144

# last-applied-configuration is the object's JSON, but the apply also carries the other
# annotations (tracking-id, checksum) and the key names themselves. Reserve for those so
# the check fires slightly BEFORE the API server does rather than slightly after — an
# off-by-a-few-hundred-bytes gate that lets the breaking change through is no gate.
ANNOTATION_OVERHEAD = 2048

REPO = pathlib.Path(__file__).resolve().parents[2]


def applied_size(doc: dict) -> int:
    """Bytes client-side apply would write into last-applied-configuration.

    kubectl serialises the object it was given, minus the annotation it is about to add.
    Compact separators match kubectl's encoder.
    """
    obj = {
        "apiVersion": doc.get("apiVersion"),
        "kind": doc.get("kind"),
        "metadata": doc.get("metadata", {}),
        "data": doc.get("data", {}),
    }
    return len(json.dumps(obj, separators=(",", ":")))


def load_applications(apps_dir: pathlib.Path) -> dict[str, dict]:
    """Map component-directory name -> Application spec that deploys it."""
    by_path: dict[str, dict] = {}
    for f in sorted(apps_dir.glob("*.yaml")):
        try:
            docs = [d for d in yaml.safe_load_all(f.read_text()) if d]
        except yaml.YAMLError:
            continue
        for d in docs:
            if not isinstance(d, dict) or d.get("kind") != "Application":
                continue
            path = ((d.get("spec") or {}).get("source") or {}).get("path") or ""
            if not path:
                continue
            by_path[pathlib.PurePosixPath(path).name] = {
                "file": f.name,
                "name": (d.get("metadata") or {}).get("name"),
                "options": (((d.get("spec") or {}).get("syncPolicy") or {}).get("syncOptions") or []),
            }
    return by_path


def check(root: pathlib.Path) -> list[str]:
    components = root / "openbank-infra/gitops/components"
    apps = load_applications(root / "openbank-infra/gitops/apps")
    problems: list[str] = []

    for bundle in sorted(components.glob("*/*opa-bundle*.yaml")):
        try:
            docs = [d for d in yaml.safe_load_all(bundle.read_text()) if d]
        except yaml.YAMLError as exc:
            problems.append(f"{bundle.relative_to(root)}: unparseable ({exc})")
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "ConfigMap":
                continue
            size = applied_size(doc)
            if size + ANNOTATION_OVERHEAD <= ANNOTATION_LIMIT:
                continue

            component = bundle.parent.name
            app = apps.get(component)
            if app is None:
                problems.append(
                    f"{bundle.relative_to(root)}: {size} bytes exceeds the client-side apply "
                    f"ceiling and no Application under gitops/apps deploys "
                    f"components/{component}, so nothing can be verified to install it."
                )
                continue
            if "ServerSideApply=true" not in app["options"]:
                over = size + ANNOTATION_OVERHEAD - ANNOTATION_LIMIT
                problems.append(
                    f"{bundle.relative_to(root)}: {size} bytes, {over} over what client-side "
                    f"apply can carry, but Application '{app['name']}' "
                    f"(gitops/apps/{app['file']}) does not set ServerSideApply=true. "
                    f"The API server will reject this ConfigMap with "
                    f"'metadata.annotations: Too long' and Argo will retry forever while the "
                    f"cluster keeps serving the previous policy. Add "
                    f"'- ServerSideApply=true' to spec.syncPolicy.syncOptions."
                )
    return problems


def self_test() -> int:
    """Feed the checker the two inputs it must separate. A gate that has only ever seen
    the correct tree is unfalsified — this proves it fires AND that it does not misfire."""
    import tempfile

    failures = 0
    big = "x" * (ANNOTATION_LIMIT + 10_000)

    for label, options, expect_problem in (
        ("oversized bundle, no ServerSideApply", ["CreateNamespace=false"], True),
        ("oversized bundle, ServerSideApply set", ["CreateNamespace=false", "ServerSideApply=true"], False),
    ):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            comp = root / "openbank-infra/gitops/components/demo"
            appdir = root / "openbank-infra/gitops/apps"
            comp.mkdir(parents=True)
            appdir.mkdir(parents=True)
            (comp / "demo-opa-bundle.yaml").write_text(
                yaml.safe_dump(
                    {
                        "apiVersion": "v1",
                        "kind": "ConfigMap",
                        "metadata": {"name": "demo-opa-bundle", "namespace": "demo"},
                        "data": {"rest.rego": big},
                    }
                )
            )
            (appdir / "demo.yaml").write_text(
                yaml.safe_dump(
                    {
                        "apiVersion": "argoproj.io/v1alpha1",
                        "kind": "Application",
                        "metadata": {"name": "demo"},
                        "spec": {
                            "source": {"path": "openbank-infra/gitops/components/demo"},
                            "syncPolicy": {"syncOptions": options},
                        },
                    }
                )
            )
            got = bool(check(root))
            ok = got == expect_problem
            failures += 0 if ok else 1
            print(f"  [{'ok' if ok else 'FAIL'}] {label}: flagged={got} expected={expect_problem}")

    # A small bundle must never be flagged, whatever the sync options.
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        comp = root / "openbank-infra/gitops/components/demo"
        appdir = root / "openbank-infra/gitops/apps"
        comp.mkdir(parents=True)
        appdir.mkdir(parents=True)
        (comp / "demo-opa-bundle.yaml").write_text(
            yaml.safe_dump(
                {
                    "apiVersion": "v1",
                    "kind": "ConfigMap",
                    "metadata": {"name": "demo-opa-bundle"},
                    "data": {"rest.rego": "package x"},
                }
            )
        )
        (appdir / "demo.yaml").write_text(
            yaml.safe_dump(
                {
                    "apiVersion": "argoproj.io/v1alpha1",
                    "kind": "Application",
                    "metadata": {"name": "demo"},
                    "spec": {
                        "source": {"path": "openbank-infra/gitops/components/demo"},
                        "syncPolicy": {"syncOptions": []},
                    },
                }
            )
        )
        got = bool(check(root))
        failures += 0 if not got else 1
        print(f"  [{'ok' if not got else 'FAIL'}] small bundle, no options: flagged={got} expected=False")

    print("self-test:", "PASS" if failures == 0 else f"{failures} FAILED")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    problems = check(REPO)
    if problems:
        print("OPA bundles that cannot be installed by client-side apply:\n")
        for p in problems:
            print(f"::error::{p}")
        return 1
    print("OPA bundle apply-size check: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
