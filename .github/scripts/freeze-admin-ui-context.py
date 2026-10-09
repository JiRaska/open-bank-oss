#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Freeze the Admin UI Docker context after collectors have written their inputs.

Only tracked files and explicitly named generated evidence enter this context. A
local untracked source file therefore cannot silently enter a released image.
The output directory must be outside the repository and is consumed by buildx.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path


GENERATED_DIRS = (
    "openbank-admin-ui/client-test-evidence",
    "openbank-admin-ui/perf-artifacts",
    "openbank-admin-ui/test-intelligence-history",
    "openbank-admin-ui/test-run-history",
    "sbom-staging",
)
GENERATED_ROOT_JSON = (
    "ai-governance-snapshot.json", "app-status.json", "card-capabilities.json",
    "catalog.json", "cluster-topology.json", "cost-footprints.json",
    "cost-report.json", "dora.json", "events.json", "gate-catalog.json",
    "gate-health.json", "governance.json", "infra-lifecycle.json",
    "infra-vulns.json", "origination-graph.json", "prod-readiness.json",
    "quality-report.json", "security-graph.json", "service-graph.json",
    "test-intelligence.json", "test-results.json",
)
SBOM_PATH = re.compile(r"openbank-[^/]+/build/reports/bom\.json\Z")
EXTERNAL_ROOT_SOURCES = {
    "cost-report.json": "aws-cost-explorer",
    "infra-lifecycle.json": "endoflife-date",
    "infra-vulns.json": "grype-or-placeholder",
    "gate-health.json": "github-actions-api",
    "quality-report.json": "pact-and-pitest-evidence",
    "test-intelligence.json": "github-actions-evidence",
    "test-results.json": "junit-evidence",
}
EXTERNAL_DIR_SOURCES = {
    "openbank-admin-ui/client-test-evidence": "client-actions-artifact",
    "openbank-admin-ui/perf-artifacts": "performance-actions-artifact",
    "openbank-admin-ui/test-intelligence-history": "admin-ui-actions-artifact",
    "openbank-admin-ui/test-run-history": "test-intelligence-actions-artifact",
    "sbom-staging": "security-actions-artifact",
}
ARTIFACT_SOURCES = set(EXTERNAL_DIR_SOURCES.values()) | {"security-actions-artifact"}


def valid_receipt(item: object) -> bool:
    return (isinstance(item, dict) and set(item) == {"source", "artifactId", "archiveSha256", "path", "sha256"}
            and item["source"] in ARTIFACT_SOURCES
            and isinstance(item["artifactId"], str) and item["artifactId"].isdecimal()
            and isinstance(item["path"], str) and item["path"]
            and not Path(item["path"]).is_absolute() and ".." not in Path(item["path"]).parts
            and isinstance(item["sha256"], str)
            and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) is not None
            and isinstance(item["archiveSha256"], str)
            and re.fullmatch(r"[0-9a-f]{64}", item["archiveSha256"]) is not None)


def feed_receipts() -> list[dict]:
    """Read the producer's private, append-only artifact ledger into the signed record."""
    ledger = os.environ.get("ADMIN_UI_FEED_RECEIPTS", "")
    if not ledger:
        return []
    path = Path(ledger)
    if not path.is_file():
        raise ValueError("external feed receipt ledger is missing")
    receipts = []
    for line in path.read_text().splitlines():
        item = json.loads(line)
        if not valid_receipt(item):
            raise ValueError("external feed receipt is malformed")
        receipts.append(item)
    return sorted(receipts, key=lambda x: (x["source"], x["artifactId"], x["path"]))


def receipts_cover(entries: list[dict], receipts: list[dict]) -> bool:
    """Every artifact-backed frozen input must have a signed producer receipt."""
    if not isinstance(receipts, list) or any(not valid_receipt(item) for item in receipts):
        return False
    if receipts != sorted(receipts, key=lambda x: (x["source"], x["artifactId"], x["path"])):
        return False
    receipt_paths = {item["path"] for item in receipts}
    if len(receipt_paths) != len(receipts):
        return False
    if not receipt_paths.issubset({entry["path"] for entry in entries}):
        return False
    by_path = {item["path"]: item for item in receipts}
    for entry in entries:
        material = entry["material"]
        source = material.get("source")
        if source in ARTIFACT_SOURCES:
            receipt = by_path.get(entry["path"])
            if (receipt is None or receipt["source"] != source
                    or receipt["sha256"] != entry.get("sha256")
                    or receipt["artifactId"] != material.get("artifactId")
                    or receipt["archiveSha256"] != material.get("archiveSha256")):
                return False
            if source == "client-actions-artifact" and Path(entry["path"]).name != f"openbank-app-{receipt['artifactId']}.json":
                return False
    return True


