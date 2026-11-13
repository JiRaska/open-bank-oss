#!/usr/bin/env python3
"""Exit 0 when test-intelligence was published successfully within the last WINDOW minutes.

Collecting test-intelligence costs ~220-260 GITHUB_TOKEN REST requests (artifact listing, run
reads and a zip per component across ~74 components). admin-ui-evidence.yml collected it on EVERY
main CI / Services CI completion, ~4-5 times an hour, i.e. ~1,000-1,300 of the repository's
shared 5000/h REST core quota — the largest attributed consumer when that quota ran out three
times in nine hours on 2026-11-09/10 and failed required admission checks on unrelated PRs (#15483).

Scheduled and workflow_run triggers skip test-intelligence when an automated publish succeeded
within WINDOW minutes. Manual dispatches can force a refresh or roll back a snapshot.

Fail OPEN toward collecting: any error answering the question (API failure, unexpected shape)
exits 1 = "not recently published", so a broken probe can only restore today's behaviour, never
silently stop the dataset from refreshing.

Usage: test-intelligence-recently-published.py <owner/repo> <current-run-id> [--window 55]
       test-intelligence-recently-published.py --self-test
Costs at most 1 + MAX_RUNS requests.
"""
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile

GH = os.environ.get("TI_RECENT_GH", "gh")
WORKFLOW = "admin-ui-evidence.yml"
JOB = "publish (test-intelligence)"
PUBLISH_STEP = "Publish selected validated snapshot and atomic selector"
MAX_RUNS = 6


def gh_json(path):
    p = subprocess.run([GH, "api", path], capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.strip())
    return json.loads(p.stdout)


def recently_published(repo, current_run, window_min, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    since = (now - dt.timedelta(minutes=window_min)).strftime("%Y-%m-%dT%H:%M:%SZ")
    runs = gh_json(f"repos/{repo}/actions/workflows/{WORKFLOW}/runs?per_page=20&created=%3E{since}")["workflow_runs"]
    checked = 0
    for run in runs:
        if (str(run["id"]) == str(current_run) or run.get("status") not in {"completed", "in_progress"}
                or run.get("event") not in {"schedule", "workflow_run"}):
            continue
        if checked >= MAX_RUNS:
            break
        checked += 1
        for job in gh_json(f"repos/{repo}/actions/runs/{run['id']}/jobs?per_page=50")["jobs"]:
            if job["name"] != JOB or job.get("conclusion") != "success":
                continue
            if any(step["name"] == PUBLISH_STEP and step.get("conclusion") == "success"
                   for step in job["steps"]):
                return run["id"]
    return None


def self_test():
    fails = 0
    runs = {"workflow_runs": [
        {"id": 1, "status": "completed", "event": "workflow_run"},
        {"id": 2, "status": "in_progress", "event": "schedule"},
        {"id": 3, "status": "completed", "event": "schedule"},
    ]}
    jobs_hit = {"jobs": [
        {"name": "authorize-source", "conclusion": "success"},
        {"name": JOB, "conclusion": "success", "steps": [{"name": PUBLISH_STEP, "conclusion": "success"}]},
    ]}
    jobs_skip = {"jobs": [{"name": JOB, "conclusion": "skipped"}]}
    superseded = {"jobs": [
        {"name": JOB, "conclusion": "success", "steps": [{"name": PUBLISH_STEP, "conclusion": "skipped"}]},
    ]}
    manual = {"workflow_runs": [{"id": 3, "status": "completed", "event": "workflow_dispatch"}]}
    cases = [  # name, runs payload, jobs payload, runs-exit, expected
        ("published-recently", runs, jobs_hit, 0, 2),
        ("whole-run-completed", {"workflow_runs": [runs["workflow_runs"][2]]}, jobs_hit, 0, 3),
        ("only-skipped", runs, jobs_skip, 0, None),
        ("successful-job-without-publication", runs, superseded, 0, None),
        ("manual-rollback-cannot-count-as-fresh", manual, jobs_hit, 0, None),
        ("no-runs", {"workflow_runs": []}, jobs_hit, 0, None),
    ]
    global GH
    saved = GH
    with tempfile.TemporaryDirectory() as tmp:
        for name, r, j, rc, want in cases:
            stub = os.path.join(tmp, f"gh-{name}")
            with open(stub, "w") as f:
                f.write("#!/usr/bin/env bash\ncase \"$2\" in\n"
                        f"  */jobs*) printf '%s' {json.dumps(json.dumps(j))} ;;\n"
                        f"  *) printf '%s' {json.dumps(json.dumps(r))}; exit {rc} ;;\nesac\n")
            os.chmod(stub, 0o755)
            GH = stub
            got = recently_published("o/r", 1, 55)  # run 1 is "this" run and must be ignored
            ok = got == want
            print(f"{'ok  ' if ok else 'FAIL'} {name} (got {got})")
            fails += not ok
        # an API failure must fail OPEN (raise -> main() returns 1 = collect)
        stub = os.path.join(tmp, "gh-error")
        with open(stub, "w") as f:
            f.write("#!/usr/bin/env bash\necho 'gh: API rate limit exceeded (HTTP 403)' >&2; exit 1\n")
        os.chmod(stub, 0o755)
        GH = stub
        rc = main(["x", "o/r", "1"])
        print(f"{'ok  ' if rc == 1 else 'FAIL'} api-error-fails-open (rc {rc})")
        fails += rc != 1
    GH = saved
    return fails


def main(argv):
    if argv[1:] == ["--self-test"]:
        return 1 if self_test() else 0
    args = argv[1:]
    window = 55
    if "--window" in args:
        i = args.index("--window")
        window = int(args[i + 1])
        del args[i:i + 2]
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        hit = recently_published(args[0], args[1], window)
    except Exception as e:  # fail open toward collecting
        print(f"::warning::could not tell whether test-intelligence is fresh ({e}); collecting", file=sys.stderr)
        return 1
    if hit:
        print(f"test-intelligence published by run {hit} within {window} min; skipping this collection")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
