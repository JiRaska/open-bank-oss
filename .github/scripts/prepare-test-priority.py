#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prepare context-service class-order hints from one trusted main CI envelope.

This never selects or excludes tests. Missing, stale or invalid evidence leaves no
manifest and exits successfully so the full suite keeps its default order. Reuses
the existing bounded artifact finder, safe ZIP reader and run/attempt validator;
never stages downloaded files into the checkout.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("priority_evidence", HERE / "collect-admin-ui-test-evidence.py")
assert SPEC and SPEC.loader
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)
FINDER = EVIDENCE.FINDER
COMPONENT = "openbank-context-service"
MAX_AGE = timedelta(days=7)
FUTURE_SKEW = timedelta(minutes=5)
MAX_RUN_LOOKUPS = 10
MAX_CASES = 20_000
MAX_CLASSES = 2_000
MAX_MANIFEST_BYTES = 256 * 1024
MAX_DURATION_MS = 2**53 - 1
CLASS_NAME = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\Z")


class NoEvidence(ValueError):
    """A safe fallback reason that contains no downloaded metadata."""


def timestamp(value):
    return EVIDENCE._timestamp(value)


def encode_manifest(value):
    encoded = (json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")
    if len(encoded) > MAX_MANIFEST_BYTES:
        raise NoEvidence("class-order manifest exceeds its byte limit")
    return encoded


def manifest(envelope, component, now):
    observed = envelope.get("run", {}).get("observedAt")
    moment = timestamp(observed)
    if moment is None or now - moment > MAX_AGE or moment - now > FUTURE_SKEW:
        raise NoEvidence("source observation is missing, stale or future-dated")
    cases = envelope.get("testCases")
    if not isinstance(cases, list) or not cases or len(cases) > MAX_CASES:
        raise NoEvidence("no bounded test-case history available")
    classes = {}
    for case in cases:
        if not isinstance(case, dict):
            raise NoEvidence("test-case history is malformed")
        name, duration, state = case.get("classname"), case.get("durationMs"), case.get("state")
        if (not isinstance(name, str) or len(name) > 512 or CLASS_NAME.fullmatch(name) is None
                or type(duration) is not int or not 0 <= duration <= MAX_DURATION_MS
                or state not in {"passed", "failed", "skipped"}):
            raise NoEvidence("test-case class, duration or outcome is invalid")
        if state == "skipped":
            continue
        row = classes.setdefault(name, {"failed": False, "duration": 0})
        row["failed"] |= state == "failed" or case.get("retryFlaky") is True
        row["duration"] += duration
        if row["duration"] > MAX_DURATION_MS or len(classes) > MAX_CLASSES:
            raise NoEvidence("class history exceeds its bounded limits")
    if not classes:
        raise NoEvidence("history contains no executed classes")
    ordered = sorted(classes, key=lambda name: (not classes[name]["failed"], classes[name]["duration"], name))
    result = {"schemaVersion": 1, "component": component, "generatedAt": observed, "classes": ordered}
    encode_manifest(result)
    return result


def prepare(repo, component, token, *, now=None, fetch_page=None, fetch_run=None,
            fetch_attempt=None, fetch_archive=None):
    if component != COMPONENT:
        raise NoEvidence("only context-service is enabled for this pilot")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise NoEvidence("repository identity is invalid")
    if not token:
        raise NoEvidence("artifact read token is unavailable")
    now = now or datetime.now(timezone.utc)
    artifact_name = f"test-intelligence-{component}"
    artifacts, runs = {}, {}
    root = f"{EVIDENCE.API_ORIGIN}/repos/{repo}/actions"

    def page(name, number):
        values = (fetch_page(name, number) if fetch_page else
                  FINDER.github_page(repo, token, name, number))
        if not isinstance(values, list) or len(values) > FINDER.PER_PAGE:
            raise NoEvidence("artifact inventory is malformed")
        for item in values:
            if (not isinstance(item, dict) or type(item.get("id")) is not int or item["id"] < 1
                    or item.get("name") != artifact_name or not isinstance(item.get("workflow_run"), dict)
                    or type(item.get("expired")) is not bool):
                raise NoEvidence("artifact metadata is malformed")
            if str(item["id"]) in artifacts:
                raise NoEvidence("artifact inventory contains duplicate identities")
            artifacts[str(item["id"])] = item
        return values

    def run(run_id):
        if type(run_id) is not int or run_id < 1:
            raise NoEvidence("producer run identity is invalid")
        if run_id not in runs:
            if len(runs) >= MAX_RUN_LOOKUPS:
                raise NoEvidence("producer lookup budget exhausted")
            value = fetch_run(run_id) if fetch_run else EVIDENCE.api_json(f"{root}/runs/{run_id}", token)
            if not isinstance(value, dict) or value.get("id") != run_id:
                raise NoEvidence("producer run identity mismatch")
            runs[run_id] = value
        value = runs[run_id]
        repository = value.get("head_repository")
        return {**value, "head_repository": repository.get("full_name") if isinstance(repository, dict) else repository}

    artifact_id = FINDER.latest_main_artifact(artifact_name, page, run, repo)
    if artifact_id is None:
        raise NoEvidence("no retained trusted main envelope")
    metadata = artifacts[artifact_id]
    trusted = run(metadata["workflow_run"]["id"])
    producer = FINDER.producer_of(artifact_name)
    created = timestamp(metadata.get("created_at"))
    if (not EVIDENCE.trusted_run(trusted, repo, producer) or trusted.get("status") != "completed"
            or type(trusted.get("run_attempt")) is not int or trusted["run_attempt"] < 1
            or created is None or now - created > MAX_AGE or created - now > FUTURE_SKEW):
        raise NoEvidence("producer provenance or artifact freshness is invalid")
    artifact_sha = metadata["workflow_run"].get("head_sha")
    if artifact_sha is not None and artifact_sha != trusted["head_sha"]:
        raise NoEvidence("artifact and producer commit identities differ")
    archive = (fetch_archive(int(artifact_id)) if fetch_archive else
               EVIDENCE.api_bytes(f"{root}/artifacts/{artifact_id}/zip", token))
    if not isinstance(archive, bytes) or len(archive) > EVIDENCE.MAX_ARCHIVE_BYTES:
        raise NoEvidence("archive exceeds its bounded size")
    attempts = {}

    def attempt(run_id, number):
        if attempts:
            raise NoEvidence("unexpected additional attempt lookup")
        value = (fetch_attempt(run_id, number) if fetch_attempt else
                 EVIDENCE.api_json(f"{root}/runs/{run_id}/attempts/{number}", token))
        if not isinstance(value, dict) or value.get("status") != "completed":
            raise NoEvidence("exact producer attempt is unavailable or incomplete")
        attempts[(run_id, number)] = value
        return value

    payloads = EVIDENCE.artifact_payload(
        archive, component, trusted, repo_slug=repo, expected_path=producer,
        resolve_attempt=attempt, artifact_created_at=metadata["created_at"],
    )
    envelope = json.loads(payloads[f"{component}/build/test-intelligence/run.json"])
    claimed = envelope["run"]["attempt"]
    producer_attempt = trusted if claimed == trusted["run_attempt"] else attempts[(trusted["id"], claimed)]
    started, observed = timestamp(producer_attempt.get("run_started_at")), timestamp(envelope["run"].get("observedAt"))
    if started is None or observed is None or observed < started:
        raise NoEvidence("observation predates its producer attempt")
    return manifest(envelope, component, now)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--component", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    temporary = None
    try:
        # Never leave yesterday's priority file in place on a failed refresh.
        args.out.unlink(missing_ok=True)
        result = prepare(args.repo, args.component, os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN", ""))
        encoded = encode_manifest(result)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="wb", dir=args.out.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(encoded)
        temporary.replace(args.out)
        print(f'test-priority: {len(result["classes"])} class-order hints; full test discovery unchanged', file=sys.stderr)
    except NoEvidence as error:
        print(f"test-priority: no priority evidence: {error}; using default full-suite order", file=sys.stderr)
    except (EVIDENCE.CollectionError, FINDER.ArtifactApiError, OSError, ValueError,
            TypeError, KeyError, AttributeError, zipfile.BadZipFile):
        # Shared archive errors may quote untrusted filenames; never echo their payload.
        print("test-priority: no priority evidence: trusted evidence unavailable or malformed; using default full-suite order", file=sys.stderr)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
