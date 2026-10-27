#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Optional exact-source ARM fast-jar handoff; every restore failure is a build miss."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import time
import zipfile

SERVICE = "openbank-context-service"
WORKFLOW = ".github/workflows/services-ci.yml"
RECIPE = "fast-jar-clickhouse-redis-v1"
PLATFORM = "linux/arm64"
MAX_BYTES = 512 * 1024 * 1024
MAX_FILES = 10_000
SHA = re.compile(r"[0-9a-f]{40}\Z")
REPO = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identity(service, sha):
    require(service == SERVICE and bool(SHA.fullmatch(sha)), "unsupported service/source")


def artifact_name(sha, attempt):
    return f"service-build-{SERVICE}-{sha}-a{attempt}"


def valid_path(name):
    path = PurePosixPath(name)
    require(name == str(path) and not path.is_absolute() and all(p not in {".", ".."} for p in path.parts)
            and "\\" not in name and "\x00" not in name and bool(path.parts), "unsafe member path")
    return path


def payload_path(name):
    path = valid_path(name)
    return name == "reports/bom.json" or (len(path.parts) > 1 and path.parts[0] == "quarkus-app")


def read_zip(raw):
    require(len(raw) <= MAX_BYTES, "archive exceeds size limit")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = archive.infolist()
        require(len(infos) <= MAX_FILES, "too many archive entries")
        seen, total, result = set(), 0, {}
        for info in infos:
            valid_path(info.filename)
            require(info.filename not in seen, "duplicate archive entry")
            seen.add(info.filename)
            mode = info.external_attr >> 16
            require(not info.is_dir() and stat.S_IFMT(mode) in {0, stat.S_IFREG}, "nonregular archive entry")
            require(not info.flag_bits & 1, "encrypted archive entry")
            total += info.file_size
            require(total <= MAX_BYTES, "expanded archive exceeds size limit")
            result[info.filename] = archive.read(info)
        return result


def json_object(raw):
    def unique(pairs):
        out = {}
        for key, value in pairs:
            require(key not in out, "duplicate JSON key")
            out[key] = value
        return out
    value = json.loads(raw, object_pairs_hook=unique)
    require(isinstance(value, dict), "expected JSON object")
    return value


def validate_bundle(raw, sha, run_id, attempt):
    files = read_zip(raw)
    require("manifest.json" in files, "missing manifest")
    manifest = json_object(files.pop("manifest.json"))
    expected = {"schemaVersion": 1, "service": SERVICE, "sha": sha, "runId": run_id,
                "attempt": attempt, "platform": PLATFORM, "recipe": RECIPE}
    require(set(manifest) == set(expected) | {"files"}, "unexpected manifest fields")
    for key, value in expected.items():
        require(type(manifest[key]) is type(value) and manifest[key] == value, f"manifest {key} mismatch")
    declared = manifest["files"]
    require(isinstance(declared, dict) and set(declared) == set(files), "file inventory mismatch")
    require("quarkus-app/quarkus-run.jar" in files and "reports/bom.json" in files, "incomplete build")
    for name, data in files.items():
        require(payload_path(name), "unexpected payload path")
        entry = declared[name]
        require(isinstance(entry, dict) and set(entry) == {"size", "sha256"}
                and type(entry["size"]) is int and entry["size"] == len(data)
                and entry["sha256"] == hashlib.sha256(data).hexdigest(), "payload checksum mismatch")
    bom = json_object(files["reports/bom.json"])
    require(bom.get("bomFormat") == "CycloneDX" and isinstance(bom.get("components"), list)
            and bool(bom["components"]), "invalid or empty SBOM")
    return files


def pack(service, sha, run_id, attempt, out, root=Path(".")):
    identity(service, sha)
    require(type(run_id) is int and run_id > 0 and type(attempt) is int and attempt > 0, "invalid run identity")
    require(os.environ.get("RUNNER_ARCH") == "ARM64" and os.environ.get("RUNNER_OS") == "Linux",
            "producer must run natively on Linux ARM64")
    require(os.environ.get("ANALYTICS_SINK_TYPE") == "clickhouse"
            and os.environ.get("AGENT_SVID_NONCE_STORE") == "redis", "producer recipe environment mismatch")
    base = root / service / "build"
    require(not base.is_symlink() and not (base / "quarkus-app").is_symlink()
            and not (base / "reports").is_symlink(), "symlink build root")
    paths = sorted((base / "quarkus-app").rglob("*")) + [base / "reports/bom.json"]
    files, total = {}, 0
    for path in paths:
        require(not path.is_symlink(), "symlink build member")
        if path.is_dir():
            continue
        require(path.is_file(), "nonregular build member")
        total += path.stat().st_size
        require(total <= MAX_BYTES and len(files) < MAX_FILES - 1, "build exceeds limits")
        files[path.relative_to(base).as_posix()] = path.read_bytes()
    manifest = {"schemaVersion": 1, "service": service, "sha": sha, "runId": run_id,
                "attempt": attempt, "platform": PLATFORM, "recipe": RECIPE,
                "files": {name: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                          for name, data in files.items()}}
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, sort_keys=True))
        for name, data in files.items():
            archive.writestr(name, data)
    raw = buffer.getvalue()
    validate_bundle(raw, sha, run_id, attempt)
    out.mkdir(parents=True, exist_ok=True)
    (out / "deploy-build.zip").write_bytes(raw)


