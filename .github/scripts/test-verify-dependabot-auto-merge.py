"""Offline trust-boundary tests for verify-dependabot-auto-merge.py."""

import copy
import importlib.util
import json
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-dependabot-auto-merge.py")
SPEC = importlib.util.spec_from_file_location("verify_dependabot_auto_merge", SCRIPT)
assert SPEC and SPEC.loader
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)

REPO = "JiRaska/open-bank-oss"
BRANCH = "dependabot/npm_and_yarn/openbank-admin-ui/test-patch"
SHA = "a" * 40
WORKFLOWS = Path(__file__).parents[1] / "workflows"
ARMING_BLOCKS = (
    ("derived-artefact-autoheal.yml", "Enable auto-merge on the heal PR", "run"),
    ("dependabot-auto-merge-arm.yml", "Enable native auto-merge for the classified head", "run"),
    ("release-please.yml", "Enable auto-merge on open release PRs", "script"),
)


def workflow_block(workflow_name, step_name, field_name):
    """Extract a literal block from the named workflow step without a YAML dependency."""
    lines = (WORKFLOWS / workflow_name).read_text().splitlines()
    step_index = next(
        i for i, line in enumerate(lines) if line.strip() == f"- name: {step_name}"
    )
    step_indent = len(lines[step_index]) - len(lines[step_index].lstrip())
    end = next(
        (i for i in range(step_index + 1, len(lines))
         if lines[i].strip().startswith("-")
         and len(lines[i]) - len(lines[i].lstrip()) == step_indent),
        len(lines),
    )
    field_index = next(
        i for i in range(step_index + 1, end)
        if lines[i].strip() == f"{field_name}: |"
    )
    field_indent = len(lines[field_index]) - len(lines[field_index].lstrip())
    block = []
    for line in lines[field_index + 1:end]:
        if line.strip() and len(line) - len(line.lstrip()) <= field_indent:
            break
        block.append(line)
    return textwrap.dedent("\n".join(block)).rstrip() + "\n"


FAKE_GH = """#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
if os.environ.get('FAKE_GH_ERROR') == '1':
    sys.exit(7)
if args and args[0] == 'api':
    sys.stdout.write(open(os.environ['FAKE_PR_JSON'], encoding='utf-8').read())
elif args[:2] == ['pr', 'merge']:
    with open(os.environ['FAKE_GH_CALLS'], 'a', encoding='utf-8') as out:
        out.write(json.dumps(args) + '\\n')
else:
    sys.exit(9)
"""


def run_bash_workflow(workflow_name, step_name, pull, *, api_error=False, expected_sha=SHA):
    script = workflow_block(workflow_name, step_name, "run")
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        fakebin = root / "bin"
        fakebin.mkdir()
        fake_gh = fakebin / "gh"
        fake_gh.write_text(FAKE_GH, encoding="utf-8")
        fake_gh.chmod(0o755)
        pr_file = root / "pr.json"
        pr_file.write_text(json.dumps(pull), encoding="utf-8")
        calls = root / "calls.jsonl"
        env = {
            **os.environ,
            "PATH": f"{fakebin}:{os.environ['PATH']}",
            "GITHUB_REPOSITORY": REPO,
            "GH_REPO": REPO,
            "GH_TOKEN": "fixture-token",
            "PR": str(pull.get("number", 55)),
            "PR_NUMBER": str(pull.get("number", 55)),
            "HEAD_SHA": expected_sha,
            "FAKE_PR_JSON": str(pr_file),
            "FAKE_GH_CALLS": str(calls),
            "FAKE_GH_ERROR": "1" if api_error else "0",
        }
        result = subprocess.run(["bash", "-c", script], env=env, text=True,
                                capture_output=True, check=False)
        recorded = calls.read_text(encoding="utf-8").splitlines() if calls.exists() else []
        return result, [json.loads(line) for line in recorded]


def release_pr(**updates):
    value = {
        "number": 55,
        "node_id": "PR_node_55",
        "title": "chore(main): release openbank-example",
        "state": "open",
        "draft": False,
        "head": {"sha": SHA},
        "labels": [{"name": "autorelease: pending"}],
    }
    value.update(updates)
    return value


