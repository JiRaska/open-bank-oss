#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# AI ROLLOUT STATUS EVIDENCE — a PR that flips ai-rollout.yaml's D5 decision to `built`
# (or roadmap phase 5 to `complete`) must carry live, VERIFIED attestation evidence in
# the SAME FILE (issue #12923 gap 7).
#
# WHY THIS EXISTS
#   openbank-libs/governance/ai-rollout.yaml is the one authoritative narrative for the
#   ADR-0031 AI-governance rollout, and its D5 decision ("AI-attributed, tamper-evident
#   audit") is today honestly `partial`: the hash chain and KMS anchor plumbing are built,
#   but the independently attested runtime evidence of KMS-signed anchors is still listed
#   under `planned:`. Nothing stops a future PR from editing one word — `partial` ->
#   `built` — and letting the admin-ui route, the compliance rows and every reader of the
#   snapshot JSON conclude the attestation gap is closed when it is not. A status that
#   can be flipped by prose alone is not a status, it is a hope.
#
#   This gate makes the flip falsifiable: the SAME commit that claims `built` must also
#   add an `evidence:` mapping naming the attestation workflow, a concrete workflow run,
#   a VERIFIED verdict, and a verified_at date no older than 14 days — and the gate then
#   reads the LIVE GitHub API to confirm the run exists, belongs to the declared
#   workflow, concluded success (the attestation workflow's verify step exits non-zero
#   on any verdict other than VERIFIED, so `conclusion: success` IS the VERIFIED claim),
#   and is itself fresh. Declared-but-unverified must never render as verified.
#
# SCOPE — when the gate judges, and when it steps aside
#   triggered:   the PR touches ai-rollout.yaml AND the head version claims D5 `built`
#                OR roadmap phase 5 `complete` (either claim, either direction of edit).
#                "Becomes" is deliberate: a PR that KEEPS the claim but edits the file
#                must still carry fresh evidence — a 40-day-old verified_at must not
#                survive a touch of the claim.
#   not triggered: the file is unchanged, or it is changed with no built/complete claim
#                (today's honest `partial` state passes with no evidence at all).
#
# DECLARED-UNDER-HEAD — the workflow may not exist on main yet
#   The attestation workflow (audit-anchor-attestation.yml) ships on a branch that is
#   unmerged at the time of writing. The workflow-file existence check therefore reads
#   the PR's OWN checkout (the checked-out head in CI), never main.
#
# FAIL-CLOSED API RULE — deviation from the Unreadable/UNRESOLVED convention, on purpose
#   Sibling live-API gates (check-ruleset-bypass-actors.py) distinguish "the API was
#   rate-limited / token cannot see the field" (UNRESOLVED, exit 0) from "the API broke"
#   (exit 1), because their subject is read on EVERY run and an org-wide rate limit must
#   not redden unrelated PRs. This gate reads the API ONLY when a PR claims `built` —
#   an event that is expected roughly once. When the claim is made, "could not check
#   the evidence" must read exactly like "evidence missing": the claim is refused until
#   the run can be verified. An API error while triggered is therefore exit 1 with the
#   reason, never exit 0. (Undetermined — cannot read the PR's own diff/file — stays
#   exit 2, the third state: nothing was claimed, nothing was checked.)
#
# FALSIFIABILITY
#   --self-test drives every clause over hermetic fixtures in temp dirs with a stubbed
#   gh seam and a pinned clock: each failure mode (missing block, missing keys, wrong
#   verdict, workflow absent, stale verified_at, future verified_at, unreadable/absent
#   run, failed run, run from the wrong workflow, stale run) is asserted on the REASON
#   STRING, not just the exit code — two clauses agreeing on a verdict is how a clause
#   becomes unfalsifiable while the suite stays green — plus the positive case where
#   complete, valid evidence PASSES.
#
# USAGE
#   python3 .github/scripts/check-ai-rollout-status-evidence.py            # PR from env
#   python3 .github/scripts/check-ai-rollout-status-evidence.py --pr 6410
#   python3 .github/scripts/check-ai-rollout-status-evidence.py --self-test
#   (read-only: it only GETs from the API and never posts)
#
# EXIT CODES
#   0  not triggered (file unchanged, or no built/complete claim), or claim backed by
#      valid, live-verified evidence
#   1  triggered and the evidence is missing, stale, non-VERIFIED, names no workflow,
#      or the live run read failed (FAIL CLOSED — see the API rule above)
#   2  could not determine the PR's diff or read ai-rollout.yaml — NOT a clean verdict

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gatelib  # noqa: E402  (path must be set first)

