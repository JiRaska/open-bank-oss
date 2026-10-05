"""Offline trust-boundary tests for verify-dependabot-auto-merge.py."""

import copy
import importlib.util
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


def event():
    return {
        "repository": {"full_name": REPO},
        "workflow_run": {
            "id": 123,
            "run_attempt": 1,
            "event": "pull_request",
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
    }


class FakeApi:
    def __init__(self, jobs=None, prs=None, files=None):
        self.jobs = copy.deepcopy(job() if jobs is None else jobs)
        self.prs = copy.deepcopy([pr()] if prs is None else prs)
        self.files = copy.deepcopy(
            [{"filename": "openbank-admin-ui/package-lock.json"}]
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

    def test_workflow_file_change_rejected(self):
        files = [
            {"filename": "openbank-admin-ui/package-lock.json"},
            {"filename": ".github/workflows/dependabot-auto-merge.yml"},
        ]
        with self.assertRaises(verifier.AdmissionError):
            verifier.verify(event(), REPO, FakeApi(files=files))

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


if __name__ == "__main__":
    unittest.main()
