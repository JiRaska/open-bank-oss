#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Admit an Admin UI image source only after exact-main validation completes."""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

POLL_SECONDS = 60
MAX_WAIT_SECONDS = 40 * 60
API_PAGE_SIZE = 100
API_MAX_PAGES = 10
WORKFLOWS = {
    "ci.yml": ("Admin UI build",),
    # The workflow's all-green job is the authoritative aggregate. Individual
    # matrix entries may be skipped when the selected module scope is empty.
    "services-ci.yml": ("Detect changed services", "all-green"),
    "pact-drift-check.yml": (
        "Regenerate consumer pacts and diff against committed",
        "Publish verified admin UI contracts",
    ),
}


def pact_push_path_patterns(workflow_path=None):
    """Read Pact's actual main-push path filters instead of mirroring the list."""
    path = Path(workflow_path) if workflow_path else \
        Path(__file__).resolve().parents[1] / "workflows" / "pact-drift-check.yml"
    lines = path.read_text().splitlines()
    in_push = False
    in_paths = False
    found_push = False
    patterns = []
    for line in lines:
        if line == "  push:":
            if found_push:
                raise RuntimeError("Pact workflow has multiple push trigger sections")
            in_push = True
            in_paths = False
            found_push = True
            continue
        if in_push and line.startswith("  ") and not line.startswith("    "):
            break
        if not in_push:
            continue
        if line == "    paths:":
            in_paths = True
            continue
        if in_paths:
            if not line.strip() or line.startswith("      #"):
                continue
            if not line.startswith("      "):
                if re.match(r"^(?: {0,4})[A-Za-z_-][A-Za-z0-9_-]*:", line):
                    break
                raise RuntimeError("Unsupported Pact workflow push path-filter indentation")
            if not line.startswith("      - "):
                raise RuntimeError("Unsupported Pact workflow push path-filter syntax")
            value = line[8:].strip()
            if len(value) < 2 or value[0] != '"' or value[-1] != '"':
                raise RuntimeError("Pact workflow path patterns must be double-quoted scalars")
            try:
                value = json.loads(value)
            except json.JSONDecodeError as error:
                raise RuntimeError("Malformed quoted Pact workflow path pattern") from error
            if not isinstance(value, str) or not value:
                raise RuntimeError("Pact workflow path patterns must be non-empty strings")
            if any(char in value for char in "[]{}+"):
                raise RuntimeError("Unsupported Pact workflow path glob syntax")
            patterns.append(value)
    if not patterns:
        raise RuntimeError("Pact workflow main-push path filters could not be read")
    return patterns


def github_path_glob_matches(path, pattern):
    """Match GitHub Actions path globs: * and ? cannot cross a slash; ** can."""
    expression = []
    index = 0
    while index < len(pattern):
        char = pattern[index]
        if char == "*":
            if index + 1 < len(pattern) and pattern[index + 1] == "*":
                index += 2
                if index < len(pattern) and pattern[index] == "/":
                    expression.append("(?:.*/)?")
                    index += 1
                else:
                    expression.append(".*")
                continue
            expression.append("[^/]*")
        elif char == "?":
            expression.append("[^/]")
        else:
            expression.append(re.escape(char))
        index += 1
    return re.fullmatch("".join(expression), path) is not None


def path_matches_pact_push(paths, patterns):
    """Apply ordered positive/negative patterns from the actual workflow trigger."""
    for path in paths:
        included = False
        for raw_pattern in patterns:
            negative = raw_pattern.startswith("!")
            pattern = raw_pattern[1:] if negative else raw_pattern
            if github_path_glob_matches(path, pattern):
                included = not negative
        if included:
            return True
    return False


def pact_triggered_for_source(fetch, repository, sha):
    commit = fetch(f"repos/{repository}/commits/{sha}")
    parents = commit.get("parents", [])
    if not parents or not isinstance(parents[0].get("sha"), str):
        raise RuntimeError(f"Cannot determine first parent for {sha}")
    base = parents[0]["sha"]
    comparison = fetch(f"repos/{repository}/compare/{base}...{sha}")
    files = comparison.get("files")
    if not isinstance(files, list) or len(files) >= 300:
        return True
    changed_paths = []
    for item in files:
        paths = [item.get("filename")]
        if item.get("previous_filename"):
            paths.append(item["previous_filename"])
        if any(not isinstance(path, str) for path in paths):
            return True
        changed_paths.extend(paths)
    return path_matches_pact_push(changed_paths, pact_push_path_patterns())


