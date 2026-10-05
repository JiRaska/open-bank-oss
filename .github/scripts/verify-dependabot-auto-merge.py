"""Fail-closed admission for the privileged Dependabot auto-merge arm workflow.

Only GitHub API data is read. The workflow checks this script out at the
default-branch github.sha; it never loads code or artifacts from the PR.
"""

import json
import os
import re
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

CLASSIFIER_JOB = "Classify patch Dependabot PR"
METADATA_STEP = "Fetch Dependabot metadata"
ELIGIBLE_STEP = "Eligible patch for Gradle or npm"
Api = Callable[..., Any]


class AdmissionError(Exception):
    """The source run or current PR is not eligible for privileged arming."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AdmissionError(message)


def field(obj: Any, *path: str) -> Any:
    for key in path:
        if not isinstance(obj, dict) or key not in obj:
            raise AdmissionError(f"missing GitHub API field: {'.'.join(path)}")
        obj = obj[key]
    return obj


def gh_api(*args: str) -> Any:
    try:
        result = subprocess.run(
            ["gh", "api", *args], check=True, capture_output=True, text=True
        )
        return json.loads(result.stdout)
    except (subprocess.CalledProcessError, json.JSONDecodeError) as error:
        raise AdmissionError(
            "GitHub API request failed or returned invalid JSON"
        ) from error


def verify(event: Any, repo: str, api: Api = gh_api) -> int:
    require(
        isinstance(repo, str) and re.fullmatch(r"[^/]+/[^/]+", repo) is not None,
        "invalid repository identity",
    )
    require(
        field(event, "repository", "full_name") == repo,
        "event repository does not match the current repository",
    )
    run = field(event, "workflow_run")
    require(field(run, "conclusion") == "success", "classifier run did not succeed")
    require(field(run, "event") == "pull_request", "source run is not a pull request")
    require(
        field(run, "actor", "login") == "dependabot[bot]",
        "source actor is not Dependabot",
    )
    require(
        field(run, "head_repository", "full_name") == repo,
        "source head is outside this repository",
    )
    branch = field(run, "head_branch")
    sha = field(run, "head_sha")
    run_id = field(run, "id")
    attempt = field(run, "run_attempt")
    require(
        isinstance(branch, str)
        and branch.startswith("dependabot/")
        and len(branch) > 11,
        "source branch is not a Dependabot branch",
    )
    require(
        isinstance(sha, str) and re.fullmatch(r"[0-9a-f]{40}", sha) is not None,
        "invalid source head SHA",
    )
    require(
        type(run_id) is int and run_id > 0 and type(attempt) is int and attempt > 0,
        "invalid source run identity",
    )

    # Minor/major runs also succeed, but their eligibility step is skipped. The
    # Jobs API is server-side evidence tied to the exact run attempt; no artifact
    # from the less-trusted pull_request workflow is downloaded or executed.
    jobs = api(
        f"repos/{repo}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100"
    )
    job_list = field(jobs, "jobs")
    require(
        isinstance(job_list, list) and field(jobs, "total_count") == len(job_list),
        "classifier jobs response is incomplete",
    )
    matching_jobs = [
        job
        for job in job_list
        if isinstance(job, dict)
        and job.get("name") == CLASSIFIER_JOB
        and job.get("conclusion") == "success"
    ]
    require(len(matching_jobs) == 1, "successful classifier job was not unique")
    steps = field(matching_jobs[0], "steps")
    require(isinstance(steps, list), "classifier steps response is incomplete")
    for step_name in (METADATA_STEP, ELIGIBLE_STEP):
        matching_steps = [
            step
            for step in steps
            if isinstance(step, dict)
            and step.get("name") == step_name
            and step.get("conclusion") == "success"
        ]
        require(len(matching_steps) == 1, f"{step_name} did not succeed exactly once")

    # workflow_run.pull_requests is empty for real Dependabot runs in this repo.
    # Resolve a single *open* PR from the exact same-repository head branch.
    owner = repo.split("/", 1)[0]
    prs = api(
        "--method",
        "GET",
        f"repos/{repo}/pulls",
        "-f",
        "state=open",
        "-f",
        f"head={owner}:{branch}",
        "-f",
        "per_page=100",
    )
    require(
        isinstance(prs, list) and len(prs) == 1,
        "expected exactly one open PR for source branch",
    )
    pr = prs[0]
    require(
        field(pr, "user", "login") == "dependabot[bot]", "PR author is not Dependabot"
    )
    require(
        field(pr, "head", "repo", "full_name") == repo, "PR head repository changed"
    )
    require(field(pr, "head", "ref") == branch, "PR head branch changed")
    require(field(pr, "head", "sha") == sha, "PR head SHA changed after classification")
    require(
        field(pr, "base", "repo", "full_name") == repo, "PR base repository changed"
    )
    require(field(pr, "base", "ref") == "main", "PR no longer targets main")
    require(field(pr, "state") == "open", "PR is no longer open")
    number = field(pr, "number")
    require(type(number) is int and number > 0, "invalid PR number")

    files_seen = 0
    for page in range(1, 31):
        files = api(f"repos/{repo}/pulls/{number}/files?per_page=100&page={page}")
        require(isinstance(files, list), "PR files response is incomplete")
        files_seen += len(files)
        for entry in files:
            name = field(entry, "filename")
            require(isinstance(name, str) and bool(name), "PR file path is missing")
            require(
                not (name.startswith((".github/workflows/", ".github/scripts/"))),
                "PR changes trusted workflow or verifier code; human review required",
            )
        if len(files) < 100:
            break
    else:
        raise AdmissionError("PR files exceeded the bounded API page count")
    require(files_seen > 0, "PR has no changed files")
    return number


def main() -> None:
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    try:
        number = verify(event, os.environ["GH_REPO"])
    except AdmissionError as error:
        print(f"::error::{error}")
        raise SystemExit(1) from error
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write(f"pr_number={number}\n")


if __name__ == "__main__":
    main()
