#!/usr/bin/env python3
"""Fetch the newest successful trusted-main Security KPI artifact without recomputing it."""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

API_ORIGIN = "https://api.github.com"
WORKFLOW_PATH = ".github/workflows/security-kpis.yml"
ARTIFACT_PREFIX = "security-kpis-"
MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
MAX_FILE_BYTES = 2 * 1024 * 1024


class CollectionError(RuntimeError):
    pass


class StripAuthorizationOnCrossHostRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and req.host != redirected.host:
            redirected.remove_header("Authorization")
            redirected.remove_header("authorization")
        return redirected


def api_url(url: str) -> None:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname != "api.github.com" or parsed.port is not None or parsed.username or parsed.password:
        raise CollectionError("refusing a GitHub API request outside api.github.com")


def api_json(url: str, token: str) -> dict:
    api_url(url)
    request = Request(url, headers={"Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}",
                                   "X-GitHub-Api-Version": "2022-11-28"})
    try:
        with build_opener(StripAuthorizationOnCrossHostRedirect).open(request, timeout=30) as response:
            value = json.load(response)
    except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
        status = f"HTTP {exc.code}" if isinstance(exc, HTTPError) else type(exc).__name__
        raise CollectionError(f"GitHub security KPI API request failed ({status})") from exc
    if not isinstance(value, dict):
        raise CollectionError("GitHub security KPI API returned a non-object response")
    return value


def api_bytes(url: str, token: str) -> bytes:
    api_url(url)
    request = Request(url, headers={"Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}",
                                   "X-GitHub-Api-Version": "2022-11-28"})
    try:
        with build_opener(StripAuthorizationOnCrossHostRedirect).open(request, timeout=60) as response:
            content = response.read(MAX_ARCHIVE_BYTES + 1)
    except (HTTPError, URLError, TimeoutError) as exc:
        status = f"HTTP {exc.code}" if isinstance(exc, HTTPError) else type(exc).__name__
        raise CollectionError(f"GitHub security KPI artifact download failed ({status})") from exc
    if len(content) > MAX_ARCHIVE_BYTES:
        raise CollectionError("security KPI artifact archive exceeds size limit")
    return content


def trusted_run(run: dict, repo: str) -> bool:
    repository = run.get("head_repository")
    repository_name = repository.get("full_name") if isinstance(repository, dict) else repository
    return (run.get("path") == WORKFLOW_PATH and run.get("event") in {"schedule", "workflow_dispatch", "push"}
            and run.get("head_branch") == "main" and repository_name == repo
            and isinstance(run.get("head_sha"), str) and re.fullmatch(r"[0-9a-f]{40}", run["head_sha"]) is not None
            and run.get("conclusion") == "success" and isinstance(run.get("id"), int) and run["id"] > 0
            and isinstance(run.get("run_attempt"), int) and run["run_attempt"] > 0)


def extract_snapshot(archive: bytes) -> bytes:
    try:
        with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
            entries = zipped.infolist()
            if len(entries) > 100:
                raise CollectionError("security KPI artifact contains too many files")
            selected = []
            for info in entries:
                name = info.filename
                path = PurePosixPath(name)
                mode = info.external_attr >> 16
                if (not name or "\\" in name or path.is_absolute() or ".." in path.parts
                        or mode & 0o170000 == 0o120000 or info.flag_bits & 0x1):
                    raise CollectionError("security KPI artifact contains an unsafe ZIP member")
                if info.file_size > MAX_FILE_BYTES:
                    raise CollectionError("security KPI artifact file exceeds size limit")
                if not info.is_dir():
                    if path.parts != ("security-kpis.json",):
                        raise CollectionError("security KPI artifact contains an unexpected file")
                    selected.append(info)
            if len(selected) != 1:
                raise CollectionError(f"expected exactly one root security-kpis.json, found {len(selected)}")
            raw = zipped.read(selected[0])
    except zipfile.BadZipFile as exc:
        raise CollectionError("security KPI artifact is not a valid ZIP") from exc
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise CollectionError("security KPI artifact contains invalid JSON") from exc
    if not isinstance(parsed, dict) or not isinstance(parsed.get("generatedAt"), str):
        raise CollectionError("security KPI artifact has no original generatedAt timestamp")
    return raw