def main_push_runs(fetch, repository, workflow, sha=None):
    params = {"branch": "main", "event": "push", "per_page": 100}
    if sha:
        params["head_sha"] = sha
    listing = fetch(f"repos/{repository}/actions/workflows/{workflow}/runs?{urllib.parse.urlencode(params)}")
    runs = listing.get("workflow_runs", [])
    if not isinstance(runs, list):
        raise RuntimeError(f"GitHub API returned no workflow_runs list for {workflow}")
    # The endpoint returns newest runs first. A bounded latest page preserves the
    # existing newest-run-wins rule without walking unbounded Pact history; exact-SHA
    # checks fail closed when that source's run is absent from this page.
    return [run for run in runs
            if (sha is None or run.get("head_sha") == sha)
            and isinstance(run.get("head_sha"), str)
            and run.get("head_branch") == "main"
            and run.get("event") == "push"
            and run.get("path") == f".github/workflows/{workflow}"
            and (run.get("repository") or {}).get("full_name") == repository]


def fetch_all(fetch, path, collection):
    """Fetch a bounded paginated GitHub collection, failing closed on incomplete pages."""
    url = urllib.parse.urlsplit(path)
    params = dict(urllib.parse.parse_qsl(url.query, keep_blank_values=True))
    params["per_page"] = str(API_PAGE_SIZE)
    records = []
    total_count = None
    for page in range(1, API_MAX_PAGES + 1):
        params["page"] = str(page)
        page_path = urllib.parse.urlunsplit(
            ("", "", url.path, urllib.parse.urlencode(params), ""))
        payload = fetch(page_path)
        current = payload.get(collection)
        if not isinstance(current, list):
            raise RuntimeError(f"GitHub API returned no {collection} list for {path}")
        reported_total = payload.get("total_count")
        if reported_total is not None:
            if not isinstance(reported_total, int) or reported_total < 0:
                raise RuntimeError(f"GitHub API returned an invalid {collection} total")
            if total_count is not None and reported_total != total_count:
                raise RuntimeError(f"GitHub {collection} total changed during pagination")
            total_count = reported_total
        records.extend(current)
        if total_count is not None:
            if len(records) >= total_count:
                return records[:total_count]
            if not current:
                raise RuntimeError(f"GitHub {collection} pagination ended before total_count")
        elif len(current) < API_PAGE_SIZE:
            return records
    raise RuntimeError(f"GitHub {collection} exceeded the bounded {API_MAX_PAGES}-page read")


def check_workflow(fetch, repository, sha, workflow):
    runs = main_push_runs(fetch, repository, workflow, sha)
    if not runs:
        return None
    run = max(runs, key=lambda item: item["id"])
    if run.get("status") != "completed":
        return False
    if run.get("conclusion") != "success":
        raise RuntimeError(f"{workflow} for {sha} ended {run.get('conclusion')}")
    jobs = fetch_all(fetch, f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest",
                     "jobs")
    for name in WORKFLOWS[workflow]:
        matches = [job for job in jobs if job.get("name") == name]
        if len(matches) > 1:
            raise RuntimeError(f"{workflow} has ambiguous exact-source jobs named {name}")
        if not matches or matches[0].get("status") != "completed":
            # The workflow-run completion event can become visible before the
            # jobs endpoint has indexed every job (or its terminal state). Keep
            # one-shot workflow_run checks pending; later completion events can
            # retry. This is never authorization: callers proceed only on True.
            return False
        if matches[0].get("conclusion") != "success":
            raise RuntimeError(f"{workflow} lacks successful exact-source job {name}")
    return True


def validation_ready(fetch, repository, sha):
    """Return true when all exact-source gates are green, false while pending."""
    ci = check_workflow(fetch, repository, sha, "ci.yml")
    services = check_workflow(fetch, repository, sha, "services-ci.yml")
    if ci is not True or services is not True:
        return False

    source_pact = check_workflow(fetch, repository, sha, "pact-drift-check.yml")
    if source_pact is True:
        return True
    if source_pact is False:
        return False
    if pact_triggered_for_source(fetch, repository, sha):
        return False

    ui_sha = latest_ui_change(fetch, repository, sha)
    ui_changes = {sha: ui_sha}
    evidence = same_ui_tree_pact_sha(fetch, repository, ui_sha, ui_changes)
    return check_workflow(fetch, repository, evidence, "pact-drift-check.yml") is True


