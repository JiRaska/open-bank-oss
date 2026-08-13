#!/usr/bin/env python3
"""Nothing notices when a PR is merged past an actively FAILING required check (issue #4240).

WHY THIS EXISTS
---------------
`main-protection` is a ruleset, and a ruleset can be bypassed. When it is, GitHub records the
bypass in `repos/<owner>/<repo>/rulesets/rule-suites` and tells nobody else: no PR carries the
record, no check turns red, no notification is sent, and the merge commit on `main` looks
exactly like every other merge commit. On 2026-08-08 #4215 merged while `Validate manifests`
was FAILING and the first anyone knew of it was a human reading the Actions tab.

Before this script, `grep -rl 'required_status_checks\\|statusCheckRollup' .github/scripts
.github/workflows` returned exactly one hit -- a comment inside agent-review.yml. No workflow in
this repository has ever read a merged PR's required contexts.

THE MEASUREMENT THAT DEFINES THE SCOPE, and the trap inside it
--------------------------------------------------------------
`rulesets/rule-suites` defaults to `time_period=day`. The unparameterised query therefore
returns ONE row for the incident that prompted #4240, and that single row reads precisely like
confirmation that the incident was isolated. It is not. Re-measured 2026-08-09 with
`?time_period=month&per_page=100 --paginate`, deduplicated by suite id:

    2890 raw rows -> 37 distinct `bypass` suites
    29 of those carry a FAILED `required_status_checks` evaluation
     7 of those 29 were merged past a check that was actively FAILING:

        pushed_at         head sha    PR     failing required check
        2026-07-14 09:00  200a6960   #1030   "OPA policy gate" is failing.
        2026-07-16 10:51  ab245ab3   #1187   "OPA policy gate" is failing.
        2026-08-07 09:30  0abed33e   #3658   2 of 5 ...: 1 expected and 1 failing.
        2026-08-07 09:45  3208109c   #3771   2 of 5 required status checks are failing.
        2026-08-07 10:34  ee974ea3   #3686   2 of 5 required status checks are failing.
        2026-08-07 17:07  6b3594b2   #4054   2 of 5 required status checks are failing.
        2026-08-08 17:49  4405a74f   #4215   "Validate manifests" is failing.

    (Suite ids are deliberately not tabulated here: gitleaks' `rod-cislo` rule matches a bare
    10-digit number, so committing them fails the required Secret-scan check on a shape with no
    secret in it. The head sha identifies the merge and is greppable.)

Four of the seven landed on 2026-08-07 alone. A detector that reports ONE is not detecting the
incident, it is reproducing the default-window bug that hid the other six. D1 below makes that
a property of the committed workflow rather than a thing someone remembered.

WHY `result: bypass` IS THE WRONG KEY -- the noise half
-------------------------------------------------------
The other 22 of those 29 are benign and vastly outnumber the signal. A required context that has
not reported yet is `expected`; one still running is `in progress`. Both are recorded as a FAILED
`required_status_checks` evaluation on a `bypass` suite, and neither is a merge past a red check:

    "Required status check \"all-green\" is expected."             <- 19 rows, benign
    "Required status check \"all-green\" is in progress."          <- benign
    "3 of 5 required status checks have not succeeded: 1 expected."<- benign
    "Required status check \"Validate manifests\" is failing."     <- SIGNAL
    "2 of 5 ... have not succeeded: 1 expected and 1 failing."     <- SIGNAL (mixed row)

So the classifier keys on the word `failing` in the per-evaluation `details` string, never on
`result == "bypass"` and never on the evaluation result alone. A detector that flags all 22
benign rows is noise, and noise is ignored, which returns the repo to exactly where #4240 found
it. The mixed row is why the test cannot be `details.endswith("are failing.")`.

ESCALATION, not redness
-----------------------
This runs on a schedule and on push to main. A red scheduled workflow is addressed to nobody --
that is the #4019 lesson this repo already paid for, and reproducing it here would mean the
detector for an invisible failure is itself an invisible failure. So the runtime lane opens or
refreshes ONE issue carrying the marker `<!-- merged-past-red-check -->`, reopens it if it was
hand-closed while offending merges are still inside the window, and closes it itself when the
window comes back clean. Refreshed in place, never stacked.

The escalation is self-limiting without any stored state: the window is `month`, so a merge
ages out of the report on its own and the issue closes when the last one does.

TOKEN IDENTITY -- read this before assuming the runtime lane works
------------------------------------------------------------------
`rulesets/rule-suites` requires the repository **Administration: read** permission. The Actions
`permissions:` block has no `administration:` key at all -- there is no way to grant a job's
`GITHUB_TOKEN` that scope, so the workflow token CANNOT read this endpoint no matter how the job
is written. The runtime lane therefore needs a PAT (or GitHub App token) in
`secrets.RULESET_AUDIT_TOKEN`, and the workflow's `capability` step probes the endpoint with the
plain `GITHUB_TOKEN` on every PR so that this claim is measured under the identity CI actually
uses, not asserted from documentation. When the secret is absent the runtime lane SKIPS loudly
rather than reporting a clean window -- a permission-shaped absence is indistinguishable from a
real one, and it always fails in the direction of a confident wrong answer.

THE TWO LANES
-------------
DECLARATION (`--check-declaration`) is a property of the committed workflow file: offline,
deterministic, and BLOCKING. Its binding copy is the `merged-past-red-check-declaration` gate in
.github/gates/gates.yaml, which runs unconditionally inside `Validate manifests`. A
`paths:`-filtered or scheduled workflow can never be a required context here, so the workflow's
own pull_request lane is only a fast echo. The rules:

  D1  The workflow must pass an EXPLICIT `time_period=` to rule-suites. The default is `day`,
      which hid six of the seven. `time_period=day` is itself rejected.
  D2  It must paginate. 37 bypass suites hide inside 2890 rows; one un-paginated page of 100
      covers roughly nine hours of this repo's push rate.
  D3  It must classify with THIS script, and this script must key on the `failing` detail.
      D3 rejects a workflow that decides anything from `result: bypass` in a `run:` block.
  D4  It must escalate: `issues: write` plus a step that writes an issue. A workflow that only
      goes red rebuilds the blind spot.
  D5  It must be scheduled. A push-triggered-only detector cannot notice a bypass on a day
      nobody pushes, and the bypass is by definition the last push.
  D6  It must read the ruleset token from a secret and skip loudly when it is unset, never
      fall back to GITHUB_TOKEN and report an empty window.

WHAT THIS CANNOT DO
-------------------
It cannot see a merge that was NOT a ruleset bypass: a check that reported green and was wrong,
or a required context that was never required, leaves no rule-suite row. It cannot look further
back than GitHub's retention for the endpoint (`month` is the longest window the API offers,
and rows age out). And it says nothing about WHY the bypass happened -- an admin merge made
deliberately and one made by reflex are the same row. It reports; a human judges.

USAGE
    .github/scripts/check-merged-past-red-check.py --self-test
    .github/scripts/check-merged-past-red-check.py --check-declaration
    .github/scripts/check-merged-past-red-check.py --scan --repo O/R --json /tmp/v.json
    .github/scripts/check-merged-past-red-check.py --scan --from-file suites.json   # offline

EXIT CODES
    0  clean / declaration holds / self-test passed
    1  the detector could not answer (a failure of THIS SCRIPT, never a "clean" verdict)
    2  at least one merge past an actively failing required check was found
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = ".github/workflows/merged-past-red-check-watch.yml"

# The one classification rule. `failing` appears in every signal detail string and in none of
# the benign ones; `0 failing` is excluded so a future GitHub phrasing cannot invert the sense.
FAILING_RE = re.compile(r"\bfailing\b")
ZERO_FAILING_RE = re.compile(r"\b0 failing\b")

STATUS_RULE = "required_status_checks"

DEFAULT_PERIOD = "month"


def list_url(repo: str, period: str) -> str:
    """The ONE place the rule-suites LIST url is built. D1 exercises this function rather
    than grepping for `time_period`: the first draft of D1 was a whole-file grep and it
    flagged its own explanatory prose, the #2450 collision. A grep cannot distinguish the
    thing from the prose about the thing — so the rule calls the construct instead."""
    return f"repos/{repo}/rulesets/rule-suites?time_period={period}&per_page=100"


def detail_url(repo: str, suite_id: int) -> str:
    """The per-suite DETAIL endpoint. Takes no window; it is the only source of
    `rule_evaluations`, which the list endpoint omits entirely."""
    return f"repos/{repo}/rulesets/rule-suites/{suite_id}"


# --------------------------------------------------------------------------------------
# classifier
# --------------------------------------------------------------------------------------
def failing_details(suite: dict[str, Any]) -> list[str]:
    """Return the required-status-check details that describe an ACTIVELY FAILING check.

    Keys on the per-evaluation `details` string, never on the suite's `result`. See the module
    header: 22 of 29 failed status-check evaluations in the measured month were `expected` or
    `in progress` and are not merges past a red check.
    """
    out: list[str] = []
    for ev in suite.get("rule_evaluations") or []:
        if ev.get("rule_type") != STATUS_RULE:
            continue
        if ev.get("result") != "fail":
            continue
        details = (ev.get("details") or "").strip()
        if not details:
            continue
        if ZERO_FAILING_RE.search(details):
            continue
        if FAILING_RE.search(details):
            out.append(details)
    return out


def is_merge_past_red(suite: dict[str, Any]) -> bool:
    """A bypass onto a protected ref that carried an actively failing required check."""
    if suite.get("result") != "bypass":
        return False
    return bool(failing_details(suite))


def offenders(suites: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Deduplicate by suite id (the paginated endpoint repeats rows), classify, sort by time."""
    seen: dict[int, dict[str, Any]] = {}
    for s in suites:
        sid = s.get("id")
        if sid is None:
            continue
        # A later copy may carry the detail payload the list endpoint omits.
        if sid not in seen or (s.get("rule_evaluations") and not seen[sid].get("rule_evaluations")):
            seen[sid] = s
    found = []
    for s in sorted(seen.values(), key=lambda x: (x.get("pushed_at") or "", x.get("id") or 0)):
        if is_merge_past_red(s):
            found.append(
                {
                    "suite_id": s.get("id"),
                    "pushed_at": s.get("pushed_at"),
                    "actor": s.get("actor_name"),
                    "ref": s.get("ref"),
                    "head_sha": s.get("after_sha"),
                    "details": failing_details(s),
                }
            )
    return found