def collect(repo: str, token: str, output: Path, metadata_output: Path, *, fetch_json=None, fetch_bytes=None) -> dict:
    if not token:
        raise CollectionError("GITHUB_TOKEN is required to read the trusted workflow artifact")
    get_json = fetch_json or (lambda url: api_json(url, token))
    get_bytes = fetch_bytes or (lambda url: api_bytes(url, token))
    query = urlencode({"branch": "main", "status": "success", "per_page": 100})
    runs = get_json(f"{API_ORIGIN}/repos/{repo}/actions/workflows/security-kpis.yml/runs?{query}").get("workflow_runs")
    if not isinstance(runs, list):
        raise CollectionError("Security KPI workflow response has no workflow_runs list")
    candidates = sorted((run for run in runs if isinstance(run, dict)),
                        key=lambda run: (run.get("run_started_at") or run.get("created_at") or "", run.get("id", 0)), reverse=True)
    selected = None
    for candidate in candidates:
        run_id = candidate.get("id")
        if not isinstance(run_id, int) or run_id <= 0:
            continue
        details = get_json(f"{API_ORIGIN}/repos/{repo}/actions/runs/{run_id}")
        if details.get("id") == run_id and trusted_run(details, repo):
            selected = details
            break
    if selected is None:
        raise CollectionError("no successful same-repository main Security KPI run was found")

    run_id = selected["id"]
    attempt = selected["run_attempt"]
    artifact_name = f"{ARTIFACT_PREFIX}{run_id}-{attempt}"
    artifacts = get_json(f"{API_ORIGIN}/repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100").get("artifacts")
    if not isinstance(artifacts, list):
        raise CollectionError("Security KPI artifact inventory has no artifacts list")
    matches = [artifact for artifact in artifacts if isinstance(artifact, dict) and artifact.get("name") == artifact_name]
    if len(matches) != 1 or matches[0].get("expired") is not False:
        raise CollectionError("latest trusted Security KPI run does not have exactly one retained snapshot artifact")
    workflow_run = matches[0].get("workflow_run")
    if not isinstance(workflow_run, dict) or workflow_run.get("id") != run_id:
        raise CollectionError("Security KPI artifact run identity does not match the trusted workflow run")
    artifact_id = matches[0].get("id")
    if not isinstance(artifact_id, int) or artifact_id <= 0:
        raise CollectionError("Security KPI artifact has no valid id")
    raw = extract_snapshot(get_bytes(f"{API_ORIGIN}/repos/{repo}/actions/artifacts/{artifact_id}/zip"))
    snapshot = json.loads(raw)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(raw)
    metadata = {"runId": run_id, "runAttempt": attempt, "sourceSha": selected["head_sha"],
                "collectedAt": snapshot["generatedAt"], "artifactId": artifact_id}
    metadata_output.parent.mkdir(parents=True, exist_ok=True)
    metadata_output.write_text(json.dumps(metadata) + "\n", encoding="utf-8")
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--metadata-out", type=Path, required=True)
    parser.add_argument("--repo-slug", default=os.environ.get("GITHUB_REPOSITORY"))
    args = parser.parse_args()
    try:
        if not args.repo_slug:
            raise CollectionError("--repo-slug or GITHUB_REPOSITORY is required")
        result = collect(args.repo_slug, os.environ.get("GITHUB_TOKEN", ""), args.out, args.metadata_out)
        print(json.dumps(result, sort_keys=True))
    except (CollectionError, OSError, ValueError) as exc:
        print(f"Security KPI collection failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