def material_for(relative: Path, sha256: str, source_sha: str, producer: dict) -> dict:
    """Classify every byte included after checkout; unknown provenance is fatal."""
    root_json = relative.name if relative.parent == Path("openbank-admin-ui") else None
    if root_json in GENERATED_ROOT_JSON:
        source = EXTERNAL_ROOT_SOURCES.get(root_json, "repo-derived-collector")
    elif SBOM_PATH.fullmatch(relative.as_posix()):
        source = "security-actions-artifact"
    elif relative == Path("openbank-admin-ui/test-run-history/.staged-ids"):
        source = "repo-derived-collector"
    else:
        source = next((kind for directory, kind in EXTERNAL_DIR_SOURCES.items()
                       if relative.is_relative_to(Path(directory)) and relative != Path(directory)), None)
    if source is None:
        return {"kind": "git", "commit": source_sha}
    if producer.get("kind") != "github-actions":
        # Local builds remain possible, but their material provenance cannot pass
        # the signed CI deploy verifier.
        return {"kind": "unverified-local", "source": source, "sha256": sha256}
    if source == "client-actions-artifact" and not re.fullmatch(r"openbank-app-[0-9]+\.json", relative.name):
        raise ValueError("client evidence has no source artifact identifier")
    material = {"kind": "producer-captured", "source": source, "sha256": sha256,
                "runId": producer["runId"], "runAttempt": producer["runAttempt"]}
    return material


def producer_identity() -> dict:
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "")
    workflow = os.environ.get("GITHUB_WORKFLOW_REF", "")
    if (os.environ.get("GITHUB_ACTIONS") == "true" and run_id.isdecimal()
            and attempt.isdecimal() and workflow.endswith(
                ".github/workflows/admin-ui-deploy.yml@refs/heads/main")):
        return {"kind": "github-actions", "runId": run_id,
                "runAttempt": attempt, "workflow": workflow}
    return {"kind": "unverified-local"}


def paths(root: Path) -> set[Path]:
    proc = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True)
    found = {Path(os.fsdecode(p)) for p in proc.stdout.split(b"\0") if p}
    for name in GENERATED_ROOT_JSON:
        p = Path("openbank-admin-ui") / name
        if (root / p).is_file():
            found.add(p)
    for name in GENERATED_DIRS:
        directory = root / name
        if directory.exists():
            for p in directory.rglob("*"):
                if not p.is_file():
                    continue
                if name == "sbom-staging":
                    if p.parent != directory or not re.fullmatch(r"openbank-[^/]+\.json", p.name):
                        raise ValueError(f"unexpected flat SBOM input: {p.relative_to(root)}")
                    original = root / p.name.removesuffix(".json") / "build/reports/bom.json"
                    if not original.is_file() or p.read_bytes() != original.read_bytes():
                        raise ValueError(f"flat SBOM differs from staged source: {p.relative_to(root)}")
                found.add(p.relative_to(root))
    # admin-ui-deploy.yml stages per-service CycloneDX reports before the build.
    # They are normally untracked, yet Dockerfile's sbom-collector copies them
    # into the runtime image. Freeze exactly that path shape and hash the bytes.
    found.update(p.relative_to(root) for p in root.glob("openbank-*/build/reports/bom.json")
                 if p.is_file() and SBOM_PATH.fullmatch(p.relative_to(root).as_posix()))
    others = subprocess.run(["git", "ls-files", "--others", "--exclude-standard", "-z"],
                            cwd=root, check=True, capture_output=True)
    non_inputs = (".app-src/", "sbom-downloads/")
    unknown = [os.fsdecode(p) for p in others.stdout.split(b"\0") if p
               and Path(os.fsdecode(p)) not in found
               and not os.fsdecode(p).startswith(non_inputs)]
    if unknown:
        raise ValueError(f"{len(unknown)} unknown untracked build input(s)")
    return found


def require_clean_source_inputs(root: Path) -> None:
    """Only explicitly generated evidence may differ from the source commit."""
    changed = subprocess.run(
        ["git", "diff", "HEAD", "--name-only", "-z"],
        cwd=root, check=True, capture_output=True,
    )
    generated = {Path("openbank-admin-ui") / name for name in GENERATED_ROOT_JSON}
    dirty = [Path(os.fsdecode(name)) for name in changed.stdout.split(b"\0") if name]
    if any(name not in generated for name in dirty):
        raise ValueError("tracked source input differs from source commit")


def committed_blobs(root: Path) -> tuple[dict[Path, str], str]:
    listing = subprocess.run(["git", "ls-tree", "-rz", "HEAD"], cwd=root,
                             check=True, capture_output=True).stdout
    blobs = {}
    for entry in listing.split(b"\0"):
        if not entry:
            continue
        header, name = entry.split(b"\t", 1)
        mode, kind, object_id = header.split(b" ")
        if kind == b"blob":
            blobs[Path(os.fsdecode(name))] = object_id.decode("ascii")
    object_format = subprocess.run(["git", "rev-parse", "--show-object-format=storage"],
                                   cwd=root, check=True, capture_output=True,
                                   text=True).stdout.strip()
    if object_format not in ("sha1", "sha256"):
        raise ValueError("unknown source commit object format")
    return blobs, object_format