# --------------------------------------------------------------------------------------
# network lane
# --------------------------------------------------------------------------------------
def _gh(args: list[str], token: str | None) -> str:
    env = dict(os.environ)
    if token:
        env["GH_TOKEN"] = token
    p = subprocess.run(["gh", *args], capture_output=True, text=True, env=env)
    if p.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)} failed ({p.returncode}): {p.stderr.strip()[:400]}")
    return p.stdout


def fetch_suites(repo: str, period: str, token: str | None) -> list[dict[str, Any]]:
    """List every rule suite in the window, then fetch each BYPASS suite's detail payload.

    The list endpoint carries no `rule_evaluations`; only `rule-suites/<id>` does. Fetching
    details for the bypass rows alone keeps this at ~40 calls rather than ~2900.
    """
    raw = _gh(["api", "--paginate", list_url(repo, period)], token)
    listed = json.loads(raw) if raw.strip().startswith("[") else []
    ids = sorted({s["id"] for s in listed if s.get("result") == "bypass"})
    return [json.loads(_gh(["api", detail_url(repo, sid)], token)) for sid in ids]


def resolve_pr(repo: str, sha: str, token: str | None) -> int | None:
    try:
        data = json.loads(_gh(["api", f"repos/{repo}/commits/{sha}/pulls"], token))
    except RuntimeError:
        return None
    return data[0]["number"] if data else None