def run_release_workflow(listed, live=None, *, api_error=False):
    script = workflow_block(*ARMING_BLOCKS[2])
    harness = r"""
const fs = require('fs');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const armed = [];
const github = {
  paginate: async () => input.listed,
  rest: {pulls: {
    list: async () => ({data: input.listed}),
    get: async ({pull_number}) => {
      if (input.apiError) throw new Error('fixture API error');
      return {data: input.live[pull_number]};
    }
  }},
  graphql: async (_query, vars) => armed.push(vars.id)
};
const context = {repo: {owner: 'example', repo: 'repo'}};
const core = {info: () => {}, warning: () => {}};
const AsyncFunction = Object.getPrototypeOf(async function() {}).constructor;
(async () => {
  await new AsyncFunction('github', 'context', 'core', input.script)(github, context, core);
  process.stdout.write(JSON.stringify(armed));
})().catch(error => { console.error(error); process.exit(1); });
"""
    data = {"script": script, "listed": listed,
            "live": {str(p["number"]): p for p in (live or listed)},
            "apiError": api_error}
    result = subprocess.run(["node", "-e", harness], input=json.dumps(data), text=True,
                            capture_output=True, check=False)
    return result, json.loads(result.stdout) if result.returncode == 0 else None


def event():
    return {
        "repository": {"full_name": REPO},
        "workflow_run": {
            "id": 123,
            "run_attempt": 1,
            "event": "pull_request",
            "path": ".github/workflows/dependabot-auto-merge.yml",
            "conclusion": "success",
            "actor": {"login": "dependabot[bot]"},
            "head_repository": {"full_name": REPO},
            "head_branch": BRANCH,
            "head_sha": SHA,
            "pull_requests": [],
        },
    }


def job(eligible="success"):
    return {
        "total_count": 1,
        "jobs": [
            {
                "name": verifier.CLASSIFIER_JOB,
                "conclusion": "success",
                "steps": [
                    {"name": verifier.METADATA_STEP, "conclusion": "success"},
                    {"name": verifier.ELIGIBLE_STEP, "conclusion": eligible},
                ],
            }
        ],
    }


def pr():
    return {
        "number": 55,
        "user": {"login": "dependabot[bot]"},
        "head": {"repo": {"full_name": REPO}, "ref": BRANCH, "sha": SHA},
        "base": {"repo": {"full_name": REPO}, "ref": "main"},
        "state": "open",
        "draft": False,
        "labels": [],
    }


class FakeApi:
    def __init__(self, jobs=None, prs=None, files=None):
        self.jobs = copy.deepcopy(job() if jobs is None else jobs)
        self.prs = copy.deepcopy([pr()] if prs is None else prs)
        self.files = copy.deepcopy(
            [{"filename": "openbank-admin-ui/package-lock.json", "status": "modified"}]
            if files is None
            else files
        )

    def __call__(self, *args):
        if "attempts/1/jobs" in args[0]:
            return self.jobs
        if args[0] == "--method":
            return self.prs
        if "/files?" in args[0]:
            return self.files if args[0].endswith("page=1") else []
        raise AssertionError(f"unexpected API call: {args}")


