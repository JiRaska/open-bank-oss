#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Verify capacity evidence integrity and GitHub provenance; never qualify capacity."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import stat
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[2]
PROFILE_REL = "perf/profiles/cz-current-account-5m-v1.yaml"
PROFILE_ID = "cz-current-account-5m-v1"
TRUSTED_REPOSITORY = "JiRaska/open-bank-oss"
TRUSTED_REF = "refs/heads/main"
TRUSTED_WORKFLOW_PATH = ".github/workflows/capacity-qualification.yml"
TRUSTED_SIGNER_WORKFLOW = f"{TRUSTED_REPOSITORY}/{TRUSTED_WORKFLOW_PATH}"
PREDICATE_TYPE = "https://slsa.dev/provenance/v1"
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
MAX_ARTIFACTS = 4096
MAX_FUTURE_SKEW = timedelta(minutes=5)
MAX_PROFILE_FRESHNESS_DAYS = 30
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
IMAGE_DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")


class EvidenceError(ValueError):
    """Evidence failed a required integrity or provenance check."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def _json_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, "JSON contains a duplicate object key")
        result[key] = value
    return result


def _reject_constant(_value: str) -> None:
    raise EvidenceError("JSON contains a non-finite number")


def _finite_float(value: str) -> float:
    number = float(value)
    require(number not in {float("inf"), float("-inf")}, "JSON contains a non-finite number")
    return number


def load_json(raw: bytes, *, limit: int = MAX_MANIFEST_BYTES) -> object:
    require(len(raw) <= limit, "JSON exceeds the allowed byte limit")
    try:
        return json.loads(
            raw.decode("utf-8"), object_pairs_hook=_json_object,
            parse_constant=_reject_constant, parse_float=_finite_float,
        )
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise EvidenceError("JSON is malformed or is not UTF-8") from exc


def parse_time(value: object, field: str) -> datetime:
    require(isinstance(value, str) and value, f"{field} must be a timezone-qualified timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise EvidenceError(f"{field} must be a timezone-qualified timestamp") from exc
    require(parsed.tzinfo is not None and parsed.utcoffset() is not None, f"{field} has no timezone")
    return parsed.astimezone(timezone.utc)


def not_after_reported_time(observed: datetime, raw_timestamp: object, field: str) -> bool:
    """Respect GitHub's whole-second API timestamp interval, or exact fractional bound."""
    reported = parse_time(raw_timestamp, field)
    whole_second = isinstance(raw_timestamp, str) and re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})", raw_timestamp
    )
    return observed < reported + timedelta(seconds=1) if whole_second else observed <= reported