def scan(args: argparse.Namespace) -> int:
    if args.from_file:
        suites = json.loads(Path(args.from_file).read_text())
        if isinstance(suites, dict):
            suites = [suites]
        found = offenders(suites)
    else:
        token = os.environ.get(args.token_env) or None
        if not token and args.require_token:
            print(
                f"::error::{args.token_env} is unset. `rulesets/rule-suites` needs the "
                "Administration:read permission, which a workflow GITHUB_TOKEN cannot hold. "
                "Refusing to report a window as clean that was never read.",
                file=sys.stderr,
            )
            return 1
        try:
            suites = fetch_suites(args.repo, args.period, token)
        except RuntimeError as exc:
            print(f"::error::could not read rule suites: {exc}", file=sys.stderr)
            return 1
        found = offenders(suites)
        for f in found:
            f["pr"] = resolve_pr(args.repo, f["head_sha"], token)

    verdict = {
        "repo": args.repo,
        "period": args.period,
        "suites_examined": len(suites),
        "offenders": found,
        "status": "dirty" if found else "clean",
    }
    if args.json:
        Path(args.json).write_text(json.dumps(verdict, indent=2))

    if not found:
        print(f"clean: no merge past an actively failing required check in the last {args.period}")
        print(f"       ({len(suites)} bypass suites examined)")
        return 0

    print(f"{len(found)} merge(s) past an ACTIVELY FAILING required check in the last {args.period}:")
    for f in found:
        pr = f"#{f['pr']}" if f.get("pr") else "(no PR)"
        print(f"  {f['pushed_at']}  suite {f['suite_id']}  {str(f['head_sha'])[:9]}  {pr}")
        for d in f["details"]:
            print(f"      {d}")
    return 2


