#!/usr/bin/env python3
"""Build a current Test Intelligence aggregate from trusted main CI artifacts.

This stages per-component run envelopes, JUnit and Kover data into a clean checkout,
runs the existing JS collector, then carries only cross-workflow observations not
restaged here from the latest trusted Admin UI snapshot. Missing optional sources are
called out in the report; API failures and malformed present artifacts abort instead
of looking absent.
"""

from __future__ import annotations

import argparse
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

HERE = Path(__file__).resolve().parent
FINDER_SPEC = importlib.util.spec_from_file_location("latest_main_artifacts", HERE / "find-latest-main-artifacts.py")
if not FINDER_SPEC or not FINDER_SPEC.loader:
    raise RuntimeError("latest-main-artifacts helper is unavailable")
FINDER = importlib.util.module_from_spec(FINDER_SPEC)
FINDER_SPEC.loader.exec_module(FINDER)
ENVELOPE_SPEC = importlib.util.spec_from_file_location("test_intelligence_envelope", HERE / "extract-test-intelligence-envelope.py")
if not ENVELOPE_SPEC or not ENVELOPE_SPEC.loader:
    raise RuntimeError("Test Intelligence envelope validator is unavailable")
ENVELOPES = importlib.util.module_from_spec(ENVELOPE_SPEC)
ENVELOPE_SPEC.loader.exec_module(ENVELOPES)

API_ORIGIN = "https://api.github.com"
PER_PAGE = 100
MAX_ARCHIVE_MEMBERS = 20_000
MAX_ARCHIVE_BYTES = 200 * 1024 * 1024
MAX_MEMBER_BYTES = 64 * 1024 * 1024
SNAPSHOT_PREFIX = "test-intelligence-snapshot-"
SEEDED_LIST_FIELDS = ("mutations", "performance", "performanceHistory", "history", "runHistory", "testCases")
COMPONENT = re.compile(r"openbank-[a-z0-9]+(?:-[a-z0-9]+)*\Z")
SHA = re.compile(r"[0-9a-f]{7,40}\Z")


class CollectionError(RuntimeError):
    pass


SEED_UNSET = object()