def latest_ui_change(fetch, repository, source_sha):
    query = urllib.parse.urlencode({"sha": source_sha, "path": "openbank-admin-ui/", "per_page": 1})
    commits = fetch(f"repos/{repository}/commits?{query}")
    if not isinstance(commits, list) or len(commits) != 1:
        raise RuntimeError(f"Cannot identify the latest Admin UI ancestor of {source_sha}")
    ui_sha = commits[0].get("sha")
    if not isinstance(ui_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", ui_sha):
        raise RuntimeError("Latest Admin UI ancestor has no canonical commit SHA")
    return ui_sha


def same_ui_tree_pact_sha(fetch, repository, ui_sha, ui_changes):
    """Return the head of the newest main Pact run whose Admin UI tree is ui_sha's.

    A commit whose latest openbank-admin-ui/ change is ui_sha carries ui_sha's Admin UI
    tree byte for byte, so its Pact verdict is about the same source. This includes
    ui_sha's own runs; the NEWEST such run decides, so a failed ancestor is superseded
    only by a later run over the identical tree, and a later failure or pending run is
    never bypassed by an earlier success. A commit that touched openbank-admin-ui/
    resolves to itself and never qualifies. ui_changes caches sha -> latest UI change.
    """
    runs = sorted(main_push_runs(fetch, repository, "pact-drift-check.yml"),
                  key=lambda item: item["id"], reverse=True)
    for run in runs:
        head = run["head_sha"]
        if head not in ui_changes:
            ui_changes[head] = ui_sha if head == ui_sha else latest_ui_change(fetch, repository, head)
        if ui_changes[head] == ui_sha:
            return head
    return ui_sha


def wait_for_validation(fetch, repository, sha, *, clock=time.monotonic,
                        sleep=time.sleep, max_wait=MAX_WAIT_SECONDS):
    deadline = clock() + max_wait
    # A governance-only push can rebuild UI code introduced by an earlier main commit.
    # Prefer a successful Pact run for the exact image source when one exists. Only fall
    # back to the latest UI-changing ancestor when that source did not trigger Pact drift.
    # A pending or failed exact-source run must never be bypassed by older evidence.
    # The fallback is the newest main Pact run over the SAME Admin UI tree, not only the
    # ancestor's: a re-run replays the ancestor's old workflow file, so when its run failed
    # on a CI defect later fixed on main, only a descendant run can ever clear it.
    ui_sha = latest_ui_change(fetch, repository, sha)
    ui_changes = {sha: ui_sha}
    while True:
        if check_workflow(fetch, repository, sha, "ci.yml") and \
                check_workflow(fetch, repository, sha, "services-ci.yml"):
            source_pact = check_workflow(fetch, repository, sha, "pact-drift-check.yml")
            if source_pact is True:
                return
            if source_pact is None and not pact_triggered_for_source(fetch, repository, sha):
                evidence = same_ui_tree_pact_sha(fetch, repository, ui_sha, ui_changes)
                if check_workflow(fetch, repository, evidence, "pact-drift-check.yml"):
                    return
        remaining = deadline - clock()
        if remaining <= 0:
            raise TimeoutError(f"Exact-source validation for {sha} did not complete within {max_wait}s")
        sleep(min(POLL_SECONDS, remaining))


def github_fetch(token):
    def fetch(path):
        request = urllib.request.Request(
            "https://api.github.com/" + path,
            headers={"Authorization": "Bearer " + token,
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        parsed = urllib.parse.urlsplit(request.full_url)
        endpoint = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                try:
                    return json.load(response)
                except (json.JSONDecodeError, UnicodeError):
                    raise RuntimeError(f"GitHub API {endpoint} returned invalid JSON") from None
        except urllib.error.HTTPError as error:
            # Do not print the response body or request headers: GitHub's body is
            # untrusted, and the request carries GH_TOKEN. Only expose a small
            # allowlist of rate-limit response headers to diagnose quota failures.
            details = [f"HTTP {error.code}"]
            for header, label in (
                ("X-RateLimit-Limit", "rate-limit-limit"),
                ("X-RateLimit-Resource", "rate-limit-resource"),
                ("X-RateLimit-Remaining", "rate-limit-remaining"),
                ("X-RateLimit-Reset", "rate-limit-reset"),
                ("Retry-After", "retry-after"),
            ):
                value = error.headers.get(header) if error.headers else None
                if value is None:
                    continue
                safe_value = str(value).strip()
                if (label == "rate-limit-resource" and re.fullmatch(r"[a-z_]{1,32}", safe_value)) or (
                    label != "rate-limit-resource" and re.fullmatch(r"[0-9]{1,12}", safe_value)
                ):
                    details.append(f"{label}={safe_value}")
            raise RuntimeError(f"GitHub API {endpoint} failed ({'; '.join(details)})") from None
    return fetch


def main():
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    sha = os.environ.get("SOURCE_SHA", "")
    token = os.environ.get("GH_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Repository identity is missing or invalid")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or not token:
        raise ValueError("Exact source SHA and read-only API token are required")
    fetch = github_fetch(token)
    if "--check" in sys.argv[1:]:
        if validation_ready(fetch, repository, sha):
            print("proceed=true")
            print(f"Verified exact-source Admin UI validation for {sha}", file=sys.stderr)
        else:
            print("proceed=false")
            print(f"Exact-source Admin UI validation for {sha} is still pending", file=sys.stderr)
        return
    wait_for_validation(fetch, repository, sha)
    print(f"Verified exact-source Admin UI validation for {sha}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, TimeoutError, OSError) as error:
        print(f"::error::Admin UI source validation failed: {error}", file=sys.stderr)
        sys.exit(1)