# --------------------------------------------------------------------------------------
# declaration lane
# --------------------------------------------------------------------------------------
def _strip_comments(text: str) -> str:
    """A whole-file grep matches the PROSE explaining a rule as readily as the rule."""
    return "\n".join(re.sub(r"(?<!\S)#.*$", "", line) for line in text.splitlines())


# The declaration lane's corpus: one workflow file, six rules applied to it. Reported through
# gatelib.subjects so a vanished workflow reads as "this gate examined nothing" rather than as a
# pass — the #4339 failure mode, where a moved path turns a gate into a green no-op.
RULE_IDS = ("D1", "D2", "D3", "D4", "D5", "D6")


def check_declaration(root: Path, report_subjects: bool = False) -> tuple[int, list[str]]:
    path = root / WORKFLOW
    if not path.exists():
        if report_subjects:
            gatelib.subjects(0, "rules applied (the watch workflow is MISSING)")
        return 1, [f"D0: {WORKFLOW} does not exist — the detector has no runtime lane."]
    if report_subjects:
        gatelib.subjects(len(RULE_IDS), "declaration rules applied to the watch workflow")
    raw = path.read_text()
    body = _strip_comments(raw)
    fails: list[str] = []

    # D1a — behavioural, on the construct: the one url builder must carry an explicit,
    # non-default window. Never a whole-file grep for `time_period`; the first draft of this
    # rule was exactly that and it flagged its own explanatory prose (the #2450 collision).
    built = list_url("o/r", DEFAULT_PERIOD)
    if "time_period=" not in built or re.search(r"time_period=day\b", built):
        fails.append(
            "D1: the rule-suites list url carries no explicit window, or carries the default "
            "`day` — the window that returned 1 row where the true count was 7."
        )

    # D1b — any INLINE rule-suites call in the workflow. Scoped to lines that actually invoke
    # the API (`gh api`), so prose about the endpoint cannot match.
    for line in body.splitlines():
        if "gh api" not in line or "rule-suites" not in line:
            continue
        if "time_period=" not in line or re.search(r"time_period=day\b", line):
            fails.append(f"D1: inline rule-suites call without an explicit non-day window: {line.strip()[:90]}")

    if "--paginate" not in body and "per_page" not in body:
        fails.append("D2: no pagination — 37 bypass suites hide inside 2890 rows.")

    if "check-merged-past-red-check.py" not in body:
        fails.append("D3: the workflow does not call check-merged-past-red-check.py.")
    if re.search(r"result.{0,6}==.{0,6}[\"']bypass", body):
        fails.append(
            "D3: the workflow decides from `result == bypass` — 22 of 29 such rows are benign "
            "`expected`/`in progress`. Classification belongs in the script's `failing` key."
        )

    if not re.search(r"^\s*issues:\s*write\s*$", body, re.M):
        fails.append("D4: no `issues: write` — a workflow that only goes red escalates to nobody.")
    if "github-script" not in body:
        fails.append("D4: no escalation step.")

    if not re.search(r"^\s*schedule:\s*$", body, re.M):
        fails.append("D5: not scheduled — the bypass is by definition the last push.")

    if "RULESET_AUDIT_TOKEN" not in body:
        fails.append("D6: the ruleset token is not read from a secret.")
    if "--require-token" not in body:
        fails.append(
            "D6: the scan is not run with --require-token, so a missing secret would report "
            "an unread window as clean."
        )

    # THIS script must still be the thing that keys on `failing`, not on the suite result.
    if not FAILING_RE.search("is failing.") or is_merge_past_red(
        _suite(0, "bypass", 'Required status check "all-green" is expected.')
    ):
        fails.append("D3: the classifier no longer keys on the `failing` detail.")

    return (1 if fails else 0), fails


