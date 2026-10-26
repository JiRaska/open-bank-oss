#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression tests for split artifact-source and workflow-source attribution."""
import copy
import importlib.util
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "openbank-infra/scripts/lib/build-slsa-provenance.py"
SPEC = importlib.util.spec_from_file_location("slsa_source_identity", SOURCE)
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class SourceAttributionTests(unittest.TestCase):
    def setUp(self):
        self.env = {
            "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_REPOSITORY": "example/repository",
            "GITHUB_SHA": "c" * 40,
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_WORKFLOW_REF": "example/repository/.github/workflows/auto-deploy.yml@refs/heads/main",
            "GITHUB_RUN_ID": "123",
            "GITHUB_RUN_ATTEMPT": "2",
            "GITHUB_EVENT_NAME": "workflow_run",
        }

    def test_existing_self_test_and_default_digest_behavior_are_preserved(self):
        self.assertEqual(BUILDER.self_test(), 0)
        predicate = BUILDER.build_predicate(self.env)
        self.assertEqual(predicate["invocation"]["configSource"]["digest"], {"sha1": "c" * 40})
        self.assertEqual(predicate["materials"][0]["digest"], {"sha1": "c" * 40})

    def test_workflow_a_artifact_b_event_c_remain_independently_attributed_without_mutation(self):
        self.env.update(GITHUB_WORKFLOW_SHA="a" * 40, ARTIFACT_SOURCE_SHA="b" * 40)
        before = copy.deepcopy(self.env)
        predicate = BUILDER.build_predicate(self.env)
        self.assertEqual(predicate["invocation"]["configSource"]["digest"], {"sha1": "a" * 40})
        self.assertEqual(predicate["materials"][0]["digest"], {"sha1": "b" * 40})
        self.assertEqual(predicate["invocation"]["configSource"]["entryPoint"], ".github/workflows/auto-deploy.yml")
        self.assertEqual(predicate["invocation"]["parameters"], {"ref": "refs/heads/main", "event_name": "workflow_run"})
        self.assertEqual(predicate["metadata"]["buildInvocationId"], "https://github.com/example/repository/actions/runs/123/attempts/2")
        self.assertEqual(self.env, before)
        self.assertEqual(self.env["GITHUB_SHA"], "c" * 40)

    def test_only_materials_change_when_only_artifact_source_is_supplied(self):
        baseline = BUILDER.build_predicate(self.env)
        self.env["ARTIFACT_SOURCE_SHA"] = "b" * 40
        actual = BUILDER.build_predicate(self.env)
        expected = copy.deepcopy(baseline)
        expected["materials"][0]["digest"]["sha1"] = "b" * 40
        self.assertEqual(actual, expected)

    def test_workflow_sha_does_not_change_default_source_material(self):
        self.env["GITHUB_WORKFLOW_SHA"] = "a" * 40
        predicate = BUILDER.build_predicate(self.env)
        self.assertEqual(predicate["invocation"]["configSource"]["digest"], {"sha1": "a" * 40})
        self.assertEqual(predicate["materials"][0]["digest"], {"sha1": "c" * 40})

    def test_missing_or_empty_workflow_sha_falls_back_without_changing_environment(self):
        for workflow_sha in (None, ""):
            with self.subTest(workflow_sha=workflow_sha):
                if workflow_sha is not None:
                    self.env["GITHUB_WORKFLOW_SHA"] = workflow_sha
                before = copy.deepcopy(self.env)
                predicate = BUILDER.build_predicate(self.env)
                self.assertEqual(predicate["invocation"]["configSource"]["digest"], {"sha1": "c" * 40})
                self.assertEqual(self.env, before)

    def test_explicit_artifact_sha_rejects_empty_short_long_uppercase_and_nonhex_values(self):
        for value in ("", "b" * 39, "b" * 41, "B" * 40, "g" * 40, " " + "b" * 40, "b" * 40 + "\n"):
            with self.subTest(value=value):
                self.env["ARTIFACT_SOURCE_SHA"] = value
                before = copy.deepcopy(self.env)
                with self.assertRaisesRegex(SystemExit, "exactly 40 lowercase hexadecimal"):
                    BUILDER.build_predicate(self.env)
                self.assertEqual(self.env, before)

    def test_override_does_not_relax_required_invocation_fields(self):
        self.env["ARTIFACT_SOURCE_SHA"] = "b" * 40
        del self.env["GITHUB_SHA"]
        with self.assertRaisesRegex(SystemExit, "missing required env: GITHUB_SHA"):
            BUILDER.build_predicate(self.env)

    def test_completeness_and_reproducibility_remain_honest(self):
        self.env.update(GITHUB_WORKFLOW_SHA="a" * 40, ARTIFACT_SOURCE_SHA="b" * 40)
        predicate = BUILDER.build_predicate(self.env)
        self.assertEqual(predicate["metadata"]["completeness"], {"parameters": False, "environment": False, "materials": False})
        self.assertIs(predicate["metadata"]["reproducible"], False)
        self.assertEqual(set(predicate), {"builder", "buildType", "invocation", "materials", "metadata"})


if __name__ == "__main__":
    unittest.main()
