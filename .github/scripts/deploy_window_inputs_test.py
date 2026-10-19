#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline regression coverage for the deploy-priority inventory boundary."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("deploy-window-inputs.sh")


def pull_request(number, head, labels=(), draft=False):
    return {
        "number": number, "state": "open", "head": {
            "ref": head, "repo": {"full_name": "example/open-bank"}},
        "base": {"repo": {"full_name": "example/open-bank"}},
        "created_at": "2026-01-01T00:00:00Z", "draft": draft,
        "labels": [{"name": label} for label in labels], "auto_merge": None,
    }


class DeployWindowInputsTest(unittest.TestCase):
    def run_inventory(self, pages, fail_api=False, fail_after_page=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_gh = root / "gh"
            fake_gh.write_text(
                f"#!{sys.executable}\n"
                "import os, sys\n"
                "if '--paginate' in sys.argv:\n"
                "    assert '--slurp' in sys.argv\n"
                "    assert sys.argv[-1].endswith('/pulls?state=open&per_page=100')\n"
                "    if os.environ['FAIL_API'] == 'yes': sys.exit(1)\n"
                "    print(os.environ['PAGES'])\n"
                "    if os.environ['FAIL_AFTER_PAGE'] == 'yes': sys.exit(1)\n"
                "else:\n"
                "    assert sys.argv[1] == 'api'\n"
                "    print('[]')\n",
                encoding="utf-8",
            )
            fake_gh.chmod(0o755)
            # Deliberately do not inherit credentials or other caller environment values.
            environment = {
                "PATH": str(root) + os.pathsep + os.environ.get("PATH", os.defpath),
                "GITHUB_REPOSITORY": "example/open-bank",
                "PAGES": json.dumps(pages), "FAIL_API": "yes" if fail_api else "no",
                "FAIL_AFTER_PAGE": "yes" if fail_after_page else "no",
            }
            output = root / "output"
            result = subprocess.run(
                ["bash", str(SCRIPT), str(output)], env=environment,
                capture_output=True, text=True, check=False,
            )
            inventory = json.loads((output / "prs.json").read_text()) if result.returncode == 0 else None
            return result.returncode, inventory

    def test_paginated_marked_draft_blocked_repair_is_retained(self):
        deploy = pull_request(1, "chore/gitops-auto-deploy-a")
        repair = pull_request(2, "fix/repair", ["merge-priority:repair", "blocked"], True)
        feature = pull_request(3, "feat/feature", draft=True)
        held_deploy = pull_request(4, "chore/admin-ui-deploy-a", ["blocked"])
        code, inventory = self.run_inventory([[deploy], [repair, feature, held_deploy]])
        self.assertEqual(code, 0)
        self.assertEqual([pr["number"] for pr in inventory], [1, 2])
        self.assertTrue(inventory[1]["repair_priority"])
        self.assertFalse(inventory[0]["repair_priority"])

    def test_api_error_fails_closed(self):
        code, inventory = self.run_inventory([[]], fail_api=True)
        self.assertNotEqual(code, 0)
        self.assertIsNone(inventory)

    def test_partial_valid_page_then_api_failure_is_not_accepted(self):
        # A successful first page must not hide a failed later pagination request.
        deploy = pull_request(1, "chore/gitops-auto-deploy-a")
        code, inventory = self.run_inventory([[deploy]], fail_after_page=True)
        self.assertNotEqual(code, 0)
        self.assertIsNone(inventory)

    def test_malformed_inventory_fails_closed(self):
        code, inventory = self.run_inventory([[{"number": 2}]])
        self.assertNotEqual(code, 0)
        self.assertIsNone(inventory)

    def test_valid_empty_inventory_is_accepted(self):
        self.assertEqual(self.run_inventory([[]]), (0, []))


if __name__ == "__main__":
    unittest.main()