class AdmissionTests(unittest.TestCase):
    def test_classifier_covers_npm_rebases(self):
        workflow = (
            Path(__file__).parents[1] / "workflows/dependabot-auto-merge.yml"
        ).read_text()
        # fetch-metadata v3.1.0 emits the Dependabot branch ecosystem name.
        self.assertIn("types: [opened, reopened, synchronize]", workflow)
        self.assertIn("package-ecosystem == 'npm_and_yarn'", workflow)
        self.assertIn("package-ecosystem != 'npm_and_yarn'", workflow)
        self.assertNotIn("package-ecosystem == 'npm'", workflow)

    def test_eligible_patch_with_empty_workflow_run_pr_list(self):
        self.assertEqual(verifier.verify(event(), REPO, FakeApi()), 55)

    def test_other_workflow_with_same_name_is_rejected(self):
        source = event()
        source["workflow_run"]["path"] = ".github/workflows/other.yml"
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(source, REPO, FakeApi())

    def test_skipped_minor_or_major_marker_rejected(self):
        for update_type in ("minor", "major"):
            with (
                self.subTest(update_type=update_type),
                self.assertRaises(verifier.AdmissionError),
            ):
                verifier.verify(event(), REPO, FakeApi(jobs=job("skipped")))

    def test_missing_or_duplicate_pr_rejected(self):
        for prs in ([], [pr(), pr()]):
            with (
                self.subTest(count=len(prs)),
                self.assertRaises(verifier.AdmissionError),
            ):
                verifier.verify(event(), REPO, FakeApi(prs=prs))

    def test_head_sha_mismatch_rejected(self):
        changed = pr()
        changed["head"]["sha"] = "b" * 40
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(event(), REPO, FakeApi(prs=[changed]))

    def test_draft_and_blocked_prs_rejected(self):
        for changed in (
            {**pr(), "draft": True},
            {**pr(), "labels": [{"name": "blocked"}]},
        ):
            with self.subTest(changed=changed), self.assertRaises(verifier.AdmissionError):
                verifier.verify(event(), REPO, FakeApi(prs=[changed]))

    def test_missing_or_malformed_readiness_fields_rejected(self):
        malformed = []
        for key in ("draft", "labels"):
            changed = pr()
            del changed[key]
            malformed.append(changed)
        for value in (None, 0, "false"):
            malformed.append({**pr(), "draft": value})
        for value in (None, "[]", [{"name": ""}], [{"name": 3}], ["blocked"], [{}]):
            malformed.append({**pr(), "labels": value})
        for changed in malformed:
            with self.subTest(changed=changed), self.assertRaises(verifier.AdmissionError):
                verifier.verify(event(), REPO, FakeApi(prs=[changed]))

    def test_workflow_file_change_rejected(self):
        files = [
            {"filename": "openbank-admin-ui/package-lock.json", "status": "modified"},
            {"filename": ".github/workflows/dependabot-auto-merge.yml", "status": "modified"},
        ]
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(event(), REPO, FakeApi(files=files))

    def test_dependency_file_set_is_scoped_to_the_classified_ecosystem(self):
        gradle_event = event()
        gradle_event["workflow_run"]["head_branch"] = "dependabot/gradle/quarkus-patch"
        gradle_pr = pr()
        gradle_pr["head"]["ref"] = "dependabot/gradle/quarkus-patch"
        gradle_files = [
            {"filename": "build-logic/build.gradle.kts", "status": "modified"},
            {"filename": "openbank-libs/gradle/libs.versions.toml", "status": "modified"},
            {"filename": "gradle/wrapper/gradle-wrapper.jar", "status": "modified"},
        ]
        self.assertEqual(
            verifier.verify(
                gradle_event, REPO, FakeApi(prs=[gradle_pr], files=gradle_files)
            ),
            55,
        )
        for files in (
            [{"filename": "openbank-admin-ui/src/lib/telemetry/glitchtip.ts", "status": "modified"}],
            [{"filename": "openbank-admin-ui/package-lock.json", "status": "added"}],
            [{"filename": "build-logic/build.gradle.kts", "status": "modified"}],
            [{"filename": "openbank-libs/gradle/libs.versions.toml", "status": "modified"}],
        ):
            with self.subTest(files=files), self.assertRaises(verifier.AdmissionError):
                verifier.verify(event(), REPO, FakeApi(files=files))
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(
                gradle_event,
                REPO,
                FakeApi(
                    prs=[gradle_pr],
                    files=[{"filename": "openbank-admin-ui/package.json", "status": "modified"}],
                ),
            )
        for path in (
            "openbank-libs/gradle/libs.versions.toml.bak",
            "openbank-libs/gradle/other.versions.toml",
        ):
            with self.subTest(path=path), self.assertRaises(verifier.AdmissionError):
                verifier.verify(
                    gradle_event,
                    REPO,
                    FakeApi(prs=[gradle_pr], files=[{"filename": path, "status": "modified"}]),
                )

    def test_incomplete_api_data_rejected(self):
        incomplete_jobs = job()
        incomplete_jobs["total_count"] = 2
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(event(), REPO, FakeApi(jobs=incomplete_jobs))
        incomplete_pr = pr()
        del incomplete_pr["head"]["repo"]
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(event(), REPO, FakeApi(prs=[incomplete_pr]))
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(event(), REPO, FakeApi(files=[{"status": "modified"}]))

    def test_non_dependabot_source_rejected_before_api(self):
        source = event()
        source["workflow_run"]["actor"]["login"] = "someone-else"
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(source, REPO, FakeApi())