def require_committed_content(relative: Path, content: bytes, blobs: dict[Path, str],
                              object_format: str) -> None:
    if relative in {Path("openbank-admin-ui") / name for name in GENERATED_ROOT_JSON}:
        return
    expected = blobs.get(relative)
    # These four evidence directories are produced after checkout and need not
    # have Git objects. Their bytes still enter the signed frozen inventory.
    if expected is None and any(relative.is_relative_to(Path(directory))
                                and relative != Path(directory) for directory in GENERATED_DIRS):
        return
    if expected is None and SBOM_PATH.fullmatch(relative.as_posix()):
        return
    digest = hashlib.new(object_format, b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()
    if expected != digest:
        raise ValueError(f"frozen tracked input differs from source commit: {relative}")


def freeze(root: Path, output: Path, manifest: Path) -> dict:
    if output.exists() or manifest.exists():
        raise ValueError("output and manifest must not already exist")
    if output.is_relative_to(root) or manifest.is_relative_to(root):
        raise ValueError("frozen context and manifest must be outside the source tree")
    require_clean_source_inputs(root)
    blobs, object_format = committed_blobs(root)
    source_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                capture_output=True, text=True).stdout.strip()
    producer = producer_identity()
    output.mkdir(parents=True)
    entries = []
    for relative in sorted(paths(root), key=str):
        source = root / relative
        if source.is_symlink():
            link = os.readlink(source)
            resolved = (source.parent / link).resolve()
            if not resolved.is_relative_to(root):
                raise ValueError(f"external symlink in build context: {relative}")
            require_committed_content(relative, os.fsencode(link), blobs, object_format)
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(link)
            entries.append({"path": relative.as_posix(), "symlink": link,
                            "material": material_for(relative, hashlib.sha256(os.fsencode(link)).hexdigest(),
                                                     source_sha, producer)})
            continue
        if not source.is_file():
            raise ValueError(f"missing tracked build input: {relative}")
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        content = target.read_bytes()
        require_committed_content(relative, content, blobs, object_format)
        digest = hashlib.sha256(content).hexdigest()
        entries.append({"path": relative.as_posix(), "sha256": digest,
                        "size": len(content), "executable": bool(target.stat().st_mode & 0o111),
                        "material": material_for(relative, digest, source_sha, producer)})
    require_clean_source_inputs(root)
    receipts = feed_receipts()
    receipt_by_path = {item["path"]: item for item in receipts}
    # The build script copies each verified service BOM into the flat collector
    # directory. Bind that copy to the same upstream file, byte for byte.
    for entry in entries:
        rel = Path(entry["path"])
        if rel.parent == Path("sbom-staging") and rel.suffix == ".json":
            original = f"{rel.stem}/build/reports/bom.json"
            source_receipt = receipt_by_path.get(original)
            if (entry["path"] not in receipt_by_path and source_receipt is not None
                    and source_receipt["sha256"] == entry.get("sha256")):
                copied = dict(source_receipt, path=entry["path"])
                receipts.append(copied)
                receipt_by_path[entry["path"]] = copied
    receipts.sort(key=lambda x: (x["source"], x["artifactId"], x["path"]))
    if producer.get("kind") == "github-actions":
        for entry in entries:
            if entry["material"].get("source") in ARTIFACT_SOURCES:
                receipt = receipt_by_path.get(entry["path"])
                if receipt is not None:
                    entry["material"]["artifactId"] = receipt["artifactId"]
                    entry["material"]["archiveSha256"] = receipt["archiveSha256"]
    if producer.get("kind") == "github-actions" and not os.environ.get("ADMIN_UI_FEED_RECEIPTS"):
        raise ValueError("external feed receipt ledger is unavailable")
    if producer.get("kind") == "github-actions" and not receipts_cover(entries, receipts):
        raise ValueError("frozen external input lacks a source artifact receipt")
    staged_ids = root / "openbank-admin-ui/test-run-history/.staged-ids"
    if producer.get("kind") == "github-actions" and staged_ids.is_file():
        recorded_ids = {item["artifactId"] for item in receipts
                        if item["source"] == "test-intelligence-actions-artifact"}
        if not set(staged_ids.read_text().splitlines()).issubset(recorded_ids):
            raise ValueError("cached test history lacks source artifact receipts")
    record = {"schema": "openbank.admin-ui.build-context/v1", "sourceCommit": source_sha,
              "producer": producer, "externalFeeds": receipts, "files": entries}
    manifest.write_text(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
    return record


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    record = freeze(args.root.resolve(), args.output.resolve(), args.manifest.resolve())
    print(f"frozen Admin UI context: {len(record['files'])} files from {record['sourceCommit']}")


if __name__ == "__main__":
    main()
