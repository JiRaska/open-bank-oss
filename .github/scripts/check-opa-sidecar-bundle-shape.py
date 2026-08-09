#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""An OPA sidecar's data files must mount at the roots its own .manifest declares.

WHY THIS EXISTS
---------------
campaign-service's rollout hit five independent boot failures, all on `main` with every required
check green (#2872). The fourth was:

    /bundle/rules-data.yaml: merge error

The sidecar runs `opa run --server --bundle /bundle`, i.e. DIRECTORY mode, where OPA derives each
data document's path in `data.*` from the file's location in the tree. The bundle files were
bit-identical to every healthy service's — only the mount paths differed. Mounted FLAT as
`/bundle/rules-data.yaml` instead of `/bundle/rules/data.yaml`, two documents claim the same root
and OPA refuses to load the bundle at all. The container dies; the pod never becomes ready.

Nothing could see it. The ConfigMap is correct, the args are correct, the bundle drift gate compares
bundle CONTENT, and the service build knows nothing about mounts. The contradiction lives strictly
between the Deployment's `volumeMounts` and the bundle's own `manifest.json` — two artifacts in this
tree, with nothing comparing them, which is the shape four of those five defects shared.

WHAT IT CHECKS, per container whose image is OPA:
  1. `--bundle <dir>` is passed at all — without it OPA serves an empty policy and every
     authorization decision silently falls through to the default.
  2. `<dir>/.manifest` is mounted. Directory mode without a manifest loads with roots inferred,
     which is how a bundle can appear healthy while answering about the wrong data.
  3. Every `data.yaml`/`data.json` mounted under `<dir>` sits in a SUBDIRECTORY, never flat — the
     exact #2865 shape.
  4. That subdirectory is one of the `roots` declared in the bundle ConfigMap's `manifest.json`. A
     data file under an undeclared root is silently ignored by OPA rather than rejected, so the
     policy evaluates against absent data and denies (or permits) for a reason no log explains.

Rule 4 is the one worth the effort: rule 3's failure is loud (the container crashes), rule 4's is
silent. Both are cheap once the manifest is being read.

DERIVED, NOT DECLARED: the roots come from each bundle's own `manifest.json`, so there is no
per-service list to maintain and no exception file. 40 sidecars, 40 bundle ConfigMaps, zero
findings and zero exceptions at registration.

Usage:  check-opa-sidecar-bundle-shape.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import yaml

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
COMPONENTS = REPO / "openbank-infra/gitops/components"
WORKLOADS = {"Deployment", "Rollout", "StatefulSet", "DaemonSet"}
DATA_FILES = ("data.yaml", "data.json")


def documents(root: pathlib.Path):
    for path in gatelib.rglob(root, "*.yaml"):
        try:
            docs = gatelib.load_yaml_all(path)
        except (yaml.YAMLError, OSError, UnicodeDecodeError):
            continue
        for doc in docs:
            if isinstance(doc, dict):
                yield path, doc


def bundle_roots(root: pathlib.Path) -> dict[str, set[str]]:
    """ConfigMap name -> the roots its manifest.json declares."""
    out: dict[str, set[str]] = {}
    for _, doc in documents(root):
        if doc.get("kind") != "ConfigMap":
            continue
        data = doc.get("data") or {}
        if "manifest.json" not in data:
            continue
        try:
            roots = set(json.loads(data["manifest.json"]).get("roots") or [])
        except (json.JSONDecodeError, TypeError):
            roots = set()
        out[(doc.get("metadata") or {}).get("name")] = roots
    return out


def bundle_dir(args: list[str]) -> str | None:
    for i, arg in enumerate(args):
        if arg == "--bundle" and i + 1 < len(args):
            return args[i + 1]
        if arg.startswith("--bundle="):
            return arg.split("=", 1)[1]
    return None


def findings(root: pathlib.Path = COMPONENTS) -> tuple[list[str], int]:
    roots_by_cm = bundle_roots(root)
    out: list[str] = []
    sidecars = 0

    for path, doc in documents(root):
        if doc.get("kind") not in WORKLOADS:
            continue
        podspec = (((doc.get("spec") or {}).get("template") or {}).get("spec") or {})
        volumes = {v.get("name"): v for v in (podspec.get("volumes") or [])}
        workload = (doc.get("metadata") or {}).get("name")
        where = f"{workload} ({path.name})"

        for container in podspec.get("containers") or []:
            if "opa" not in str(container.get("image", "")).lower():
                continue
            sidecars += 1
            args = [a for a in (container.get("args") or []) if isinstance(a, str)]
            mounts = container.get("volumeMounts") or []

            bdir = bundle_dir(args)
            if not bdir:
                out.append(f"{where}: the OPA sidecar passes no --bundle. It will serve an empty "
                           f"policy and every decision falls through to the default, silently.")
                continue

            prefix = bdir.rstrip("/") + "/"
            if not any((m.get("mountPath") or "") == prefix + ".manifest" for m in mounts):
                out.append(f"{where}: no {prefix}.manifest mount. In directory mode OPA then "
                           f"infers roots, so the bundle can load and answer about the wrong data.")

            cm_names = {
                (volumes.get(m.get("name"), {}).get("configMap") or {}).get("name")
                for m in mounts
                if "configMap" in (volumes.get(m.get("name")) or {})
            }
            declared: set[str] = set()
            for name in cm_names:
                declared |= roots_by_cm.get(name, set())

            for mount in mounts:
                mp = mount.get("mountPath") or ""
                if not mp.startswith(prefix):
                    continue
                rel = mp[len(prefix):]
                if not rel.endswith(DATA_FILES):
                    continue
                if "/" not in rel:
                    out.append(
                        f"{where}: {mp} is mounted FLAT. OPA derives a data document's path from "
                        f"its directory, so two flat data files claim the same root and the bundle "
                        f"fails to load with 'merge error' — the container dies at boot (#2865). "
                        f"Mount it at {prefix}<root>/{rel.rsplit('-', 1)[-1]} instead.")
                    continue
                subdir = rel.rsplit("/", 1)[0]
                if declared and subdir not in declared:
                    out.append(
                        f"{where}: {mp} sits under '{subdir}', which is not a root declared in the "
                        f"bundle manifest {sorted(declared)}. OPA IGNORES data outside a declared "
                        f"root rather than rejecting it, so the policy evaluates against absent "
                        f"data and no log explains the decision.")

    if sidecars == 0:
        # Never report a clean run on an empty scan: zero sidecars and zero findings are the same
        # output, and the fleet has 40.
        out.append(f"no OPA sidecar found under {root} — the scan is broken or the image name "
                   f"changed. Not reporting a clean run on that.")
    return out, sidecars


def selftest() -> int:
    import tempfile

    def workload(dirpath: pathlib.Path, mounts: list[tuple[str, str]], roots: list[str],
                 args: list[str] | None = None, cm: bool = True) -> None:
        dirpath.mkdir(parents=True, exist_ok=True)
        vm = [{"name": "opa-bundle", "mountPath": mp, "subPath": sp} for mp, sp in mounts]
        (dirpath / "w.yaml").write_text(yaml.safe_dump({
            "apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": "svc"},
            "spec": {"template": {"spec": {
                "volumes": [{"name": "opa-bundle", "configMap": {"name": "svc-opa-bundle"}}],
                "containers": [{"name": "opa", "image": "openpolicyagent/opa:1.0.0",
                                "args": args if args is not None else
                                ["run", "--server", "--bundle", "/bundle"],
                                "volumeMounts": vm}],
            }}},
        }), encoding="utf-8")
        if cm:
            (dirpath / "cm.yaml").write_text(yaml.safe_dump({
                "apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "svc-opa-bundle"},
                "data": {"manifest.json": json.dumps({"roots": roots})},
            }), encoding="utf-8")

    healthy = [("/bundle/.manifest", "manifest.json"),
               ("/bundle/rules/data.yaml", "rules-data.yaml"),
               ("/bundle/agents/data.yaml", "agents-data.yaml")]

    cases = [
        ("the fleet-standard shape", healthy, ["rules", "agents"], None, True, 0),
        # The exact #2865 failure: campaign mounted the data file flat.
        ("a FLAT data mount (campaign's #2865 shape)",
         [("/bundle/.manifest", "manifest.json"), ("/bundle/rules-data.yaml", "rules-data.yaml")],
         ["rules"], None, True, 1),
        ("a data file under an undeclared root",
         [("/bundle/.manifest", "manifest.json"), ("/bundle/typo/data.yaml", "rules-data.yaml")],
         ["rules"], None, True, 1),
        ("no .manifest mounted",
         [("/bundle/rules/data.yaml", "rules-data.yaml")], ["rules"], None, True, 1),
        ("no --bundle argument at all", healthy, ["rules"],
         ["run", "--server", "--addr=0.0.0.0:8181"], True, 1),
        # No manifest ConfigMap: roots are unknown, so rule 4 must NOT fire on a guess — but rule 2
        # still catches the missing manifest if it is missing. Here it is mounted, so: 0.
        ("no bundle ConfigMap to read roots from", healthy, [], None, False, 0),
    ]

    for label, mounts, roots, args, cm, want in cases:
        with tempfile.TemporaryDirectory() as d:
            base = pathlib.Path(d)
            workload(base / "c", mounts, roots, args, cm)
            got, sidecars = findings(base)
        if sidecars != 1:
            print(f"selftest FAIL: {label} — expected to find 1 sidecar, found {sidecars}")
            return 1
        if len(got) != want:
            print(f"selftest FAIL: {label} — expected {want} finding(s), got {len(got)}: {got}")
            return 1

    with tempfile.TemporaryDirectory() as d:
        got, sidecars = findings(pathlib.Path(d))
        if not got or sidecars != 0:
            print("selftest FAIL: an empty tree did not report that it found nothing.")
            return 1

    print(f"selftest OK: {len(cases)} fixture(s) — flat mount, undeclared root, missing manifest, "
          f"missing --bundle, unknown-roots restraint, and an empty tree.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true", dest="self_test")
    args = ap.parse_args()
    if args.self_test:
        return selftest()

    found, sidecars = findings()
    for line in found:
        print(("::error::" if args.enforce else "::warning::") + line)
    print(f"check-opa-sidecar-bundle-shape: {sidecars} OPA sidecar(s) — "
          f"{'clean.' if not found else f'{len(found)} finding(s) above.'}")
    return 1 if found and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