# --------------------------------------------------------------------------------------
# self-test
# --------------------------------------------------------------------------------------
def _suite(sid: int, result: str, details: str | None, rule: str = STATUS_RULE) -> dict[str, Any]:
    evs = [{"rule_type": "required_signatures", "result": "pass"}]
    if details is not None:
        evs.append({"rule_type": rule, "result": "fail", "details": details})
    return {
        "id": sid,
        "result": result,
        "pushed_at": f"2026-08-07T09:{sid % 60:02d}:00+02:00",
        "after_sha": f"{sid:040x}",
        "actor_name": "JiRaska",
        "ref": "refs/heads/main",
        "rule_evaluations": evs,
    }


# Every SIGNAL detail string observed in the real month window, plus every BENIGN one. Both
# lists are verbatim API output, not paraphrase: the mixed row ("1 expected and 1 failing")
# is the case a `.endswith("are failing.")` test gets wrong, and it is a real row (#3658).
SIGNAL_DETAILS = [
    'Required status check "OPA policy gate" is failing.',
    'Required status check "Validate manifests" is failing.',
    "2 of 5 required status checks are failing.",
    "2 of 5 required status checks have not succeeded: 1 expected and 1 failing.",
]
BENIGN_DETAILS = [
    'Required status check "all-green" is expected.',
    'Required status check "all-green" is in progress.',
    'Required status check "Validate manifests" is in progress.',
    "4 of 5 required status checks have not succeeded: 2 expected.",
    "5 of 5 required status checks are expected.",
    "3 of 5 required status checks have not succeeded: 1 expected.",
    "2 of 5 required status checks have not succeeded: 0 failing.",
]