REPO = "JiRaska/open-bank-oss"
ROLLOUT = "openbank-libs/governance/ai-rollout.yaml"
WORKFLOWS_DIR = ".github/workflows"

D5_ID = "D5"
ROADMAP_PHASE = 5
MAX_EVIDENCE_AGE = timedelta(days=14)
# Small allowance for clock skew between the attest runner and the gate runner.
FUTURE_SKEW = timedelta(minutes=5)
GH_TIMEOUT_SECONDS = 15

# The attestation workflow's verdict vocabulary (its verdict.json), mirrored here so the
# required evidence value spells exactly what a green attestation run reports.
VERIFIED = "VERIFIED"

EVIDENCE_KEYS = ("attestation_workflow", "last_verified_run", "verdict", "verified_at")


class Undetermined(Exception):
    """The verdict could not be computed (no claim was examined — the third state)."""


class FailClosed(Exception):
    """Triggered, and the live evidence read failed — refused, never passed (see header)."""


# --------------------------------------------------------------------------- pure logic


def parse_rollout(text, where):
    import yaml

    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as e:
        raise Undetermined(f"could not parse {where}: {e}") from e
    if not isinstance(doc, dict):
        raise Undetermined(f"{where} is not a YAML mapping")
    return doc


def d5_entry(doc):
    decisions = doc.get("decisions")
    if not isinstance(decisions, list):
        return None
    for item in decisions:
        if isinstance(item, dict) and item.get("id") == D5_ID:
            return item
    return None


def roadmap_entry(doc, number):
    roadmap = (doc.get("phase") or {}).get("roadmap")
    if not isinstance(roadmap, list):
        return None
    for item in roadmap:
        if isinstance(item, dict) and item.get("number") == number:
            return item
    return None


def claims(doc):
    """Every 'D5 is done' claim the head document makes (both paths can fire at once)."""
    out = set()
    entry = d5_entry(doc)
    if entry and entry.get("status") == "built":
        out.add("decisions.D5.status=built")
    phase = roadmap_entry(doc, ROADMAP_PHASE)
    if phase and phase.get("status") == "complete":
        out.add("phase.roadmap[5].status=complete")
    return out


def subject_count(doc):
    """The corpus this gate reads: every decision and roadmap entry in the rollout file."""
    decisions = doc.get("decisions")
    roadmap = (doc.get("phase") or {}).get("roadmap")
    return (len(decisions) if isinstance(decisions, list) else 0) + (
        len(roadmap) if isinstance(roadmap, list) else 0
    )


def normalize_workflow(value):
    """The declared workflow name as a bare .github/workflows/ filename, or None if malformed."""
    if not isinstance(value, str) or not value.strip():
        return None
    name = value.strip()
    if name.startswith(WORKFLOWS_DIR + "/"):
        name = name[len(WORKFLOWS_DIR) + 1:]
    elif name.startswith("."):
        return None
    if not re.fullmatch(r"[A-Za-z0-9._-]+\.ya?ml", name):
        return None
    return name


RUN_ID_RE = re.compile(r"/actions/runs/(\d+)")


def extract_run_id(value):
    """A workflow run id from a bare id or any github.com run URL, or None if neither."""
    if isinstance(value, int) and value > 0:
        return value
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.isdigit():
        return int(text)
    match = RUN_ID_RE.search(text)
    if match:
        return int(match.group(1))
    return None