class AutoMergeBoundaryWorkflowTests(unittest.TestCase):
    def test_actual_derived_heal_step_checks_live_hold_and_pins_head(self):
        good = {**pr(), "head": {**pr()["head"], "sha": SHA}}
        result, calls = run_bash_workflow(*ARMING_BLOCKS[0][:2], good)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)
        self.assertIn("--match-head-commit", calls[0])
        self.assertIn(SHA, calls[0])
        held = [
            {**good, "draft": True},
            {key: value for key, value in good.items() if key != "draft"},
            {**good, "labels": [{"name": "blocked"}]},
            {key: value for key, value in good.items() if key != "labels"},
            {**good, "labels": [{"name": " "}]},
            {**good, "state": "closed"},
            {**good, "head": {"sha": "not-a-sha"}},
        ]
        for pull in held:
            with self.subTest(pull=pull):
                result, calls = run_bash_workflow(*ARMING_BLOCKS[0][:2], pull)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, [])
        result, calls = run_bash_workflow(*ARMING_BLOCKS[0][:2], good, api_error=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [])

    def test_actual_dependabot_arm_step_rechecks_hold_and_classified_sha(self):
        good = pr()
        result, calls = run_bash_workflow(*ARMING_BLOCKS[1][:2], good)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)
        self.assertIn("--match-head-commit", calls[0])
        self.assertIn(SHA, calls[0])
        held = [
            {**good, "draft": True},
            {key: value for key, value in good.items() if key != "draft"},
            {**good, "labels": [{"name": "blocked"}]},
            {key: value for key, value in good.items() if key != "labels"},
            {**good, "labels": [{"name": " "}]},
        ]
        for pull in held:
            with self.subTest(pull=pull):
                result, calls = run_bash_workflow(*ARMING_BLOCKS[1][:2], pull)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, [])
        result, calls = run_bash_workflow(
            *ARMING_BLOCKS[1][:2], good, expected_sha="b" * 40
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [])
        result, calls = run_bash_workflow(*ARMING_BLOCKS[1][:2], good, api_error=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [])

    def test_actual_release_script_filters_and_rechecks_live_pr(self):
        good = release_pr()
        result, armed = run_release_workflow([good])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(armed, ["PR_node_55"])
        for held in (
            {**good, "draft": True},
            {**good, "labels": [{"name": "blocked"}]},
            {key: value for key, value in good.items() if key != "draft"},
            {key: value for key, value in good.items() if key != "labels"},
            {**good, "labels": [{"name": " "}]},
            {**good, "state": "closed"},
            {**good, "head": {"sha": "not-a-sha"}},
            {**good, "node_id": ""},
        ):
            with self.subTest(stage="initial", held=held):
                result, armed = run_release_workflow([held])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(armed, [])
            with self.subTest(stage="live", held=held):
                result, armed = run_release_workflow([good], [held])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(armed, [])
        moved = {**good, "head": {"sha": "b" * 40}}
        result, armed = run_release_workflow([good], [moved])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(armed, [])
        result, armed = run_release_workflow([good], api_error=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(armed, [])


if __name__ == "__main__":
    program = unittest.main(exit=False)
    if program.result.wasSuccessful():
        print(f"SUBJECTS={len(ARMING_BLOCKS)}")
    raise SystemExit(not program.result.wasSuccessful())