class StripAuthorizationOnCrossHostRedirect(HTTPRedirectHandler):
    """Do not forward the GitHub API token to artifact-storage redirect hosts."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and req.host != redirected.host:
            redirected.remove_header("Authorization")
            redirected.remove_header("authorization")
        return redirected


def require_github_api_url(url: str) -> None:
    parsed = urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname != "api.github.com" or parsed.port is not None
            or parsed.username is not None or parsed.password is not None):
        raise CollectionError("refusing a GitHub API request outside api.github.com")


def api_json(url: str, token: str) -> dict:
    require_github_api_url(url)
    request = Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        opener = build_opener(StripAuthorizationOnCrossHostRedirect)
        with opener.open(request, timeout=30) as response:  # noqa: S310 - origin is fixed above
            value = json.load(response)
    except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
        status = f"HTTP {exc.code}" if isinstance(exc, HTTPError) else type(exc).__name__
        raise CollectionError(f"GitHub evidence API request failed ({status})") from exc
    if not isinstance(value, dict):
        raise CollectionError("GitHub evidence API returned a non-object response")
    return value


def repo_components(repo: Path) -> list[str]:
    components = sorted(
        path.parent.name for path in repo.glob("openbank-*/version.txt")
        if COMPONENT.fullmatch(path.parent.name)
    )
    if (repo / "openbank-simulation").is_dir():
        components.append("openbank-simulation")
    if not components:
        raise CollectionError("no released service version.txt files were found")
    return sorted(set(components))


def trusted_run(run: dict, repo_slug: str, expected_path: str) -> bool:
    repository = run.get("head_repository")
    repository_name = repository.get("full_name") if isinstance(repository, dict) else repository
    return (
        run.get("event") == "push"
        and run.get("head_branch") == "main"
        and run.get("path") == expected_path
        and repository_name == repo_slug
        and isinstance(run.get("head_sha"), str)
        and re.fullmatch(r"[0-9a-f]{40}", run["head_sha"]) is not None
    )


def trusted_deploy_run(run: dict, repo_slug: str) -> bool:
    repository = run.get("head_repository")
    repository_name = repository.get("full_name") if isinstance(repository, dict) else repository
    return (
        run.get("event") in {"push", "workflow_run", "schedule", "workflow_dispatch"}
        and run.get("head_branch") == "main"
        and run.get("path") == ".github/workflows/admin-ui-deploy.yml"
        and repository_name == repo_slug
        and isinstance(run.get("head_sha"), str)
        and re.fullmatch(r"[0-9a-f]{40}", run["head_sha"]) is not None
    )


def safe_archive_members(archive: bytes) -> list[tuple[zipfile.ZipInfo, PurePosixPath]]:
    try:
        zipped = zipfile.ZipFile(io.BytesIO(archive))
    except zipfile.BadZipFile as exc:
        raise CollectionError("artifact archive is corrupt") from exc
    with zipped:
        entries = zipped.infolist()
        if len(entries) > MAX_ARCHIVE_MEMBERS:
            raise CollectionError("artifact archive contains too many members")
        total = 0
        seen: set[str] = set()
        selected: list[tuple[zipfile.ZipInfo, PurePosixPath]] = []
        for info in entries:
            name = info.filename
            path = PurePosixPath(name)
            if (not name or "\\" in name or path.is_absolute() or ".." in path.parts
                    or not path.parts or path.parts[0] in {"", "."}):
                raise CollectionError(f"unsafe artifact archive path: {name!r}")
            if name in seen:
                raise CollectionError(f"duplicate artifact archive member: {name!r}")
            seen.add(name)
            mode = info.external_attr >> 16
            if mode & 0o170000 == 0o120000:
                raise CollectionError(f"artifact archive contains a symlink: {name!r}")
            if info.flag_bits & 0x1:
                raise CollectionError("encrypted artifact archives are not supported")
            if info.file_size > MAX_MEMBER_BYTES:
                raise CollectionError(f"artifact archive member exceeds size limit: {name!r}")
            total += info.file_size
            if total > MAX_ARCHIVE_BYTES:
                raise CollectionError("artifact archive exceeds expanded size limit")
            if not info.is_dir():
                selected.append((info, path))
        return selected


def artifact_payload(archive: bytes, component: str, run: dict) -> dict[str, bytes]:
    entries = safe_archive_members(archive)
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        envelope_entries = [(info, path) for info, path in entries if path.name == "run.json"]
        if len(envelope_entries) != 1:
            raise CollectionError(f"{component}: expected exactly one run.json, found {len(envelope_entries)}")
        envelope_info, _ = envelope_entries[0]
        raw = zipped.read(envelope_info)
        try:
            envelope = json.loads(raw)
            ENVELOPES.validate(raw)
        except (ValueError, json.JSONDecodeError, TypeError, KeyError) as exc:
            raise CollectionError(f"{component}: run envelope is malformed or incomplete") from exc
        if envelope.get("component") != component:
            raise CollectionError(f"{component}: envelope component identity mismatch")
        evidence_run = envelope["run"]
        if (evidence_run.get("branch") != "main" or str(evidence_run.get("id")) != str(run.get("id"))
                or evidence_run.get("attempt") != run.get("run_attempt")
                or evidence_run.get("commit") != run.get("head_sha")):
            raise CollectionError(f"{component}: envelope run identity does not match trusted GitHub run metadata")

        staged = {f"{component}/build/test-intelligence/run.json": raw}
        junit_count = 0
        kover_count = 0
        for info, path in entries:
            parts = path.parts
            if path.name.startswith("TEST-") and path.name.endswith(".xml") and "test-results" in parts:
                index = parts.index("test-results")
                relative = parts[index + 1:]
                if not relative or any(part in {".", ".."} for part in relative):
                    continue
                staged[f"{component}/build/test-results/{'/'.join(relative)}"] = zipped.read(info)
                junit_count += 1
            elif parts[-3:] == ("reports", "kover", "report.xml"):
                staged[f"{component}/build/reports/kover/report.xml"] = zipped.read(info)
                kover_count += 1
        if junit_count > 0 and not any(b"<testsuite" in value or b"<testsuites" in value
                                      for key, value in staged.items() if "/test-results/" in key):
            raise CollectionError(f"{component}: staged JUnit artifacts contain no test suite XML")
        if kover_count > 1:
            raise CollectionError(f"{component}: artifact contains duplicate Kover reports")
        return staged


def _newer(old: dict, current: dict, field: str = "observedAt") -> dict:
    def value(row: dict) -> str:
        result = row
        for part in field.split("."):
            result = result.get(part) if isinstance(result, dict) else None
        return result if isinstance(result, str) else ""
    old_at = value(old)
    current_at = value(current)
    return current if current_at >= old_at else old


def merge_ancillary(core: dict, seed: dict | None) -> dict:
    if seed is None:
        core.setdefault("warnings", []).append("No retained trusted Admin UI aggregate was available; ancillary sources are unknown.")
        return core
    if seed.get("schemaVersion") != 1 or not isinstance(seed.get("collectedAt"), str):
        raise CollectionError("retained aggregate snapshot has an unsupported schema")
    for field in SEEDED_LIST_FIELDS:
        old_rows = seed.get(field)
        current_rows = core.get(field)
        if not isinstance(old_rows, list):
            continue
        if not isinstance(current_rows, list) or not current_rows:
            core[field] = old_rows
            continue
        if field == "history":
            keyed = {row.get("collectedAt"): row for row in old_rows if isinstance(row, dict) and row.get("collectedAt")}
            keyed.update({row.get("collectedAt"): row for row in current_rows if isinstance(row, dict) and row.get("collectedAt")})
            core[field] = sorted(keyed.values(), key=lambda row: row["collectedAt"])[-30:]
        elif field == "runHistory":
            keyed = {}
            for row in [*old_rows, *current_rows]:
                run = row.get("run") if isinstance(row, dict) else None
                if not isinstance(run, dict):
                    continue
                key = (row.get("component"), str(run.get("id")), str(run.get("attempt")))
                keyed[key] = _newer(keyed[key], row, "run.observedAt") if key in keyed else row
            core[field] = sorted(keyed.values(), key=lambda row: row.get("run", {}).get("observedAt", ""), reverse=True)[:500]
        elif field == "testCases":
            keyed = {row["fingerprint"]: row for row in old_rows if isinstance(row, dict) and row.get("fingerprint")}
            for row in current_rows:
                if not isinstance(row, dict) or not row.get("fingerprint"):
                    continue
                old = keyed.get(row["fingerprint"])
                keyed[row["fingerprint"]] = _newer(old, row, "lastObservedAt") if old else row
            core[field] = sorted(keyed.values(), key=lambda row: row.get("lastObservedAt", ""), reverse=True)
        elif field == "performanceHistory":
            keyed = {f"{row.get('id')}:{row.get('collectedAt')}": row for row in old_rows if isinstance(row, dict)}
            keyed.update({f"{row.get('id')}:{row.get('collectedAt')}": row for row in current_rows if isinstance(row, dict)})
            core[field] = sorted(keyed.values(), key=lambda row: (row.get("id", ""), row.get("collectedAt", "")))
        else:
            keyed = {row.get("component", row.get("id")): row for row in old_rows if isinstance(row, dict)}
            for row in current_rows:
                if not isinstance(row, dict):
                    continue
                key = row.get("component", row.get("id"))
                old = keyed.get(key)
                keyed[key] = _newer(old, row) if old else row
            core[field] = list(keyed.values())

    # The current checkout supplies the authoritative catalogue and controls. Carry only
    # a newer immutable CI verdict for the same declared journey; never copy old definitions.
    old_journeys = {row.get("id"): row for row in seed.get("syntheticJourneys", []) if isinstance(row, dict)}
    for journey in core.get("syntheticJourneys", []):
        old = old_journeys.get(journey.get("id")) if isinstance(journey, dict) else None
        old_ci, current_ci = old.get("ci") if old else None, journey.get("ci") if isinstance(journey, dict) else None
        if isinstance(old_ci, dict) and (not isinstance(current_ci, dict)
                or (old_ci.get("observedAt") or "") > (current_ci.get("observedAt") or "")):
            journey["ci"] = old_ci

    # Retain prior client evidence only for surfaces where this collection has no new run.
    old_clients = {row.get("id"): row for row in seed.get("clientExperiences", []) if isinstance(row, dict)}
    for client in core.get("clientExperiences", []):
        old = old_clients.get(client.get("id")) if isinstance(client, dict) else None
        if old and not client.get("evidence") and old.get("evidence"):
            client["evidence"] = old["evidence"]
        if old and isinstance(old.get("rum"), dict) and isinstance(client.get("rum"), dict):
            for key in ("observedAt", "source", "sampledSpansLast7d", "errorSpansLast7d"):
                if client["rum"].get(key) is None and old["rum"].get(key) is not None:
                    client["rum"][key] = old["rum"][key]

    # Preserve broker verdicts only for the same committed Pact version. New or changed
    # Pacts keep the fresh collector's pending state rather than inheriting another version's pass.
    old_contracts = {(row.get("pactFile"), row.get("consumer"), row.get("provider"), row.get("consumerVersion")): row
                     for row in seed.get("contracts", []) if isinstance(row, dict)}
    for contract in core.get("contracts", []):
        key = (contract.get("pactFile"), contract.get("consumer"), contract.get("provider"), contract.get("consumerVersion"))
        old = old_contracts.get(key)
        if old and contract.get("state") in {"pending", "unknown", "not-run"}:
            for field in ("state", "providerVersion", "observedAt", "interactions", "verificationDetail", "unavailableReason"):
                if field in old:
                    contract[field] = old[field]
    core.setdefault("warnings", []).append(
        f"Cross-workflow history and observations were reused from trusted aggregate collected at {seed['collectedAt']}; current repository catalogues and service component posture were recollected."
    )
    return core


def latest_aggregate(repo_slug: str, token: str) -> dict | None:
    """Return the latest safely published aggregate, or None when the source is absent."""
    workflow_url = f"{API_ORIGIN}/repos/{repo_slug}/actions/workflows/admin-ui-deploy.yml/runs?branch=main&status=success&per_page={PER_PAGE}"
    runs = api_json(workflow_url, token).get("workflow_runs")
    if not isinstance(runs, list):
        raise CollectionError("Admin UI deploy workflow response has no workflow_runs list")
    ordered = sorted((run for run in runs if isinstance(run, dict)),
                     key=lambda run: (run.get("run_started_at") or run.get("created_at") or "", run.get("id", 0)), reverse=True)
    for run in ordered[:PER_PAGE]:
        run_id = run.get("id")
        if not isinstance(run_id, int) or run_id <= 0:
            continue
        run_details = api_json(f"{API_ORIGIN}/repos/{repo_slug}/actions/runs/{run_id}", token)
        if not trusted_deploy_run(run_details, repo_slug) or run_details.get("conclusion") != "success":
            continue
        inventory_url = f"{API_ORIGIN}/repos/{repo_slug}/actions/runs/{run_id}/artifacts?per_page={PER_PAGE}"
        artifacts = api_json(inventory_url, token).get("artifacts")
        if not isinstance(artifacts, list):
            raise CollectionError("Admin UI deploy artifact response has no artifacts list")
        expected_name = f"{SNAPSHOT_PREFIX}{run_id}-{run.get('run_attempt')}"
        matching = [artifact for artifact in artifacts if isinstance(artifact, dict)
                    and artifact.get("name") == expected_name and not artifact.get("expired")]
        if not matching:
            continue
        matching.sort(key=lambda artifact: (artifact.get("created_at", ""), artifact.get("id", 0)), reverse=True)
        artifact = matching[0]
        artifact_id = artifact.get("id")
        if not isinstance(artifact_id, int) or artifact_id <= 0:
            raise CollectionError("aggregate snapshot artifact has no valid id")
        archive_url = f"{API_ORIGIN}/repos/{repo_slug}/actions/artifacts/{artifact_id}/zip"
        archive = api_bytes(archive_url, token)
        try:
            with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
                entries = safe_archive_members(archive)
                reports = [(info, path) for info, path in entries if path.name == "test-intelligence.json"]
                if len(reports) != 1:
                    raise CollectionError("aggregate snapshot artifact must contain exactly one test-intelligence.json")
                report = json.loads(zipped.read(reports[0][0]))
        except (zipfile.BadZipFile, json.JSONDecodeError, ValueError) as exc:
            if isinstance(exc, CollectionError):
                raise
            raise CollectionError("aggregate snapshot archive is corrupt") from exc
        if (not isinstance(report, dict) or report.get("schemaVersion") != 1
                or not isinstance(report.get("components"), list) or not isinstance(report.get("totals"), dict)):
            raise CollectionError("aggregate snapshot schema is incomplete")
        return report
    return None


def api_bytes(url: str, token: str) -> bytes:
    require_github_api_url(url)
    request = Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        opener = build_opener(StripAuthorizationOnCrossHostRedirect)
        with opener.open(request, timeout=60) as response:  # noqa: S310 - origin is fixed above
            value = response.read(MAX_ARCHIVE_BYTES + 1)
            if len(value) > MAX_ARCHIVE_BYTES:
                raise CollectionError("compressed artifact archive exceeds size limit")
            return value
    except (HTTPError, URLError, TimeoutError) as exc:
        status = f"HTTP {exc.code}" if isinstance(exc, HTTPError) else type(exc).__name__
        raise CollectionError(f"GitHub artifact download failed ({status})") from exc


def collect(repo: Path, output: Path, repo_slug: str, token: str, *,
            fetch_page=None, fetch_run=None, fetch_archive=None, seed=SEED_UNSET, run_collector=None) -> dict:
    components = repo_components(repo)
    if not token:
        raise CollectionError("GITHUB_TOKEN is required to read trusted CI artifacts")
    artifact_cache: dict[str, dict] = {}
    run_cache: dict[int, dict] = {}

    def page(name: str, page_number: int) -> list[dict]:
        if fetch_page:
            artifacts = fetch_page(name, page_number)
        else:
            query = urlencode({"name": name, "per_page": PER_PAGE, "page": page_number})
            result = api_json(f"{API_ORIGIN}/repos/{repo_slug}/actions/artifacts?{query}", token)
            artifacts = result.get("artifacts")
            if not isinstance(artifacts, list):
                raise CollectionError(f"artifact inventory for {name} has no artifacts list")
        for artifact in artifacts:
            if isinstance(artifact, dict) and isinstance(artifact.get("id"), int):
                artifact_cache[str(artifact["id"])] = artifact
        return artifacts

    def workflow_run(run_id: int) -> dict:
        if fetch_run:
            return fetch_run(run_id)
        if run_id not in run_cache:
            run_cache[run_id] = api_json(f"{API_ORIGIN}/repos/{repo_slug}/actions/runs/{run_id}", token)
        value = run_cache[run_id]
        head_repo = value.get("head_repository")
        return {
            "path": value.get("path"), "event": value.get("event"),
            "head_repository": head_repo.get("full_name") if isinstance(head_repo, dict) else None,
            "head_sha": value.get("head_sha"), "head_branch": value.get("head_branch"),
            "run_attempt": value.get("run_attempt"), "id": value.get("id"),
        }

    planned: list[tuple[str, int | None]] = []
    for component in components:
        name = f"test-intelligence-{component}"
        try:
            artifact_id = FINDER.latest_main_artifact(name, page, workflow_run, repo_slug)
        except (FINDER.ArtifactApiError, ValueError, OSError) as exc:
            raise CollectionError(f"artifact lookup failed for {component}: {exc}") from exc
        planned.append((component, int(artifact_id) if artifact_id is not None else None))

    seed_report = latest_aggregate(repo_slug, token) if seed is SEED_UNSET else seed
    if fetch_archive:
        get_archive = fetch_archive
    else:
        get_archive = lambda artifact_id: api_bytes(
            f"{API_ORIGIN}/repos/{repo_slug}/actions/artifacts/{artifact_id}/zip", token)

    created_files: list[Path] = []
    created_dirs: list[Path] = []
    absent = []
    try:
        for component, artifact_id in planned:
            if artifact_id is None:
                absent.append(component)
                continue
            metadata = artifact_cache.get(str(artifact_id))
            if not isinstance(metadata, dict):
                raise CollectionError(f"selected artifact metadata for {component} is missing")
            workflow_metadata = metadata.get("workflow_run")
            run_id = workflow_metadata.get("id") if isinstance(workflow_metadata, dict) else None
            if not isinstance(run_id, int):
                raise CollectionError(f"selected artifact for {component} has no workflow run provenance")
            trusted = workflow_run(run_id)
            producer = FINDER.producer_of(f"test-intelligence-{component}")
            if not trusted_run(trusted, repo_slug, producer) or metadata.get("expired"):
                raise CollectionError(f"selected artifact for {component} is not from trusted main")
            archive = get_archive(artifact_id)
            payloads = artifact_payload(archive, component, trusted)
            for relative in payloads:
                target = repo / relative
                if target.exists():
                    raise CollectionError(f"refusing to mix downloaded evidence with pre-existing path: {relative}")
            for relative, content in payloads.items():
                target = repo / relative
                missing_dirs = []
                parent = target.parent
                while parent != repo and not parent.exists():
                    missing_dirs.append(parent)
                    parent = parent.parent
                target.parent.mkdir(parents=True, exist_ok=True)
                created_dirs.extend(reversed(missing_dirs))
                target.write_bytes(content)
                created_files.append(target)

        warnings = []
        if absent:
            warnings.append("No retained trusted main run envelope was available for: " + ", ".join(absent) + ". Their execution evidence is unknown, not passed.")
        if seed_report is None:
            warnings.append("No retained trusted aggregate snapshot was available; ancillary evidence is unknown.")
        with tempfile.TemporaryDirectory(prefix="admin-ui-test-intelligence-") as directory:
            generated = Path(directory) / "test-intelligence.json"
            collector = repo / "openbank-admin-ui/scripts/collect-test-intelligence.mjs"
            command = ["node", str(collector), "--repo", str(repo), "--out", str(generated)]
            (run_collector or subprocess.run)(command, cwd=repo, check=True)
            core = json.loads(generated.read_text(encoding="utf-8"))
        report = merge_ancillary(core, seed_report)
        report.setdefault("warnings", []).extend(warnings)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        return {"components": components, "staged": [component for component, aid in planned if aid is not None],
                "missing": absent, "seededAncillary": seed_report is not None, "output": str(output)}
    finally:
        for file in reversed(created_files):
            file.unlink(missing_ok=True)
        for parent in reversed(created_dirs):
            if parent.is_dir():
                try:
                    parent.rmdir()
                except OSError:
                    pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--repo-slug", default=os.environ.get("GITHUB_REPOSITORY"))
    args = parser.parse_args()
    token = os.environ.get("GITHUB_TOKEN", "")
    try:
        if not args.repo_slug:
            raise CollectionError("--repo-slug or GITHUB_REPOSITORY is required")
        output = args.out or args.repo_root / "openbank-admin-ui/test-intelligence.json"
        result = collect(args.repo_root.resolve(), output.resolve(), args.repo_slug, token)
        print(json.dumps(result, sort_keys=True))
    except (CollectionError, OSError, ValueError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print(f"Test Intelligence collection failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