def parse_iso_utc(value):
    """An aware datetime for an ISO date/datetime string, or None if unparsable."""
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    # datetime.fromisoformat only learned 'Z' in 3.11; normalise so both spellings work.
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def evidence_failures(evidence, workflows_dir, now, gh):
    """Every reason the D5 evidence block fails, or [] when it fully verifies.

    Returns ALL failures rather than the first: a reason list that stops at the first hit
    cannot show that its later clauses are reachable, which is what the self-test asserts.
    `gh` is the live-API seam (self-test stubs it). A live read failure raises FailClosed —
    see the header's fail-closed API rule.
    """
    out = []
    if not isinstance(evidence, dict):
        return [
            "decisions.D5 carries no `evidence:` mapping — a `built` claim must name its "
            "attestation in the SAME file (attestation_workflow, last_verified_run, "
            "verdict, verified_at)"
        ]

    missing = [k for k in EVIDENCE_KEYS if k not in evidence]
    if missing:
        out.append(
            f"evidence block is missing key(s): {', '.join(missing)} — all of "
            f"{', '.join(EVIDENCE_KEYS)} are required"
        )

    workflow_name = normalize_workflow(evidence.get("attestation_workflow"))
    if workflow_name is None:
        out.append(
            f"evidence.attestation_workflow ({evidence.get('attestation_workflow')!r}) does not "
            f"name a .github/workflows/*.yml file"
        )
    elif not (workflows_dir / workflow_name).is_file():
        out.append(
            f"evidence.attestation_workflow names {WORKFLOWS_DIR}/{workflow_name}, which does "
            f"not exist in this PR's checkout"
        )

    verdict = evidence.get("verdict")
    if verdict != VERIFIED:
        out.append(
            f"evidence.verdict is {verdict!r}, must be {VERIFIED!r} — the attestation "
            f"workflow's own vocabulary; TAMPERED / UNVERIFIABLE / INPUT_ERROR are refusals, "
            f"not smaller truths"
        )

    verified_at = parse_iso_utc(evidence.get("verified_at"))
    if verified_at is None:
        out.append(f"evidence.verified_at ({evidence.get('verified_at')!r}) is not an ISO date")
    elif verified_at > now + FUTURE_SKEW:
        out.append(f"evidence.verified_at ({verified_at.date()}) is in the future")
    elif verified_at < now - MAX_EVIDENCE_AGE:
        out.append(
            f"evidence.verified_at ({verified_at.date()}) is older than "
            f"{MAX_EVIDENCE_AGE.days} days — re-run the attestation instead of reusing an old one"
        )

    run_id = extract_run_id(evidence.get("last_verified_run"))
    if run_id is None:
        out.append(
            f"evidence.last_verified_run ({evidence.get('last_verified_run')!r}) is neither a "
            f"workflow run id nor a run URL"
        )
    else:
        try:
            run = gh(f"repos/{REPO}/actions/runs/{run_id}")
        except Exception as e:  # noqa: BLE001 — ANY read failure while triggered is a refusal
            raise FailClosed(
                f"could not read workflow run {run_id} from the GitHub API to check "
                f"evidence.last_verified_run: {e}. FAIL CLOSED — an unverifiable claim "
                f"is not a verified one"
            ) from e
        if not isinstance(run, dict) or "conclusion" not in run:
            raise FailClosed(
                f"the API answer for run {run_id} carried no `conclusion` field — refusing to "
                f"read an unreadable run as evidence. FAIL CLOSED"
            )
        if run.get("conclusion") != "success" or run.get("status") != "completed":
            out.append(
                f"workflow run {run_id} concluded `{run.get('conclusion')}` (status "
                f"{run.get('status')!r}) — the attestation workflow exits non-zero on any "
                f"verdict but {VERIFIED}, so this run attests nothing"
            )
        run_path = str(run.get("path") or "")
        if workflow_name is not None and run_path and run_path != f"{WORKFLOWS_DIR}/{workflow_name}":
            out.append(
                f"workflow run {run_id} belongs to `{run_path}`, not the declared "
                f"{WORKFLOWS_DIR}/{workflow_name} — a run of some OTHER workflow is not "
                f"attestation evidence"
            )
        run_created = parse_iso_utc(run.get("created_at"))
        if run_created is None:
            raise FailClosed(
                f"workflow run {run_id} carried no parseable created_at — refusing to judge "
                f"its age. FAIL CLOSED"
            )
        if run_created < now - MAX_EVIDENCE_AGE:
            out.append(
                f"workflow run {run_id} is from {run_created.date()}, older than "
                f"{MAX_EVIDENCE_AGE.days} days — a stale run cannot attest the current state"
            )

    return out