def gh_json(endpoint):
    result = subprocess.run(["gh", "api", endpoint], check=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=30)
    require(len(result.stdout) <= 4 * 1024 * 1024, "API response too large")
    return json_object(result.stdout)


def trusted_run(run, repo, sha):
    return (isinstance(run, dict) and run.get("event") == "push" and run.get("head_branch") == "main"
            and run.get("head_sha") == sha and run.get("path") == WORKFLOW
            and run.get("status") == "completed" and run.get("conclusion") == "success"
            and isinstance(run.get("repository"), dict) and run["repository"].get("full_name") == repo
            and isinstance(run.get("head_repository"), dict) and run["head_repository"].get("full_name") == repo
            and type(run.get("id")) is int and run["id"] > 0
            and type(run.get("run_attempt")) is int and run["run_attempt"] > 0)


def download(repo, artifact_id, target):
    # Stream to a bounded temporary file; never retain downloads in the checkout.
    with target.open("wb") as output:
        process = subprocess.Popen(["gh", "api", f"repos/{repo}/actions/artifacts/{artifact_id}/zip"],
                                   stdout=output, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 60
            while process.poll() is None:
                require(target.stat().st_size <= MAX_BYTES, "download exceeds size limit")
                require(time.monotonic() < deadline, "download timed out")
                time.sleep(0.05)
            require(process.returncode == 0 and target.stat().st_size <= MAX_BYTES, "download failed")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


def restore(repo, service, sha, destination):
    identity(service, sha)
    require(bool(REPO.fullmatch(repo)), "invalid repository")
    parent = destination.resolve() / service
    require(parent.is_dir() and not parent.is_symlink(), "invalid service destination")
    target = parent / "build"
    require(not target.exists() and not target.is_symlink(), "existing build outputs retained")
    runs = gh_json(f"repos/{repo}/actions/workflows/services-ci.yml/runs?event=push&branch=main&head_sha={sha}&status=success&per_page=10").get("workflow_runs", [])
    require(isinstance(runs, list), "invalid run inventory")
    for candidate in runs[:10]:
        if not trusted_run(candidate, repo, sha):
            continue
        # Re-read the exact attempt, not a potentially stale list response.
        run = gh_json(f"repos/{repo}/actions/runs/{candidate['id']}/attempts/{candidate['run_attempt']}")
        if not trusted_run(run, repo, sha) or run["id"] != candidate["id"] or run["run_attempt"] != candidate["run_attempt"]:
            continue
        listing = gh_json(f"repos/{repo}/actions/runs/{run['id']}/artifacts?per_page=100&name={artifact_name(sha, run['run_attempt'])}")
        artifacts = listing.get("artifacts", [])
        require(isinstance(artifacts, list), "invalid artifact inventory")
        matches = [item for item in artifacts if isinstance(item, dict) and item.get("name") == artifact_name(sha, run["run_attempt"])]
        if not matches:
            continue
        require(len(matches) == 1, "ambiguous artifact")
        item = matches[0]
        require(item.get("expired") is False and type(item.get("id")) is int and item["id"] > 0
                and type(item.get("size_in_bytes")) is int and 0 < item["size_in_bytes"] <= MAX_BYTES, "invalid artifact metadata")
        digest = item.get("digest")
        require(isinstance(digest, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is not None,
                "missing or invalid artifact digest")
        producer = item.get("workflow_run") or {}
        require(isinstance(producer, dict) and producer.get("id") == run["id"] and producer.get("head_sha") == sha
                and producer.get("head_branch") == "main", "artifact producer mismatch")
        with tempfile.TemporaryDirectory(prefix="service-build-") as directory:
            archive = Path(directory) / "artifact.zip"
            download(repo, item["id"], archive)
            raw = archive.read_bytes()
            require(len(raw) == item["size_in_bytes"], "artifact download size mismatch")
            require("sha256:" + hashlib.sha256(raw).hexdigest() == digest, "artifact download digest mismatch")
            outer = read_zip(raw)
            require(set(outer) == {"deploy-build.zip"}, "unexpected artifact contents")
            files = validate_bundle(outer["deploy-build.zip"], sha, run["id"], run["run_attempt"])
        # Validate everything first, then atomically install the complete build directory.
        staging = Path(tempfile.mkdtemp(prefix=".build-handoff-", dir=parent))
        try:
            for name, data in files.items():
                path = staging / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                path.chmod(0o644)
            require(not target.exists() and not target.is_symlink(), "build outputs appeared during restore")
            staging.rename(target)
        finally:
            if staging.exists():
                shutil.rmtree(staging)
        return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("pack", "restore"):
        command = commands.add_parser(name)
        command.add_argument("--service", required=True)
        command.add_argument("--sha", required=True)
        if name == "pack":
            command.add_argument("--run-id", required=True, type=int)
            command.add_argument("--attempt", required=True, type=int)
            command.add_argument("--out", required=True, type=Path)
        else:
            command.add_argument("--repo", required=True)
            command.add_argument("--destination", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "pack":
        pack(args.service, args.sha, args.run_id, args.attempt, args.out)
        return 0
    restored = False
    try:
        restored = restore(args.repo, args.service, args.sha, args.destination)
    except Exception as exc:  # Optional optimization: corrupt/unsupported archives also fall back.
        # Do not print remote messages, filesystem paths or credentials from API failures.
        print(f"Service build artifact miss ({type(exc).__name__}); normal deploy build required.")
    print(f"Service build artifact restored: {str(restored).lower()}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"restored={str(restored).lower()}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
