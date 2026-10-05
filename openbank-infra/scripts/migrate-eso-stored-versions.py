#!/usr/bin/env python3
"""Rewrite ESO custom resources at v1 storage before retiring v1beta1.

Dry-run by default. This script only prints resource counts, never object names,
specs, Secret data, cluster URLs, or kubectl error bodies. See the adjacent
runbook for preflight, execution, verification, and recovery.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import uuid
from dataclasses import dataclass
from typing import Any


GROUP = "external-secrets.io"
MIGRATABLE_PLURALS = {"externalsecrets", "secretstores", "clustersecretstores", "clusterexternalsecrets"}
REQUIRED_PLURALS = {"externalsecrets", "secretstores", "clustersecretstores"}
ANNOTATION = "openbank.io/eso-storage-v1-migration"
ANNOTATION_PATH = "/metadata/annotations/" + ANNOTATION.replace("~", "~0").replace("/", "~1")


class MigrationError(Exception):
    pass


@dataclass(frozen=True)
class Crd:
    name: str
    plural: str
    scope: str
    uid: str
    resource_version: str
    stored_versions: tuple[str, ...]


class Kubectl:
    def __init__(self, context: str):
        self.base = ["kubectl", "--context", context, "--request-timeout=30s"]

    def call(self, *args: str, payload: Any | None = None) -> dict[str, Any]:
        cmd = [*self.base, *args, "-o", "json"]
        if payload is not None:
            cmd.extend(["-p", json.dumps(payload, separators=(",", ":"))])
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=90)
            return json.loads(result.stdout)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
            # kubectl diagnostics can contain resource names and provider configuration.
            raise MigrationError(f"kubectl {args[0]} failed; inspect locally and retry") from exc


def crd_from_object(obj: dict[str, Any]) -> Crd:
    spec = obj["spec"]
    meta = obj["metadata"]
    versions = spec["versions"]
    storage = [version["name"] for version in versions if version.get("storage") is True]
    served_v1 = any(version["name"] == "v1" and version.get("served") is True for version in versions)
    stored = tuple(obj["status"]["storedVersions"])
    if storage != ["v1"] or not served_v1 or "v1" not in stored:
        raise MigrationError("ESO CRD must serve and store v1 before rewriting objects")
    if not stored or set(stored) - {"v1", "v1beta1"}:
        raise MigrationError("ESO CRD has an unexpected stored version; stop for manual review")
    if spec["names"]["plural"] + "." + spec["group"] != meta["name"]:
        raise MigrationError("ESO CRD resource name does not match its group/plural")
    if spec["scope"] not in ("Namespaced", "Cluster"):
        raise MigrationError("ESO CRD has an unexpected scope")
    return Crd(meta["name"], spec["names"]["plural"], spec["scope"],
               meta["uid"], meta["resourceVersion"], stored)


def inventory(kubectl: Kubectl) -> list[Crd]:
    raw = kubectl.call("get", "crds")
    objects = [obj for obj in raw["items"]
               if obj.get("spec", {}).get("group") == GROUP
               and obj["spec"].get("names", {}).get("plural") in MIGRATABLE_PLURALS]
    if not REQUIRED_PLURALS.issubset({obj["spec"]["names"]["plural"] for obj in objects}):
        raise MigrationError("the selected context lacks required ESO v1 CRDs")
    crds = [crd_from_object(obj) for obj in objects]
    return sorted(crds, key=lambda crd: crd.name)


def objects(kubectl: Kubectl, crd: Crd) -> list[dict[str, Any]]:
    args = ["get", crd.name]
    if crd.scope == "Namespaced":
        args.append("--all-namespaces")
    result = kubectl.call(*args)
    return result["items"]


def identity(obj: dict[str, Any]) -> tuple[str, str]:
    meta = obj["metadata"]
    return meta.get("namespace", ""), meta["name"]


def rewrite(kubectl: Kubectl, crd: Crd, obj: dict[str, Any], marker: str) -> None:
    meta = obj["metadata"]
    annotation_patch = ([{"op": "add", "path": "/metadata/annotations", "value": {ANNOTATION: marker}}]
                        if meta.get("annotations") is None else
                        [{"op": "add", "path": ANNOTATION_PATH, "value": marker}])
    patch = [
        {"op": "test", "path": "/metadata/uid", "value": meta["uid"]},
        {"op": "test", "path": "/metadata/resourceVersion", "value": meta["resourceVersion"]},
        *annotation_patch,
    ]
    args = ["patch", crd.name, meta["name"]]
    if crd.scope == "Namespaced":
        args.extend(["--namespace", meta["namespace"]])
    args.append("--type=json")
    result = kubectl.call(*args, payload=patch)
    current = result["metadata"]
    if current["uid"] != meta["uid"] or current["resourceVersion"] == meta["resourceVersion"]:
        raise MigrationError("object write did not advance resourceVersion; status will not be trimmed")
    if current.get("annotations", {}).get(ANNOTATION) != marker:
        raise MigrationError("object write did not preserve migration marker")


def verify(kubectl: Kubectl, crd: Crd, marker: str, original: list[dict[str, Any]]) -> int:
    current = objects(kubectl, crd)
    old_uids = {identity(obj): obj["metadata"]["uid"] for obj in original}
    current_uids = {identity(obj): obj["metadata"]["uid"] for obj in current}
    if any(current_uids.get(key) != uid for key, uid in old_uids.items()):
        raise MigrationError("a previously listed object disappeared or changed UID; retry after reconciliation")
    if any(obj["metadata"].get("annotations", {}).get(ANNOTATION) != marker for obj in current):
        raise MigrationError("an object was added or lost its marker during migration; retry safely")
    return len(current)


def recheck_crd(kubectl: Kubectl, crd: Crd) -> Crd:
    current = crd_from_object(kubectl.call("get", "crd", crd.name))
    if current.uid != crd.uid or current.stored_versions != crd.stored_versions:
        raise MigrationError("CRD changed during the migration; stop for manual review")
    return current


def finalize(kubectl: Kubectl, crd: Crd) -> None:
    current = recheck_crd(kubectl, crd)
    if "v1beta1" not in current.stored_versions:
        return
    patch = [
        {"op": "test", "path": "/metadata/uid", "value": current.uid},
        {"op": "test", "path": "/metadata/resourceVersion", "value": current.resource_version},
        {"op": "test", "path": "/status/storedVersions", "value": list(current.stored_versions)},
        {"op": "replace", "path": "/status/storedVersions", "value": ["v1"]},
    ]
    result = kubectl.call("patch", "crd", crd.name, "--subresource=status", "--type=json", payload=patch)
    if result["status"]["storedVersions"] != ["v1"]:
        raise MigrationError("CRD status patch did not report v1-only storage")
    if crd_from_object(kubectl.call("get", "crd", crd.name)).stored_versions != ("v1",):
        raise MigrationError("CRD did not retain v1-only storedVersions")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True, help="explicit kubeconfig context")
    parser.add_argument("--execute", action="store_true", help="rewrite objects (otherwise read-only)")
    parser.add_argument("--finalize", action="store_true", help="after verification, trim CRD storedVersions")
    args = parser.parse_args()
    if args.finalize and not args.execute:
        parser.error("--finalize requires --execute")
    kubectl = Kubectl(args.context)
    try:
        crds = inventory(kubectl)
        pending = [crd for crd in crds if "v1beta1" in crd.stored_versions]
        snapshots = {crd.name: objects(kubectl, crd) for crd in pending}
        print(f"ESO CRDs: {len(crds)}; pending storage migration: {len(pending)}; "
              f"custom resources to rewrite: {sum(map(len, snapshots.values()))}")
        if not args.execute:
            print("Read-only plan complete. Use --execute --finalize after reviewing the runbook.")
            return 0
        marker = str(uuid.uuid4())
        for crd in pending:
            recheck_crd(kubectl, crd)
            for obj in snapshots[crd.name]:
                rewrite(kubectl, crd, obj, marker)
            count = verify(kubectl, crd, marker, snapshots[crd.name])
            recheck_crd(kubectl, crd)
            print(f"Verified {count} objects for one ESO CRD at v1 storage")
        if args.finalize:
            for crd in pending:
                finalize(kubectl, crd)
            print(f"Finalized {len(pending)} CRDs; each now lists only v1 in status.storedVersions")
        else:
            print("Objects rewritten. CRD status is unchanged; rerun with --execute --finalize to complete.")
        return 0
    except MigrationError as exc:
        print(f"Migration stopped: {exc}", file=sys.stderr)
        print("No further CRD status changes will be made; inspect cluster state and retry.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