def judge(base_doc, head_doc, changed_files, workflows_dir, now, gh):
    """(exit_code, message). Pure: the self-test drives this over fixtures, no network."""
    if ROLLOUT not in changed_files:
        return 0, f"{ROLLOUT} unchanged — nothing to judge"
    head_claims = claims(head_doc)
    if not head_claims:
        return 0, (
            f"{ROLLOUT} changed but makes no `built`/`complete` claim for D5 — today's "
            f"honest `partial` state passes without evidence"
        )
    gained = head_claims - claims(base_doc) if base_doc is not None else head_claims
    transition = "newly claims" if gained else "keeps"
    entry = d5_entry(head_doc) or {}
    failures = evidence_failures(entry.get("evidence"), workflows_dir, now, gh)
    claim_text = " and ".join(sorted(head_claims))
    if failures:
        lines = [
            f"D5 EVIDENCE REQUIRED: this PR {transition} {claim_text} in {ROLLOUT}, and the "
            f"attestation evidence in the same file does not verify:"
        ]
        lines += [f"  * {f}" for f in failures]
        lines.append(
            ""
            "Flip D5 only in the PR that carries the evidence: run the attestation workflow "
            "against the live sandbox, get a VERIFIED verdict, then add the evidence mapping "
            "(attestation_workflow, last_verified_run, verdict, verified_at) under decisions.D5 "
            "with a verified_at within 14 days. See issue #12923."
        )
        return 1, "\n".join(lines)
    run_id = extract_run_id((entry.get("evidence") or {}).get("last_verified_run"))
    return 0, (
        f"D5 claim ({claim_text}) {transition} and is backed by live VERIFIED attestation: "
        f"run {run_id} of the declared workflow, completed successfully within the 14-day window"
    )


# --------------------------------------------------------------------------- live reads