def load_profile_checker(repo_root: Path):
    path = repo_root / ".github/scripts/check-capacity-profile.py"
    spec = importlib.util.spec_from_file_location("capacity_profile_checker", path)
    require(spec is not None and spec.loader is not None, "capacity profile checker is unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_profile(repo_root: Path) -> tuple[dict, bytes]:
    path = repo_root / PROFILE_REL
    require(path.is_file() and not path.is_symlink(), "trusted profile file is missing or a symlink")
    raw = path.read_bytes()
    checker = load_profile_checker(repo_root)
    try:
        profile = checker.load_yaml(path)
        errors = checker.validate(repo_root, profile)
    except (OSError, ValueError, TypeError) as exc:
        raise EvidenceError("trusted profile is invalid") from exc
    require(not errors, "trusted profile consistency check failed")
    require(profile.get("profile_id") == PROFILE_ID, "trusted profile identity is unexpected")
    return profile, raw


def parse_manifest(raw: bytes, profile: dict, profile_bytes: bytes) -> dict:
    manifest = load_json(raw)
    require(isinstance(manifest, dict), "manifest root must be an object")
    expected_top = {"schema", "profile", "source", "run", "campaign", "bindings", "artifacts"}
    require(set(manifest) == expected_top, "manifest fields are incomplete or unknown")
    require(manifest["schema"] == "openbank.capacity-evidence/v1", "manifest schema is unsupported")

    profile_ref = manifest["profile"]
    require(isinstance(profile_ref, dict) and set(profile_ref) == {"id", "version", "sha256"},
            "manifest profile binding is malformed")
    actual_profile_hash = hashlib.sha256(profile_bytes).hexdigest()
    require(profile_ref["id"] == profile["profile_id"] == PROFILE_ID, "manifest profile id does not match")
    require(type(profile_ref["version"]) is int and profile_ref["version"] == profile["schema_version"],
            "manifest profile version does not match")
    require(profile_ref["sha256"] == actual_profile_hash, "manifest profile digest does not match")

    source = manifest["source"]
    require(isinstance(source, dict) and set(source) == {"repository", "commit", "ref", "workflow_path"},
            "manifest source binding is malformed")
    require(source["repository"] == TRUSTED_REPOSITORY, "manifest repository is not trusted")
    require(isinstance(source["commit"], str) and COMMIT.fullmatch(source["commit"]) is not None,
            "manifest source commit must be a full lowercase SHA-1")
    require(source["ref"] == TRUSTED_REF, "manifest source ref is not trusted")
    require(source["workflow_path"] == TRUSTED_WORKFLOW_PATH, "manifest workflow path is not trusted")

    run = manifest["run"]
    require(isinstance(run, dict) and set(run) == {"id", "attempt", "started_at", "ended_at", "observed_at"},
            "manifest run binding is malformed")
    require(type(run["id"]) is int and run["id"] > 0, "manifest run id must be a positive integer")
    require(type(run["attempt"]) is int and run["attempt"] > 0, "manifest attempt must be a positive integer")
    started = parse_time(run["started_at"], "run.started_at")
    ended = parse_time(run["ended_at"], "run.ended_at")
    observed = parse_time(run["observed_at"], "run.observed_at")
    require(started <= ended <= observed, "manifest run timestamps are not ordered")

    campaign = manifest["campaign"]
    require(isinstance(campaign, dict) and set(campaign) == {"scope"}, "manifest campaign is malformed")
    require(campaign["scope"] in {
        "bounded-sandbox-observation", "modeled-five-million",
        "platform-qualification", "bank-production-qualification",
    }, "manifest campaign scope is unknown")

    bindings = manifest["bindings"]
    expected_bindings = {
        "application_image_digests", "k6_image_digest", "effective_configuration",
        "topology", "dataset",
    }
    require(isinstance(bindings, dict) and set(bindings) == expected_bindings,
            "manifest immutable bindings are incomplete or unknown")
    app_digests = bindings["application_image_digests"]
    require(isinstance(app_digests, list) and app_digests, "application image digests are required")
    require(all(isinstance(value, str) and IMAGE_DIGEST.fullmatch(value) for value in app_digests),
            "application image digest is invalid")
    require(len(set(app_digests)) == len(app_digests), "application image digests contain duplicates")
    require(isinstance(bindings["k6_image_digest"], str)
            and IMAGE_DIGEST.fullmatch(bindings["k6_image_digest"]) is not None, "k6 image digest is invalid")
    for field in ("effective_configuration", "topology"):
        descriptor = bindings[field]
        require(isinstance(descriptor, dict) and set(descriptor) == {"artifact", "sha256"},
                f"{field} artifact binding is malformed")
        require(isinstance(descriptor["artifact"], str) and safe_relative_path(descriptor["artifact"]),
                f"{field} artifact path is unsafe")
        require(isinstance(descriptor["sha256"], str) and SHA256.fullmatch(descriptor["sha256"]) is not None,
                f"{field} artifact SHA-256 is invalid")
    dataset = bindings["dataset"]
    require(isinstance(dataset, dict) and set(dataset) == {
        "descriptor_artifact", "descriptor_sha256", "seed_sha256", "generator_version",
    }, "dataset descriptor binding is malformed")
    require(isinstance(dataset["descriptor_artifact"], str)
            and safe_relative_path(dataset["descriptor_artifact"]), "dataset descriptor path is unsafe")
    for field in ("descriptor_sha256", "seed_sha256"):
        require(isinstance(dataset[field], str) and SHA256.fullmatch(dataset[field]) is not None,
                f"dataset {field} is invalid")
    require(isinstance(dataset["generator_version"], str) and dataset["generator_version"].strip(),
            "dataset generator version is required")

    artifacts = manifest["artifacts"]
    require(isinstance(artifacts, list) and 1 <= len(artifacts) <= MAX_ARTIFACTS,
            "manifest artifact inventory is empty or too large")
    seen_paths = set()
    for artifact in artifacts:
        require(isinstance(artifact, dict) and set(artifact) == {"path", "size", "sha256"},
                "artifact inventory entry is malformed")
        path = artifact["path"]
        require(isinstance(path, str) and safe_relative_path(path), "artifact path is unsafe")
        require(path != "manifest.json" and path not in seen_paths, "artifact path is duplicated or names the manifest")
        seen_paths.add(path)
        require(type(artifact["size"]) is int and artifact["size"] >= 0, "artifact size must be a non-negative integer")
        require(isinstance(artifact["sha256"], str) and SHA256.fullmatch(artifact["sha256"]) is not None,
                "artifact SHA-256 is invalid")
    inventory_by_path = {artifact["path"]: artifact for artifact in artifacts}
    linked_bindings = (
        (bindings["effective_configuration"]["artifact"], bindings["effective_configuration"]["sha256"]),
        (bindings["topology"]["artifact"], bindings["topology"]["sha256"]),
        (dataset["descriptor_artifact"], dataset["descriptor_sha256"]),
    )
    for path, digest in linked_bindings:
        item = inventory_by_path.get(path)
        require(item is not None, f"binding artifact is missing from inventory: {path}")
        require(item["sha256"] == digest, f"binding digest differs from artifact inventory: {path}")
    return manifest


def safe_relative_path(value: str) -> bool:
    if not value or "\x00" in value or "\\" in value or value.startswith("/") or re.match(r"^[A-Za-z]:", value):
        return False
    parsed = PurePosixPath(value)
    return parsed.as_posix() == value and all(part not in {"", ".", ".."} for part in parsed.parts)


def inventory_files(root: Path) -> set[str]:
    found = set()
    pending = [root]
    while pending:
        directory = pending.pop()
        try:
            children = list(os.scandir(directory))
        except OSError as exc:
            raise EvidenceError("evidence directory cannot be read") from exc
        for child in children:
            try:
                mode = child.stat(follow_symlinks=False).st_mode
            except OSError as exc:
                raise EvidenceError("evidence path cannot be inspected") from exc
            if stat.S_ISLNK(mode):
                raise EvidenceError("evidence bundle contains a symlink")
            relative = Path(child.path).relative_to(root).as_posix()
            if stat.S_ISDIR(mode):
                pending.append(Path(child.path))
            elif stat.S_ISREG(mode):
                found.add(relative)
            else:
                raise EvidenceError("evidence bundle contains a non-regular file")
    return found


def checked_open(path: Path, expected_stat: os.stat_result) -> int:
    nofollow = getattr(os, "O_NOFOLLOW", None)
    require(nofollow is not None, "platform does not support no-follow file opens")
    flags = os.O_RDONLY | nofollow | getattr(os, "O_NONBLOCK", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise EvidenceError("evidence file cannot be opened safely") from exc
    try:
        opened_stat = os.fstat(descriptor)
        require(stat.S_ISREG(opened_stat.st_mode), "evidence path is not a regular file")
        require((opened_stat.st_dev, opened_stat.st_ino) == (expected_stat.st_dev, expected_stat.st_ino),
                "evidence file changed between inspection and open")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def read_regular_file(path: Path, max_bytes: int, *, expected_stat: os.stat_result | None = None) -> bytes:
    try:
        before = expected_stat or path.lstat()
    except OSError as exc:
        raise EvidenceError("evidence file is unavailable") from exc
    require(stat.S_ISREG(before.st_mode), "evidence path must be a regular non-symlink file")
    require(before.st_size <= max_bytes, "evidence file exceeds the allowed byte limit")
    descriptor = checked_open(path, before)
    try:
        chunks = []
        total = 0
        while True:
            chunk = os.read(descriptor, min(1024 * 1024, max_bytes + 1 - total))
            if not chunk:
                break
            total += len(chunk)
            require(total <= max_bytes, "evidence file exceeds the allowed byte limit")
            chunks.append(chunk)
        return b"".join(chunks)
    finally:
        os.close(descriptor)


def verify_artifacts(evidence_root: Path, manifest: dict) -> list[dict]:
    try:
        root_mode = evidence_root.lstat().st_mode
    except OSError as exc:
        raise EvidenceError("evidence directory is unavailable") from exc
    require(stat.S_ISDIR(root_mode) and not stat.S_ISLNK(root_mode), "evidence root must be a real directory")
    manifest_path = evidence_root / "manifest.json"
    try:
        manifest_mode = manifest_path.lstat().st_mode
    except OSError as exc:
        raise EvidenceError("manifest.json is missing") from exc
    require(stat.S_ISREG(manifest_mode) and not stat.S_ISLNK(manifest_mode), "manifest must be a regular non-symlink file")

    declared = {item["path"]: item for item in manifest["artifacts"]}
    actual = inventory_files(evidence_root) - {"manifest.json"}
    require(actual == set(declared), "bundle files do not exactly match the signed artifact inventory")
    verified = []
    for relative, item in sorted(declared.items()):
        path = evidence_root.joinpath(*PurePosixPath(relative).parts)
        cursor = evidence_root
        artifact_stat = None
        for part in PurePosixPath(relative).parts:
            cursor = cursor / part
            try:
                current_stat = cursor.lstat()
            except OSError as exc:
                raise EvidenceError("declared artifact is missing") from exc
            mode = current_stat.st_mode
            require(not stat.S_ISLNK(mode), "declared artifact path traverses a symlink")
            if cursor != path:
                require(stat.S_ISDIR(mode), "declared artifact parent is not a directory")
            else:
                require(stat.S_ISREG(mode), "declared artifact is not a regular file")
                artifact_stat = current_stat
        require(artifact_stat is not None, "declared artifact could not be inspected")
        require(artifact_stat.st_size == item["size"], "artifact size changed before hashing")
        descriptor = checked_open(path, artifact_stat)
        digest = hashlib.sha256()
        size = 0
        try:
            while True:
                chunk = os.read(descriptor, min(1024 * 1024, item["size"] + 1 - size))
                if not chunk:
                    break
                size += len(chunk)
                require(size <= item["size"], "artifact grew beyond its declared size while hashing")
                digest.update(chunk)
        except OSError as exc:
            raise EvidenceError("declared artifact cannot be read") from exc
        finally:
            os.close(descriptor)
        require(size == item["size"], "artifact byte size does not match the signed inventory")
        actual_hash = digest.hexdigest()
        require(actual_hash == item["sha256"], "artifact SHA-256 does not match the signed inventory")
        verified.append({"path": relative, "size": size, "sha256": actual_hash})
    return verified


def verify_dataset_descriptor(evidence_root: Path, manifest: dict) -> None:
    dataset = manifest["bindings"]["dataset"]
    path = evidence_root.joinpath(*PurePosixPath(dataset["descriptor_artifact"]).parts)
    descriptor_raw = read_regular_file(path, MAX_MANIFEST_BYTES)
    descriptor = load_json(descriptor_raw)
    require(isinstance(descriptor, dict) and set(descriptor) == {
        "schema", "seed_sha256", "generator_version",
    }, "dataset descriptor fields are malformed or unknown")
    require(descriptor["schema"] == "openbank.capacity-dataset-descriptor/v1",
            "dataset descriptor schema is unsupported")
    require(descriptor["seed_sha256"] == dataset["seed_sha256"],
            "dataset seed digest differs from the descriptor")
    require(descriptor["generator_version"] == dataset["generator_version"],
            "dataset generator version differs from the descriptor")


def gh_attestation_command(manifest_path: Path, source_commit: str) -> list[str]:
    return [
        "gh", "attestation", "verify", str(manifest_path),
        "--repo", TRUSTED_REPOSITORY,
        "--signer-workflow", TRUSTED_SIGNER_WORKFLOW,
        "--predicate-type", PREDICATE_TYPE,
        "--cert-oidc-issuer", OIDC_ISSUER,
        "--source-ref", TRUSTED_REF,
        "--source-digest", source_commit,
        "--deny-self-hosted-runners",
        "--format=json",
    ]


def run_command(command: list[str]) -> bytes:
    try:
        result = subprocess.run(command, check=False, capture_output=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise EvidenceError("required GitHub CLI operation could not complete") from exc
    if result.returncode != 0:
        raise EvidenceError("GitHub CLI rejected attestation or API metadata")
    return result.stdout


def invocation_identity(value: object) -> tuple[int, int]:
    require(isinstance(value, str), "attestation has no run invocation identity")
    match = re.fullmatch(
        rf"https://github\.com/{re.escape(TRUSTED_REPOSITORY)}/actions/runs/(\d+)/attempts/(\d+)", value
    )
    require(match is not None, "attestation invocation identity is not a canonical run-attempt URL")
    run_id, attempt = int(match.group(1)), int(match.group(2))
    require(run_id > 0 and attempt > 0, "attestation run identity is invalid")
    return run_id, attempt


def validate_certificate(certificate: dict, source_commit: str) -> tuple[int, int]:
    expected = {
        "issuer": OIDC_ISSUER,
        "sourceRepositoryURI": f"https://github.com/{TRUSTED_REPOSITORY}",
        "sourceRepositoryDigest": source_commit,
        "sourceRepositoryRef": TRUSTED_REF,
        "buildSignerURI": f"https://github.com/{TRUSTED_SIGNER_WORKFLOW}@{TRUSTED_REF}",
        "runnerEnvironment": "github-hosted",
        "buildTrigger": "workflow_dispatch",
        "githubWorkflowRepository": TRUSTED_REPOSITORY,
        "githubWorkflowRef": TRUSTED_REF,
    }
    for field, value in expected.items():
        if value is not None:
            require(certificate.get(field) == value, f"signed certificate {field} is not trusted")
    san = certificate.get("subjectAlternativeName")
    expected_san = f"https://github.com/{TRUSTED_SIGNER_WORKFLOW}@{TRUSTED_REF}"
    require(isinstance(san, dict) and san.get("type") == "URI" and san.get("value") == expected_san,
            "signed certificate SAN is not the trusted workflow identity")
    run_id, attempt = invocation_identity(certificate.get("runInvocationURI"))
    return run_id, attempt


def parse_attestation(raw: bytes, manifest_hash: str, source_commit: str) -> tuple[int, int, int]:
    entries = load_json(raw, limit=8 * 1024 * 1024)
    require(isinstance(entries, list), "gh attestation output must be a JSON array")
    matches = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        result = entry.get("verificationResult")
        if not isinstance(result, dict):
            continue
        statement = result.get("statement")
        if not isinstance(statement, dict) or statement.get("predicateType") != PREDICATE_TYPE:
            continue
        subjects = statement.get("subject")
        if not isinstance(subjects, list):
            continue
        subject_matches = [subject for subject in subjects if isinstance(subject, dict)
                           and isinstance(subject.get("name"), str)
                           and PurePosixPath(subject["name"]).name == "manifest.json"
                           and isinstance(subject.get("digest"), dict)
                           and subject["digest"].get("sha256") == manifest_hash]
        if len(subject_matches) != 1:
            continue
        signature = result.get("signature")
        require(isinstance(signature, dict) and isinstance(signature.get("certificate"), dict),
                "verified attestation certificate details are missing")
        certificate = signature["certificate"]
        cert_run_id, cert_attempt = validate_certificate(certificate, source_commit)
        timestamps = result.get("verifiedTimestamps")
        require(isinstance(timestamps, list) and timestamps, "attestation has no verified timestamp")
        for timestamp in timestamps:
            require(isinstance(timestamp, dict), "verified timestamp entry is malformed")
            parse_time(timestamp.get("timestamp"), "verified attestation timestamp")
        predicate = statement.get("predicate")
        run_details = predicate.get("runDetails") if isinstance(predicate, dict) else None
        metadata = run_details.get("metadata") if isinstance(run_details, dict) else None
        invocation = metadata.get("invocationId") if isinstance(metadata, dict) else None
        predicate_run_id, predicate_attempt = invocation_identity(invocation)
        require((predicate_run_id, predicate_attempt) == (cert_run_id, cert_attempt),
                "workflow-controlled predicate invocation differs from signed certificate run identity")
        matches.append((cert_run_id, cert_attempt, len(timestamps)))
    require(len(matches) == 1, "expected exactly one verified attestation for the manifest subject")
    return matches[0]


def api_run_attempt_command(run_id: int, attempt: int) -> list[str]:
    endpoint = f"repos/{TRUSTED_REPOSITORY}/actions/runs/{run_id}/attempts/{attempt}"
    return ["gh", "api", "--hostname", "github.com", endpoint]


def validate_run_attempt(
    run: object, manifest: dict, attested_run_id: int, attested_attempt: int, now: datetime,
) -> dict:
    require(isinstance(run, dict), "GitHub run-attempt response must be an object")
    source, claimed = manifest["source"], manifest["run"]
    require(type(run.get("id")) is int and run["id"] == attested_run_id == claimed["id"],
            "GitHub run id does not match attested manifest")
    require(type(run.get("run_attempt")) is int and run["run_attempt"] == attested_attempt == claimed["attempt"],
            "GitHub run attempt does not match attested manifest")
    head_repository = run.get("head_repository")
    require(isinstance(head_repository, dict) and head_repository.get("full_name") == TRUSTED_REPOSITORY,
            "GitHub run head repository is not trusted")
    require(run.get("path") == TRUSTED_WORKFLOW_PATH and run.get("head_branch") == "main"
            and run.get("event") == "workflow_dispatch", "GitHub run workflow/ref/event is not trusted")
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            "GitHub run attempt did not complete successfully")
    require(run.get("head_sha") == source["commit"], "GitHub run commit differs from manifest")
    require(source["repository"] == TRUSTED_REPOSITORY and source["ref"] == TRUSTED_REF
            and source["workflow_path"] == TRUSTED_WORKFLOW_PATH, "manifest source identity differs")

    api_started_raw, api_updated_raw = run.get("run_started_at"), run.get("updated_at")
    api_started = parse_time(api_started_raw, "GitHub run_started_at")
    api_updated = parse_time(api_updated_raw, "GitHub updated_at")
    started = parse_time(claimed["started_at"], "run.started_at")
    ended = parse_time(claimed["ended_at"], "run.ended_at")
    observed = parse_time(claimed["observed_at"], "run.observed_at")
    require(started == api_started, "manifest run start does not match GitHub API")
    require(api_started <= ended <= observed, "manifest timestamps precede the actual run")
    require(not_after_reported_time(ended, api_updated_raw, "GitHub updated_at")
            and not_after_reported_time(observed, api_updated_raw, "GitHub updated_at"),
            "manifest timestamp is after the GitHub run attempt")
    require(api_updated >= api_started, "GitHub run update timestamp precedes its start")
    # GitHub API clock tolerance is separate from its timestamp precision interval above.
    require(api_updated - now <= MAX_FUTURE_SKEW, "GitHub run update timestamp is too far in the future")
    require(observed - now <= MAX_FUTURE_SKEW, "manifest observation time is too far in the future")
    return {"id": attested_run_id, "attempt": attested_attempt, "commit": source["commit"]}


def verify_evidence(evidence_dir: Path, repo_root: Path = ROOT, *, now: datetime | None = None) -> dict:
    now = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    profile, profile_bytes = load_profile(repo_root)
    evidence_root = evidence_dir.absolute()
    try:
        root_mode = evidence_root.lstat().st_mode
    except OSError as exc:
        raise EvidenceError("evidence directory is unavailable") from exc
    require(stat.S_ISDIR(root_mode) and not stat.S_ISLNK(root_mode), "evidence root must be a real directory")
    manifest_path = evidence_root / "manifest.json"
    try:
        manifest_stat = manifest_path.lstat()
    except OSError as exc:
        raise EvidenceError("manifest.json is unavailable") from exc
    require(stat.S_ISREG(manifest_stat.st_mode), "manifest must be a regular non-symlink file")
    require(manifest_stat.st_size <= MAX_MANIFEST_BYTES, "manifest exceeds the allowed byte limit")
    manifest_raw = read_regular_file(manifest_path, MAX_MANIFEST_BYTES, expected_stat=manifest_stat)
    manifest = parse_manifest(manifest_raw, profile, profile_bytes)
    verified_artifacts = verify_artifacts(evidence_root, manifest)
    verify_dataset_descriptor(evidence_root, manifest)
    manifest_hash = hashlib.sha256(manifest_raw).hexdigest()

    attestation_raw = run_command(gh_attestation_command(manifest_path, manifest["source"]["commit"]))
    run_id, attempt, timestamp_count = parse_attestation(
        attestation_raw, manifest_hash, manifest["source"]["commit"]
    )
    api_raw = run_command(api_run_attempt_command(run_id, attempt))
    api_run = load_json(api_raw)
    run_identity = validate_run_attempt(api_run, manifest, run_id, attempt, now)

    # External CLI/API calls create a window in which a local evidence bundle could change.
    # Re-read the subject and every artifact before accepting the joined provenance.
    final_manifest_raw = read_regular_file(manifest_path, MAX_MANIFEST_BYTES)
    final_manifest_hash = hashlib.sha256(final_manifest_raw).hexdigest()
    require(final_manifest_hash == manifest_hash, "manifest changed during provenance verification")
    require(verify_artifacts(evidence_root, manifest) == verified_artifacts,
            "artifact inventory changed during provenance verification")
    verify_dataset_descriptor(evidence_root, manifest)

    freshness_days = profile["qualification"]["expires_after_days"]
    require(type(freshness_days) is int and freshness_days > 0, "profile freshness policy is invalid")
    freshness_limit = timedelta(days=min(freshness_days, MAX_PROFILE_FRESHNESS_DAYS))
    observed = parse_time(manifest["run"]["observed_at"], "run.observed_at")
    require(now - observed <= freshness_limit, "capacity evidence is stale")

    pending = profile["qualification"]["pending_decisions"]
    require(isinstance(pending, list), "profile pending decisions are malformed")
    pending_block = profile["qualification"].get("pending_decisions_block_qualification")
    require(pending_block is True, "profile must explicitly block qualification on pending decisions")
    return {
        "schema": "openbank.capacity-evidence-integrity-result/v1",
        "evidence_integrity": "verified",
        "qualification_disposition": "not-evaluated-by-this-tool",
        "capacity_qualified": False,
        "financial_invariants_verified": False,
        "slo_verified": False,
        "scope": manifest["campaign"]["scope"],
        "profile": {
            "id": profile["profile_id"],
            "version": profile["schema_version"],
            "sha256": manifest["profile"]["sha256"],
            "decision": profile["decision"],
            "pending_decisions": len(pending),
            "pending_decisions_block_qualification": pending_block,
        },
        "run": run_identity,
        "attestation": {
            "mechanism": "gh attestation verify",
            "predicate_type": PREDICATE_TYPE,
            "signer_workflow": TRUSTED_SIGNER_WORKFLOW,
            "subject_sha256": manifest_hash,
            "invocation_id_matches_api_attempt": True,
            "verified_timestamp_count": timestamp_count,
            "deny_self_hosted_runners_scope": "signer only; not workload isolation proof",
            "workload_execution_truth": "not-established-by-signature-alone",
        },
        "bindings": manifest["bindings"],
        "artifacts": verified_artifacts,
        "evidence_integrity_only": True,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", required=True, type=Path, help="directory with manifest.json and its exact artifact inventory")
    args = parser.parse_args(argv)
    try:
        result = verify_evidence(args.evidence_dir)
    except EvidenceError as exc:
        result = {
            "schema": "openbank.capacity-evidence-integrity-result/v1",
            "evidence_integrity": "failed",
            "qualification_disposition": "not-evaluated-by-this-tool",
            "capacity_qualified": False,
            "financial_invariants_verified": False,
            "slo_verified": False,
            "evidence_integrity_only": True,
            "errors": [str(exc)],
        }
        print(json.dumps(result, sort_keys=True))
        print(f"capacity evidence integrity verification failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