def self_test() -> int:
    bad: list[str] = []

    # 1. Every real signal string is flagged.
    for i, d in enumerate(SIGNAL_DETAILS):
        if not is_merge_past_red(_suite(100 + i, "bypass", d)):
            bad.append(f"signal NOT flagged: {d!r}")

    # 2. Every real benign string is silent. This is the half that decides whether the
    #    detector is usable: 22 of 29 real rows look like these.
    for i, d in enumerate(BENIGN_DETAILS):
        if is_merge_past_red(_suite(200 + i, "bypass", d)):
            bad.append(f"BENIGN flagged: {d!r}")

    # 3. `result: bypass` alone is not the key — a bypass of the review rule with the status
    #    checks green is not this defect (real shape: the 2026-07-10 bypass at 5431d982, #1030's neighbour).
    review_only = {
        "id": 300,
        "result": "bypass",
        "pushed_at": "2026-07-10T09:44:54+02:00",
        "after_sha": "5431d98",
        "ref": "refs/heads/main",
        "rule_evaluations": [
            {
                "rule_type": "pull_request",
                "result": "fail",
                "details": "1 review requesting changes by reviewers with write access.",
            },
            {"rule_type": STATUS_RULE, "result": "pass"},
        ],
    }
    if is_merge_past_red(review_only):
        bad.append("a review-rule bypass with GREEN status checks was flagged")

    # 4. The word `failing` on a DIFFERENT rule type must not count.
    if is_merge_past_red(_suite(400, "bypass", "something is failing.", rule="pull_request")):
        bad.append("a non-status-check rule carrying the word `failing` was flagged")

    # 5. A suite that PASSED, or one that was blocked (`fail`), is not a merge past a red check.
    for res in ("pass", "fail"):
        if is_merge_past_red(_suite(500, res, "2 of 5 required status checks are failing.")):
            bad.append(f"a `{res}` suite was flagged as a bypass")

    # 6. Deduplication: the paginated endpoint repeats rows, and a detail-carrying copy must
    #    win over a bare list-endpoint copy of the same id.
    bare = {"id": 600, "result": "bypass", "pushed_at": "2026-08-07T09:00:00+02:00"}
    rich = _suite(600, "bypass", SIGNAL_DETAILS[0])
    for order in ([bare, rich], [rich, bare]):
        got = offenders(order)
        if len(got) != 1:
            bad.append(f"dedup produced {len(got)} rows for one suite id (order {order[0] is bare})")

    # 7. Mixed batch: the real ratio. 4 signal + 7 benign in, exactly 4 out, in time order.
    batch = [_suite(700 + i, "bypass", d) for i, d in enumerate(SIGNAL_DETAILS)]
    batch += [_suite(800 + i, "bypass", d) for i, d in enumerate(BENIGN_DETAILS)]
    got = offenders(batch)
    if len(got) != len(SIGNAL_DETAILS):
        bad.append(f"mixed batch: expected {len(SIGNAL_DETAILS)} offenders, got {len(got)}")

    # 8. The declaration lane must FAIL on a workflow that reproduces the default-window bug.
    #    A rule that has only ever passed is unfalsified.
    import tempfile

    broken = (
        "on:\n  push:\n    branches: [main]\n"
        "jobs:\n  x:\n    steps:\n"
        "      - run: gh api repos/o/r/rulesets/rule-suites\n"
    )
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / WORKFLOW
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(broken)
        code, fails = check_declaration(Path(td))
        if code == 0:
            bad.append("declaration passed a workflow with no time_period, no pagination, no issue")
        for want in ("D1", "D2", "D3", "D4", "D5", "D6"):
            if not any(f.startswith(want) for f in fails):
                bad.append(f"declaration did not raise {want} against the deliberately broken file")

        # 8b. A workflow whose ONLY defect is the default window must still fail D1 — the
        #     single-rule falsification, not just the everything-broken one.
        p.write_text(broken.replace("rule-suites", "rule-suites?time_period=day"))
        _, fails = check_declaration(Path(td))
        if not any(f.startswith("D1") for f in fails):
            bad.append("declaration accepted an explicit `time_period=day`")

        # 8c. Missing workflow is a failure, not a pass.
        p.unlink()
        if check_declaration(Path(td))[0] == 0:
            bad.append("declaration passed with the workflow file absent")

    # 9. The real declaration must hold against the committed tree.
    code, fails = check_declaration(REPO)
    if code != 0:
        bad += [f"committed workflow violates {f}" for f in fails]

    if bad:
        for b in bad:
            print(f"self-test FAILED: {b}", file=sys.stderr)
        return 1
    print(
        f"self-test: {len(SIGNAL_DETAILS)} signal + {len(BENIGN_DETAILS)} benign detail strings, "
        "dedup, rule-type scoping, suite-result scoping and 3 declaration falsifications all "
        "behaved as required"
    )
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-declaration", action="store_true")
    ap.add_argument("--scan", action="store_true")
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--repo", default=os.environ.get("GH_REPO", "JiRaska/open-bank-oss"))
    ap.add_argument("--period", default="month", choices=["day", "week", "month"])
    ap.add_argument("--from-file", help="classify saved suite details offline (falsification)")
    ap.add_argument("--token-env", default="RULESET_AUDIT_TOKEN")
    ap.add_argument("--require-token", action="store_true")
    ap.add_argument("--json", help="write the verdict as JSON here")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if args.check_declaration:
        code, fails = check_declaration(Path(args.root), report_subjects=True)
        for f in fails:
            print(f"::error::{f}")
        if code == 0:
            print(f"declaration holds: {WORKFLOW} satisfies D1-D6")
        return code
    if args.scan:
        return scan(args)
    ap.error("pick one of --self-test / --check-declaration / --scan")
    return 1


if __name__ == "__main__":
    sys.exit(main())