def gh(path):
    """`gh api <path>`, parsed JSON. Raises on ANY failure — callers while triggered
    convert this to FailClosed; nothing here ever degrades to a pass."""
    try:
        proc = subprocess.run(
            ["gh", "api", path], capture_output=True, text=True, timeout=GH_TIMEOUT_SECONDS
        )
    except subprocess.TimeoutExpired as e:
        raise RuntimeError(f"gh api {path} timed out after {GH_TIMEOUT_SECONDS}s") from e
    except (OSError, subprocess.SubprocessError) as e:
        raise RuntimeError(f"could not run `gh api {path}`: {e}") from e
    if proc.returncode != 0:
        raise RuntimeError(f"gh api {path} failed (rc={proc.returncode}): {proc.stderr.strip()}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"gh api {path} returned non-JSON: {e}") from e


def gh_raw(path):
    """`gh api <path>` with a raw body (for file contents at a ref). Raises on failure."""
    try:
        proc = subprocess.run(
            ["gh", "api", path, "-H", "Accept: application/vnd.github.raw+json"],
            capture_output=True, text=True, timeout=GH_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as e:
        raise RuntimeError(f"gh api {path} timed out after {GH_TIMEOUT_SECONDS}s") from e
    except (OSError, subprocess.SubprocessError) as e:
        raise RuntimeError(f"could not run `gh api {path}`: {e}") from e
    if proc.returncode != 0:
        raise RuntimeError(f"gh api {path} failed (rc={proc.returncode}): {proc.stderr.strip()}")
    return proc.stdout


def now_utc():
    """The clock seam — the self-test pins this so the 14-day windows are hermetic."""
    return datetime.now(timezone.utc)


def changed_files_local(base, head):
    proc = subprocess.run(
        ["git", "diff", "--name-only", "-z", base, head],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise Undetermined(f"git diff {base} {head} failed (rc={proc.returncode}): {proc.stderr.strip()}")
    return [f for f in proc.stdout.split("\0") if f]


def base_rollout_local(base):
    proc = subprocess.run(
        ["git", "show", f"{base}:{ROLLOUT}"], capture_output=True, text=True
    )
    if proc.returncode != 0:
        return None  # file absent (or unresolvable) on the base — treat as no claims there
    return parse_rollout(proc.stdout, f"{base}:{ROLLOUT}")


def fetch_event_pr(n):
    """Identity + diff from the checked-out PR merge, like check-agent-pr-guard: paths from
    the local git diff, the head file from the checkout, the base file from git show."""
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    base = os.environ.get("PR_DIFF_BASE", "")
    head = os.environ.get("GITHUB_SHA", "HEAD")
    if not event_path or not base or not os.path.exists(event_path):
        return None
    try:
        with open(event_path) as fh:
            pull = json.load(fh).get("pull_request") or {}
    except (OSError, ValueError, json.JSONDecodeError) as e:
        raise Undetermined(f"could not read pull_request event: {e}") from e
    if int(pull.get("number") or 0) != n:
        return None
    files = changed_files_local(base, head)
    head_text = Path(ROLLOUT).read_text(encoding="utf-8") if Path(ROLLOUT).is_file() else None
    if head_text is None:
        raise Undetermined(f"{ROLLOUT} is not present in this checkout")
    base_doc = base_rollout_local(base)
    return files, parse_rollout(head_text, ROLLOUT), base_doc


def fetch_pr(n):
    local = fetch_event_pr(n)
    if local is not None:
        return local
    pr = gh(f"repos/{REPO}/pulls/{n}")
    base_sha = ((pr.get("base") or {}).get("sha") or "")
    if not re.fullmatch(r"[0-9a-f]{40}", base_sha):
        raise Undetermined(f"PR #{n}: could not read the base SHA")
    pages = gh(f"repos/{REPO}/pulls/{n}/files?per_page=100") or []
    flat = pages if isinstance(pages, list) else [pages]
    files = [f["filename"] for f in flat if isinstance(f, dict) and f.get("filename")]
    head_sha = (pr.get("head") or {}).get("sha") or ""
    try:
        head_text = gh_raw(f"repos/{REPO}/contents/{ROLLOUT}?ref={head_sha}")
    except RuntimeError as e:
        raise Undetermined(f"PR #{n}: could not read {ROLLOUT} at head: {e}") from e
    try:
        base_text = gh_raw(f"repos/{REPO}/contents/{ROLLOUT}?ref={base_sha}")
        base_doc = parse_rollout(base_text, f"{ROLLOUT}@{base_sha[:10]}")
    except RuntimeError:
        base_doc = None
    return files, parse_rollout(head_text, ROLLOUT), base_doc


def resolve_pr_number(explicit):
    if explicit:
        return int(explicit)
    for var in ("PR_NUMBER", "GITHUB_PR_NUMBER"):
        if os.environ.get(var, "").strip().isdigit():
            return int(os.environ[var])
    match = re.match(r"refs/pull/(\d+)/", os.environ.get("GITHUB_REF", ""))
    if match:
        return int(match.group(1))
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    if event_path and os.path.exists(event_path):
        try:
            with open(event_path) as fh:
                number = (json.load(fh).get("pull_request") or {}).get("number")
            if number:
                return int(number)
        except (OSError, ValueError, json.JSONDecodeError):
            pass
    return None


# --------------------------------------------------------------------------- self-test


def _rollout_yaml(d5_status="partial", roadmap5_status="planned", evidence=None, d5_detail="x"):
    ev = ""
    if evidence is not None:
        import yaml

        dumped = yaml.safe_dump(evidence, default_flow_style=False, sort_keys=False)
        ev = "    evidence:\n" + "".join(
            f"      {line}" for line in dumped.splitlines(keepends=True)
        )
    return f"""\
adrRef: ADR-0031
phase:
  current: 2
  total: 5
  roadmap:
    - number: 1
      status: complete
      title: a
      outcome: b
    - number: 2
      status: active
      title: a
      outcome: b
    - number: 3
      status: blocked
      title: a
      outcome: b
    - number: 4
      status: blocked
      title: a
      outcome: b
    - number: 5
      status: {roadmap5_status}
      title: a
      outcome: b
decisions:
  - id: D1
    title: t
    status: built
    detail: x
  - id: D5
    title: AI-attributed, tamper-evident audit
    status: {d5_status}
    detail: {d5_detail}
{ev}"""


NOW = datetime(2026, 10, 15, 12, 0, 0, tzinfo=timezone.utc)
RECENT = "2026-10-14T07:23:00Z"          # 1 day before NOW
STALE = "2026-09-20T07:23:00Z"           # 25 days before NOW
FUTURE = "2026-10-16T00:00:00Z"          # after NOW
GOOD_RUN = 123456789
GOOD_EVIDENCE = {
    "attestation_workflow": "audit-anchor-attestation.yml",
    "last_verified_run": GOOD_RUN,
    "verdict": "VERIFIED",
    "verified_at": RECENT,
}


def self_test():
    import yaml

    failures = []
    passed = 0

    def check(name, want_code, want_sub, base_doc, head_text, changed, workflows, gh_stub):
        """One falsifiable clause: assert exit code AND the reason substring."""
        nonlocal passed
        head_doc = parse_rollout(head_text, "fixture head")
        with tempfile.TemporaryDirectory() as tmp:
            wf_dir = Path(tmp) / WORKFLOWS_DIR
            wf_dir.mkdir(parents=True)
            for fname in workflows:
                (wf_dir / fname).write_text("name: stub\n", encoding="utf-8")
            try:
                code, msg = judge(base_doc, head_doc, changed, wf_dir, NOW, gh_stub)
            except FailClosed as e:
                code, msg = 1, f"FAILCLOSED {e}"
        if code != want_code:
            failures.append(f"{name}: exit {code}, expected {want_code} — {msg}")
        elif want_sub not in msg:
            failures.append(f"{name}: message missing {want_sub!r} — {msg}")
        else:
            passed += 1

    base_partial = parse_rollout(_rollout_yaml(), "fixture base")

    def good_gh(path):
        assert f"runs/{GOOD_RUN}" in path, f"unexpected API read: {path}"
        return {
            "id": GOOD_RUN,
            "status": "completed",
            "conclusion": "success",
            "path": f"{WORKFLOWS_DIR}/audit-anchor-attestation.yml",
            "created_at": RECENT,
        }

    def broken_gh(path):
        raise RuntimeError(f"stubbed API outage on {path}")

    good_files = ["audit-anchor-attestation.yml"]
    touched = [ROLLOUT]

    # --- not triggered -------------------------------------------------------
    check(
        "file untouched: nothing to judge", 0, "nothing to judge",
        base_partial, _rollout_yaml(), ["some/other.yaml"], [], good_gh,
    )
    check(
        "file touched, still partial: honest edit passes without evidence",
        0, "no `built`/`complete` claim",
        base_partial, _rollout_yaml(d5_detail="wording polish"), touched, [], good_gh,
    )

    # --- triggered, evidence absent / malformed ------------------------------
    check(
        "D5 flipped to built with NO evidence block",
        1, "no `evidence:` mapping",
        base_partial, _rollout_yaml(d5_status="built"), touched, good_files, good_gh,
    )
    check(
        "roadmap phase 5 complete is a claim too (either claim triggers)",
        1, "no `evidence:` mapping",
        base_partial, _rollout_yaml(roadmap5_status="complete"), touched, good_files, good_gh,
    )
    check(
        "evidence missing three of four keys",
        1, "missing key(s): last_verified_run, verdict, verified_at",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={"attestation_workflow": "audit-anchor-attestation.yml"}),
        touched, good_files, good_gh,
    )
    check(
        "workflow name does not parse as a workflows file",
        1, "does not name a .github/workflows/*.yml file",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={
            **GOOD_EVIDENCE, "attestation_workflow": "not a workflow name"}),
        touched, [], good_gh,
    )
    check(
        "declared workflow absent from the PR checkout (declared-under-head)",
        1, "does not exist in this PR's checkout",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={
            **GOOD_EVIDENCE, "attestation_workflow": "some-other.yml"}),
        touched, good_files, good_gh,
    )
    check(
        "a TAMPERED verdict is a refusal, not a smaller truth",
        1, "must be 'VERIFIED'",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={**GOOD_EVIDENCE, "verdict": "TAMPERED"}),
        touched, good_files, good_gh,
    )
    check(
        "verified_at 25 days old is stale",
        1, "older than 14 days",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={**GOOD_EVIDENCE, "verified_at": STALE}),
        touched, good_files, good_gh,
    )
    check(
        "verified_at in the future is refused",
        1, "in the future",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={**GOOD_EVIDENCE, "verified_at": FUTURE}),
        touched, good_files, good_gh,
    )
    check(
        "unparsable verified_at is refused",
        1, "not an ISO date",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={**GOOD_EVIDENCE, "verified_at": "last wednesday"}),
        touched, good_files, good_gh,
    )
    check(
        "last_verified_run is neither an id nor a URL",
        1, "neither a workflow run id nor a run URL",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={**GOOD_EVIDENCE, "last_verified_run": "the good one"}),
        touched, good_files, good_gh,
    )

    # --- triggered, live run does not back the claim --------------------------
    check(
        "API outage while triggered FAILS CLOSED (never passes)",
        1, "FAIL CLOSED",
        base_partial, _rollout_yaml(d5_status="built", evidence=dict(GOOD_EVIDENCE)),
        touched, good_files, broken_gh,
    )

    def gh_run(conclusion="success", status="completed",
               path=f"{WORKFLOWS_DIR}/audit-anchor-attestation.yml", created=RECENT):
        def stub(p):
            return {"id": GOOD_RUN, "status": status, "conclusion": conclusion,
                    "path": path, "created_at": created}
        return stub

    check(
        "a FAILED run attests nothing",
        1, "concluded `failure`",
        base_partial, _rollout_yaml(d5_status="built", evidence=dict(GOOD_EVIDENCE)),
        touched, good_files, gh_run(conclusion="failure"),
    )
    check(
        "a run of a DIFFERENT workflow is not evidence",
        1, "not the declared",
        base_partial, _rollout_yaml(d5_status="built", evidence=dict(GOOD_EVIDENCE)),
        touched, good_files, gh_run(path=f"{WORKFLOWS_DIR}/ci.yml"),
    )
    check(
        "a 25-day-old run is stale even with a fresh verified_at",
        1, "stale run",
        base_partial, _rollout_yaml(d5_status="built", evidence=dict(GOOD_EVIDENCE)),
        touched, good_files, gh_run(created=STALE),
    )
    check(
        "an in-flight (not completed) run attests nothing yet",
        1, "concluded `None`",
        base_partial, _rollout_yaml(d5_status="built", evidence=dict(GOOD_EVIDENCE)),
        touched, good_files, gh_run(status="in_progress", conclusion=None),
    )

    # --- triggered, evidence valid → PASS ------------------------------------
    check(
        "complete valid evidence passes (positive case)",
        0, "backed by live VERIFIED attestation",
        base_partial, _rollout_yaml(d5_status="built", evidence=dict(GOOD_EVIDENCE)),
        touched, good_files, good_gh,
    )
    check(
        "run URL form of last_verified_run resolves to the same run",
        0, "backed by live VERIFIED attestation",
        base_partial,
        _rollout_yaml(d5_status="built", evidence={
            **GOOD_EVIDENCE,
            "last_verified_run": f"https://github.com/{REPO}/actions/runs/{GOOD_RUN}",
        }),
        touched, good_files, good_gh,
    )
    check(
        "a PR that KEEPS the claim still needs fresh evidence (touch re-falsifies)",
        1, "older than 14 days",
        parse_rollout(_rollout_yaml(d5_status="built"), "fixture base built"),
        _rollout_yaml(d5_status="built", evidence={**GOOD_EVIDENCE, "verified_at": STALE}),
        touched, good_files, good_gh,
    )
    check(
        "a PR that keeps the claim WITH fresh evidence passes",
        0, "keeps",
        parse_rollout(
            _rollout_yaml(d5_status="built", evidence=dict(GOOD_EVIDENCE)), "fixture base built"
        ),
        _rollout_yaml(d5_status="built", evidence=dict(GOOD_EVIDENCE)),
        touched, good_files, good_gh,
    )

    # An evidence block must not smuggle non-VERIFIED verdicts past the parser:
    # YAML booleans/other scalars never equal the string VERIFIED.
    weird = yaml.safe_load("verdict: yes")["verdict"]
    if weird == VERIFIED:
        failures.append("YAML-1.1 boolean `yes` would equal VERIFIED — quote the verdict")

    # The live rollout file must itself be parseable and non-claiming today: the self-test
    # must notice the day somebody flips D5 on main WITHOUT this gate's evidence shape.
    if os.path.exists(ROLLOUT):
        live = parse_rollout(Path(ROLLOUT).read_text(encoding="utf-8"), ROLLOUT)
        live_claims = claims(live)
        if live_claims and "evidence" not in (d5_entry(live) or {}):
            failures.append(
                f"live {ROLLOUT} already claims {sorted(live_claims)} without an evidence block"
            )
        count = subject_count(live)
        if count < 10:
            failures.append(f"live {ROLLOUT} yields only {count} subjects — the corpus read is broken")

    if failures:
        print("SELF-TEST FAILED — the gate is not falsifiable as written:")
        for failure in failures:
            print(f"  x {failure}")
        return 1
    print(
        f"self-test OK — {passed} cases, every clause independently reached "
        f"(trigger shapes, each evidence failure on its reason string, API-outage fail-closed, "
        f"positive case passes)"
    )
    return 0


