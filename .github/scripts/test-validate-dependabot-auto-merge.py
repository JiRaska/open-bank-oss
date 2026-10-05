#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Focused tests for the privileged Dependabot auto-merge trust boundary."""

import importlib.util
from pathlib import Path
import unittest

SOURCE = Path(__file__).with_name("validate-dependabot-auto-merge.py")
SPEC = importlib.util.spec_from_file_location("dependabot_auto_merge_validator", SOURCE)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)
REPO = "example/open-bank-oss"
SHA = "a" * 40
REF = "dependabot/npm_and_yarn/openbank-admin-ui/next-patch"


def fixtures():
    request = {"number": 42, "sha": SHA, "ref": REF,
               "update_type": "version-update:semver-patch", "ecosystem": "npm_and_yarn"}
    event = {"workflow_run": {"event": "pull_request", "conclusion": "success",
                              "actor": {"login": "dependabot[bot]"},
                              "head_repository": {"full_name": REPO},
                              "head_branch": REF, "head_sha": SHA,
                              "path": ".github/workflows/dependabot-auto-merge.yml",
                              "pull_requests": [{"number": 42}]}}
    pr = {"number": 42, "state": "open", "user": {"login": "dependabot[bot]"},
          "base": {"ref": "main"},
          "head": {"sha": SHA, "ref": REF, "repo": {"full_name": REPO}},
          "html_url": f"https://github.com/{REPO}/pull/42"}
    return request, event, pr


class EligibilityTests(unittest.TestCase):
    def test_exact_patch_from_trusted_run_is_eligible(self):
        request, event, pr = fixtures()
        self.assertEqual(validator.validate(request, event, pr, REPO), pr["html_url"])

    def test_patch_claim_cannot_upgrade_a_major_or_other_ecosystem(self):
        for field, value in (("update_type", "version-update:semver-major"),
                             ("ecosystem", "github_actions"),
                             ("ecosystem", [])):
            with self.subTest(field=field, value=value):
                request, event, pr = fixtures()
                request[field] = value
                with self.assertRaises(ValueError):
                    validator.validate(request, event, pr, REPO)

    def test_stale_head_and_other_pr_are_rejected(self):
        request, event, pr = fixtures()
        pr["head"]["sha"] = "b" * 40
        with self.assertRaises(ValueError):
            validator.validate(request, event, pr, REPO)
        request, event, pr = fixtures()
        event["workflow_run"]["pull_requests"] = [{"number": 43}]
        with self.assertRaises(ValueError):
            validator.validate(request, event, pr, REPO)

    def test_run_or_pr_outside_trusted_repository_is_rejected(self):
        request, event, pr = fixtures()
        event["workflow_run"]["head_repository"]["full_name"] = "attacker/fork"
        with self.assertRaises(ValueError):
            validator.validate(request, event, pr, REPO)
        request, event, pr = fixtures()
        pr["user"]["login"] = "someone-else"
        with self.assertRaises(ValueError):
            validator.validate(request, event, pr, REPO)
        request, event, pr = fixtures()
        pr["head"]["repo"]["full_name"] = "attacker/fork"
        with self.assertRaises(ValueError):
            validator.validate(request, event, pr, REPO)

    def test_unexpected_artifact_fields_are_rejected(self):
        request, event, pr = fixtures()
        request["override_reviews"] = True
        with self.assertRaises(ValueError):
            validator.validate(request, event, pr, REPO)


if __name__ == "__main__":
    unittest.main()
