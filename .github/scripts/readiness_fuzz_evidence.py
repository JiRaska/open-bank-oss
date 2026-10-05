# SPDX-License-Identifier: Apache-2.0
"""Verify a CI pentest count against its exact api-fuzz run artifact (#9673)."""

from __future__ import annotations

import datetime as dt
import io
import json
import os
import re
import subprocess
import zipfile
from collections.abc import Callable

MAX_AGE_DAYS = 21
MAX_ARCHIVE_BYTES = 50_000_000
MAX_RECORD_BYTES = 100_000


class EvidenceError(ValueError):
    """The cited run does not prove the claimed exercised-operation count."""


def github_api(path: str) -> bytes:
    """Use gh's credential-safe artifact redirect handling; never surface API bodies."""
    if not (os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")):
        raise EvidenceError("GH_TOKEN with actions:read is required to verify fuzz evidence")
    try:
        result = subprocess.run(
            ["gh", "api", path], capture_output=True, timeout=60, check=False,
            env={**os.environ, "GH_TOKEN": os.environ.get("GH_TOKEN") or os.environ["GITHUB_TOKEN"]},
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise EvidenceError(f"GitHub evidence lookup failed ({type(exc).__name__})") from None
    if result.returncode or not result.stdout:
        raise EvidenceError("GitHub evidence lookup failed or artifact is unavailable")
    return result.stdout


def verify_fuzz_ops(
    *,
    service: str,
    ops: int,
    attested: dt.date,
    ttl_days: int,
    today: dt.date,
    ref: str,
    repository: str,
    fetch: Callable[[str], bytes] = github_api,
) -> None:
    """Reject stale, wrong-run, failed, missing, and mismatched coverage evidence."""
    if ttl_days > MAX_AGE_DAYS:
        raise EvidenceError(f"CI fuzz attestation ttl_days exceeds {MAX_AGE_DAYS}-day evidence limit")
    prefix = f"https://github.com/{repository}/actions/runs/"
    match = re.fullmatch(re.escape(prefix) + r"([1-9][0-9]*)/?", ref)
    if not match:
        raise EvidenceError("CI fuzz ref must name an exact run in this repository")
    run_id = match.group(1)
    try:
        run = json.loads(fetch(f"repos/{repository}/actions/runs/{run_id}"))
        started = dt.date.fromisoformat(run["created_at"][:10])
    except EvidenceError:
        raise
    except (ValueError, KeyError, TypeError) as exc:
        raise EvidenceError(f"GitHub run metadata is invalid ({type(exc).__name__})") from None
    if run.get("id") != int(run_id) or run.get("path") != ".github/workflows/api-fuzz.yml":
        raise EvidenceError("cited run is not the api-fuzz workflow run")
    if run.get("head_branch") != "main" or run.get("event") not in ("schedule", "workflow_dispatch"):
        raise EvidenceError("cited fuzz run did not execute on the reviewed main branch")
    if run.get("status") != "completed" or run.get("conclusion") not in ("success", "failure"):
        raise EvidenceError("cited api-fuzz run did not complete")
    if started != attested:
        raise EvidenceError("attestation date does not match the cited run date")
    if (today - started).days < 0 or (today - started).days > MAX_AGE_DAYS:
        raise EvidenceError(f"cited fuzz measurement exceeds {MAX_AGE_DAYS}-day max age")

    try:
        jobs = json.loads(fetch(f"repos/{repository}/actions/runs/{run_id}/jobs?per_page=100"))
        if jobs.get("total_count", 0) > 100:
            raise EvidenceError("cited run has too many jobs to verify completely")
        service_job = f"schemathesis (openbank-{service}-service)"
        matching_jobs = [job for job in jobs["jobs"] if job.get("name") == service_job]
        if len(matching_jobs) != 1 or matching_jobs[0].get("conclusion") != "success":
            raise EvidenceError("cited service fuzz job did not succeed")
        listing = json.loads(fetch(f"repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100"))
        artifact_name = f"api-fuzz-reports-openbank-{service}-service"
        matches = [item for item in listing["artifacts"] if item.get("name") == artifact_name]
        if len(matches) != 1 or matches[0].get("expired"):
            raise EvidenceError("exact service fuzz artifact is absent or expired")
        artifact = matches[0]
        if not 0 < artifact["size_in_bytes"] <= MAX_ARCHIVE_BYTES:
            raise EvidenceError("service fuzz artifact exceeds verification size limit")
        archive = fetch(f"repos/{repository}/actions/artifacts/{artifact['id']}/zip")
        if len(archive) > MAX_ARCHIVE_BYTES:
            raise EvidenceError("downloaded service fuzz artifact exceeds verification size limit")
        with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
            name = f"openbank-{service}-service-ops.json"
            info = zipped.getinfo(name)
            if info.file_size > MAX_RECORD_BYTES:
                raise EvidenceError("service fuzz record exceeds verification size limit")
            record = json.loads(zipped.read(info))
    except EvidenceError:
        raise
    except (ValueError, KeyError, TypeError, zipfile.BadZipFile) as exc:
        raise EvidenceError(f"service fuzz artifact is invalid ({type(exc).__name__})") from None

    full_service = f"openbank-{service}-service"
    if (record.get("service"), record.get("lane"), record.get("run"), record.get("date")) != (
        full_service, "authz ON", ref.rstrip("/"), started.isoformat(),
    ):
        raise EvidenceError("service fuzz record belongs to a different service, lane, run, or date")
    selected, blocked, exercised = (record.get(key) for key in ("selected", "auth_blocked", "exercised"))
    if any(type(value) is not int for value in (selected, blocked, exercised)):
        raise EvidenceError("service fuzz record counts are not integers")
    if selected < 0 or blocked < 0 or blocked > selected or exercised != selected - blocked:
        raise EvidenceError("service fuzz exercised count is inconsistent")
    if ops != exercised:
        raise EvidenceError(f"attested ops={ops} differs from measured exercised={exercised}")