# --------------------------------------------------------------------------- main


def main():
    ap = argparse.ArgumentParser(description="ai-rollout D5 status evidence gate")
    ap.add_argument("--pr")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    try:
        # The SUBJECT corpus is the rollout file, not the PR: a PR-scoped gate examines one
        # PR by definition, so counting PRs could never distinguish a working gate from a
        # broken one. What CAN silently collapse is the file read: if decisions/roadmap
        # vanish from ai-rollout.yaml, every clause matches nothing and the gate passes
        # everything while exiting 0. That is what min_subjects: is for.
        if os.path.exists(ROLLOUT):
            head_now = parse_rollout(Path(ROLLOUT).read_text(encoding="utf-8"), ROLLOUT)
            gatelib.subjects(subject_count(head_now), "rollout decisions + roadmap entries")
        n = resolve_pr_number(args.pr)
        if n is None:
            print("ai-rollout-status-evidence: no pull request in context — nothing to judge")
            return 0
        changed, head_doc, base_doc = fetch_pr(n)
        code, msg = judge(base_doc, head_doc, changed, Path(WORKFLOWS_DIR), now_utc(), gh)
    except Undetermined as e:
        # Third state: the verdict could not be computed. Not a pass — but also not a
        # finding about a claim nobody made, so the floor must not convert it to a red.
        gatelib.subjects_unresolved(str(e))
        print(f"::error::ai-rollout-status-evidence could not reach a verdict: {e}")
        return 2
    except FailClosed as e:
        print(f"::error::{e}")
        return 1
    print(f"ai-rollout-status-evidence: {msg}" if code == 0 else msg)
    return code


if __name__ == "__main__":
    sys.exit(main())
