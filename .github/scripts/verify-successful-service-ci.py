# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Verify that an exact main Services CI run built and contracted one service.

Used by auto-deploy reconcile before it borrows a Pact verdict for an equivalent
tree. A broker tag is not provenance: this checks a completed successful run of
the Services CI workflow, its exact SHA/attempt, all-green, and the service's own
build and contract child jobs. Every API or schema failure is a refusal.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from collections.abc import Iterator
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin, urlsplit
from urllib.request import Request, urlopen

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MAX_API_PAGES = 20
RECONCILE_RESOLUTION_SECONDS = 420


def _require_time_remaining(deadline: float | None) -> None:
    if deadline is not None and time.monotonic() >= deadline:
        raise VerificationError("reconcile source resolution exceeded its time budget")


def _bounded_timeout(deadline: float | None, maximum: float) -> float:
    _require_time_remaining(deadline)
    if deadline is None:
        return maximum
    return min(maximum, deadline - time.monotonic())


class VerificationError(Exception):
    pass


def _iter_json_pages(
    url: str, token: str, *, deadline: float | None = None
) -> Iterator[list[dict]]:
    """Yield API pages; errors and malformed pages must never become evidence."""
    next_url: str | None = url
    origin = urlsplit(url)[:2]
    visited: set[str] = set()
    while next_url:
        if next_url in visited:
            raise VerificationError("GitHub Actions API pagination repeated a page URL")
        if len(visited) >= MAX_API_PAGES:
            raise VerificationError(
                f"GitHub Actions API pagination exceeded {MAX_API_PAGES} pages"
            )
        visited.add(next_url)
        _require_time_remaining(deadline)
        if urlsplit(next_url)[:2] != origin:
            raise VerificationError("GitHub Actions API pagination changed origin")
        request = Request(
            next_url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            timeout = 20 if deadline is None else min(20, deadline - time.monotonic())
            if timeout <= 0:
                raise VerificationError(
                    "reconcile source resolution exceeded its time budget"
                )
            with urlopen(request, timeout=timeout) as response:
                body = json.loads(response.read())
                link_header = response.headers.get("Link", "")
        except (
            HTTPError,
            URLError,
            TimeoutError,
            OSError,
            json.JSONDecodeError,
        ) as exc:
            raise VerificationError(
                f"GitHub Actions API request failed: {type(exc).__name__}"
            ) from exc
        if not isinstance(body, dict):
            raise VerificationError("GitHub Actions API returned a non-object response")
        # Run endpoints wrap results in workflow_runs; job endpoints use jobs.
        key = "workflow_runs" if "workflow_runs" in body else "jobs"
        page_rows = body.get(key)
        if not isinstance(page_rows, list) or any(
            not isinstance(row, dict) for row in page_rows
        ):
            raise VerificationError(
                f"GitHub Actions API response has no valid {key} array"
            )
        yield page_rows
        next_url = None
        for part in link_header.split(","):
            match = re.match(r'\s*<([^>]+)>\s*;\s*rel="next"', part)
            if match:
                next_url = match.group(1)
                if urlsplit(next_url)[:2] != origin:
                    raise VerificationError(
                        "GitHub Actions API pagination changed origin"
                    )
                break


def _json_pages(url: str, token: str, *, deadline: float | None = None) -> list[dict]:
    """Read every API page; errors and malformed pages must never become evidence."""
    return [
        row for page in _iter_json_pages(url, token, deadline=deadline) for row in page
    ]


def verify_successful_service_ci(
    service: str, sha: str, repository: str, token: str, api_url: str
) -> tuple[str, int]:
    if not re.fullmatch(r"openbank-[a-z0-9-]+", service):
        raise VerificationError("invalid service name")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise VerificationError("invalid source SHA")
    if not re.fullmatch(r"[^/]+/[^/]+", repository):
        raise VerificationError("invalid repository")
    if not token:
        raise VerificationError("GitHub token is unavailable")

    base = api_url.rstrip("/") + "/"
    encoded_repo = "/".join(repository.split("/"))
    query = urlencode({"branch": "main", "head_sha": sha, "per_page": 100})
    runs_url = urljoin(
        base,
        f"repos/{encoded_repo}/actions/workflows/services-ci.yml/runs?{query}",
    )
    runs = _json_pages(runs_url, token)
    candidates = [
        run
        for run in runs
        if run.get("head_sha") == sha
        and run.get("head_branch") == "main"
        and run.get("event") in {"push", "workflow_dispatch"}
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
        and isinstance(run.get("id"), int)
        and isinstance(run.get("run_attempt"), int)
        and run.get("run_attempt", 0) > 0
    ]
    expected = {
        "all-green",
        f"build ({service}) / {service} (build)",
        f"build ({service}) / {service} (contract)",
    }
    for run in candidates:
        run_id = run["id"]
        attempt = run["run_attempt"]
        jobs_query = urlencode({"per_page": 100})
        jobs_url = urljoin(
            base,
            f"repos/{encoded_repo}/actions/runs/{run_id}/attempts/{attempt}/jobs?{jobs_query}",
        )
        jobs = _json_pages(jobs_url, token)
        observed: dict[str, list[str | None]] = {}
        for job in jobs:
            name = job.get("name")
            if name in expected:
                observed.setdefault(name, []).append(job.get("conclusion"))
        if set(observed) == expected and all(
            value == ["success"] for value in observed.values()
        ):
            return str(run_id), attempt
    raise VerificationError(
        f"no successful exact-main Services CI attempt proves {service} build and contract for {sha}"
    )


def _jobs_prove_services(
    run: dict,
    services: set[str],
    repository: str,
    token: str,
    api_url: str,
    *,
    deadline: float | None = None,
) -> set[str]:
    """Fetch a workflow attempt once and identify each requested service with two green jobs."""
    run_id = run.get("id")
    attempt = run.get("run_attempt")
    if not isinstance(run_id, int) or not isinstance(attempt, int) or attempt < 1:
        return set()
    base = api_url.rstrip("/") + "/"
    jobs_query = urlencode({"per_page": 100})
    jobs_url = urljoin(
        base,
        f"repos/{repository}/actions/runs/{run_id}/attempts/{attempt}/jobs?{jobs_query}",
    )
    jobs = _json_pages(jobs_url, token, deadline=deadline)
    expected = {
        name
        for service in services
        for name in (
            f"build ({service}) / {service} (build)",
            f"build ({service}) / {service} (contract)",
        )
    }
    observed: dict[str, list[str | None]] = {}
    for job in jobs:
        name = job.get("name")
        if name in expected:
            observed.setdefault(name, []).append(job.get("conclusion"))
    metadata_jobs = [
        job
        for job in jobs
        if isinstance(job.get("name"), str)
        and job["name"].startswith("verification-metadata (")
    ]
    expected_metadata = _expected_verification_modules(run, deadline=deadline)
    metadata_names = [job["name"] for job in metadata_jobs]
    # all-green also aggregates unrelated service builds, so its failure alone must
    # not erase valid per-service evidence. Its verification-metadata dependency is
    # different: metadata failures invalidate the build verification and must remain
    # blocking even when this service's build and contract children passed.
    metadata_green = (
        expected_metadata is not None
        and len(metadata_names) == len(set(metadata_names))
        and all(job.get("conclusion") == "success" for job in metadata_jobs)
        and all(
            metadata_names.count(f"verification-metadata ({module})") == 1
            for module in expected_metadata
        )
    )
    return {
        service
        for service in services
        if metadata_green
        and all(
            observed.get(f"build ({service}) / {service} ({phase})") == ["success"]
            for phase in ("build", "contract")
        )
    }


def _expected_verification_modules(
    run: dict, *, deadline: float | None = None
) -> set[str] | None:
    """Reproduce the Services CI metadata matrix for a push from its exact diff."""
    if run.get("event") != "push":
        # schedule/workflow_dispatch set FILES empty in services-ci.yml, so the
        # derive-verification-modules step deliberately emits an empty matrix.
        return set()
    sha = run.get("head_sha")
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        return None
    try:
        changed = subprocess.run(
            ["git", "diff", "--name-only", f"{sha}^", sha],
            check=True,
            capture_output=True,
            text=True,
            timeout=_bounded_timeout(deadline, 30),
        ).stdout.splitlines()
    except (OSError, subprocess.SubprocessError):
        return None
    return {
        match.group(1)
        for path in changed
        if (match := re.fullmatch(r"(openbank-[^/]+)/build\.gradle\.kts", path))
    }


def _is_ancestor(
    source_sha: str, target_sha: str, *, deadline: float | None = None
) -> bool:
    try:
        return (
            subprocess.run(
                ["git", "merge-base", "--is-ancestor", source_sha, target_sha],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=_bounded_timeout(deadline, 30),
            ).returncode
            == 0
        )
    except (OSError, subprocess.TimeoutExpired):
        return False


def _tree_equivalent(
    service: str,
    source_sha: str,
    target_sha: str,
    *,
    deadline: float | None = None,
) -> bool:
    comparator = os.path.join(SCRIPT_DIR, "pact-version-tree-equivalent.sh")
    try:
        return (
            subprocess.run(
                ["bash", comparator, service, source_sha, target_sha],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=_bounded_timeout(deadline, 30),
            ).returncode
            == 0
        )
    except (OSError, subprocess.TimeoutExpired):
        return False


def _push_might_build_service(
    service: str, sha: str, *, deadline: float | None = None
) -> bool:
    """Avoid Actions jobs API requests for unrelated docs-only main pushes.

    Services CI is push-path-scoped. If this commit's complete build-input tree for
    the service is unchanged from its first parent, the push matrix could not have
    selected it. Unknown diffs and workflow/Pact edits stay eligible.
    """
    try:
        parent = subprocess.run(
            ["git", "rev-parse", "--verify", f"{sha}^"],
            check=True,
            capture_output=True,
            text=True,
            timeout=_bounded_timeout(deadline, 30),
        ).stdout.strip()
        changed = subprocess.run(
            ["git", "diff", "--name-only", parent, sha],
            check=True,
            capture_output=True,
            text=True,
            timeout=_bounded_timeout(deadline, 30),
        ).stdout.splitlines()
    except (OSError, subprocess.SubprocessError):
        return True
    if any(path.startswith((".github/", "pacts/")) for path in changed):
        return True
    return not _tree_equivalent(service, parent, sha, deadline=deadline)


def _exact_pact_exists(
    service: str, sha: str, *, deadline: float | None = None
) -> bool:
    """Probe this exact Pact version; never ask latest/main or wait for a moving version."""
    probe = os.path.join(SCRIPT_DIR, "probe-pact-version.sh")
    env = os.environ.copy()
    env.update({"EVENT_NAME": "push", "PACT_WAIT_PER_SERVICE_SECONDS": "0"})
    try:
        result = subprocess.run(
            ["bash", probe, service, sha],
            check=False,
            capture_output=True,
            text=True,
            env=env,
            timeout=_bounded_timeout(deadline, 30),
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0 and result.stdout.strip() == "yes"


def resolve_reconcile_service_source(
    service: str, tip_sha: str, repository: str, token: str, api_url: str
) -> tuple[str, str, int]:
    resolved = resolve_reconcile_service_sources(
        [service], tip_sha, repository, token, api_url
    )
    source = resolved[service]
    return source["sha"], source["run_id"], source["attempt"]


def resolve_reconcile_service_sources(
    services: list[str], tip_sha: str, repository: str, token: str, api_url: str
) -> dict[str, dict[str, str | int]]:
    """Resolve exact historical Sources CI/Pact versions for a batch of services.

    The workflow run listing is newest-first. Each selected source must be an ancestor
    of current main, have successful build+contract jobs for that service in one attempt,
    have an exact Pact version, and be tree-equivalent to the built image. Jobs are fetched
    once per candidate run for the entire pending service batch. A bad/incomplete candidate
    is skipped; API failures and inconsistent pagination are refusals.
    """
    if (
        not isinstance(services, list)
        or not services
        or any(
            not isinstance(service, str)
            or not re.fullmatch(r"openbank-[a-z0-9-]+", service)
            for service in services
        )
    ):
        raise VerificationError("invalid service list")
    if len(services) != len(set(services)):
        raise VerificationError("duplicate service in resolver input")
    if not re.fullmatch(r"[0-9a-f]{40}", tip_sha):
        raise VerificationError("invalid current main SHA")
    if not re.fullmatch(r"[^/]+/[^/]+", repository):
        raise VerificationError("invalid repository")
    if not token:
        raise VerificationError("GitHub token is unavailable")

    base = api_url.rstrip("/") + "/"
    deadline = time.monotonic() + RECONCILE_RESOLUTION_SECONDS
    query = urlencode({"branch": "main", "per_page": 100})
    runs_url = urljoin(
        base, f"repos/{repository}/actions/workflows/services-ci.yml/runs?{query}"
    )
    pending = set(services)
    resolved: dict[str, dict[str, str | int]] = {}
    ancestor_cache: dict[str, bool] = {}
    newest_seen: str | None = None
    for page in _iter_json_pages(runs_url, token, deadline=deadline):
        for run in page:
            _require_time_remaining(deadline)
            created_at = run.get("created_at")
            if not isinstance(created_at, str):
                raise VerificationError(
                    "Services CI run is missing its creation timestamp"
                )
            if newest_seen is not None and created_at > newest_seen:
                raise VerificationError(
                    "Services CI run pagination is not newest-first"
                )
            newest_seen = created_at
            sha = run.get("head_sha")
            if (
                run.get("head_branch") != "main"
                or run.get("event") not in {"push", "workflow_dispatch"}
                or run.get("status") != "completed"
                or not isinstance(sha, str)
                or not re.fullmatch(r"[0-9a-f]{40}", sha)
            ):
                continue
            if sha not in ancestor_cache:
                ancestor_cache[sha] = _is_ancestor(sha, tip_sha, deadline=deadline)
            if not ancestor_cache[sha]:
                continue
            candidates: set[str] = set()
            for service in pending:
                _require_time_remaining(deadline)
                if run.get("event") == "push" and not _push_might_build_service(
                    service, sha, deadline=deadline
                ):
                    continue
                if _tree_equivalent(service, sha, tip_sha, deadline=deadline):
                    candidates.add(service)
            if not candidates:
                continue
            successful = _jobs_prove_services(
                run, candidates, repository, token, api_url, deadline=deadline
            )
            for service in candidates & successful:
                _require_time_remaining(deadline)
                if not _exact_pact_exists(service, sha, deadline=deadline):
                    continue
                _require_time_remaining(deadline)
                resolved[service] = {
                    "sha": sha,
                    "run_id": str(run["id"]),
                    "attempt": run["run_attempt"],
                }
                pending.remove(service)
            if not pending:
                return resolved
    missing = ", ".join(sorted(pending))
    raise VerificationError(
        f"no successful main Services CI build+contract with exact Pact versions and "
        f"tree-equivalent inputs found for {missing} at {tip_sha}"
    )


def main() -> int:
    if len(sys.argv) == 4 and sys.argv[1] == "--resolve-reconcile-sources":
        try:
            services = json.loads(sys.argv[2])
            resolved = resolve_reconcile_service_sources(
                services,
                sys.argv[3],
                os.environ.get("GITHUB_REPOSITORY", ""),
                os.environ.get("GH_TOKEN", ""),
                os.environ.get("GITHUB_API_URL", "https://api.github.com"),
            )
        except (json.JSONDecodeError, VerificationError) as exc:
            print(f"::error::reconcile source refused: {exc}", file=sys.stderr)
            return 1
        print(json.dumps(resolved, sort_keys=True))
        return 0
    if len(sys.argv) == 4 and sys.argv[1] == "--resolve-reconcile-source":
        try:
            sha, run_id, attempt = resolve_reconcile_service_source(
                sys.argv[2],
                sys.argv[3],
                os.environ.get("GITHUB_REPOSITORY", ""),
                os.environ.get("GH_TOKEN", ""),
                os.environ.get("GITHUB_API_URL", "https://api.github.com"),
            )
        except VerificationError as exc:
            print(f"::error::reconcile source refused: {exc}", file=sys.stderr)
            return 1
        print(
            json.dumps(
                {"sha": sha, "run_id": run_id, "attempt": attempt}, sort_keys=True
            )
        )
        return 0
    if len(sys.argv) != 3:
        print(
            "usage: verify-successful-service-ci.py <service> <sha> | "
            "--resolve-reconcile-source <service> <current-main-sha> | "
            "--resolve-reconcile-sources <services-json> <current-main-sha>",
            file=sys.stderr,
        )
        return 2
    try:
        run_id, attempt = verify_successful_service_ci(
            sys.argv[1],
            sys.argv[2],
            os.environ.get("GITHUB_REPOSITORY", ""),
            os.environ.get("GH_TOKEN", ""),
            os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
    except VerificationError as exc:
        print(f"::error::reconcile source refused: {exc}", file=sys.stderr)
        return 1
    print(f"verified Services CI run {run_id} attempt {attempt}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
