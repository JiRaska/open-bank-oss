#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Falsification suite for .github/scripts/check-argocd-sync-integrity.py (issue #6371).

A gate that has only ever passed is unfalsified. Every case below drives the SCRIPT — as a
subprocess, through the same CLI the in-cluster CronJob uses — with an input it MUST classify a
particular way, in BOTH directions. The negative cases carry as much weight as the positive
ones: this detector's whole value is that it distinguishes an app that CANNOT sync from the 93
that are simply not syncing because nothing changed, and a detector that cannot tell those
apart is one nobody reads by week two.

Run: python3 -m unittest openbank-infra/scripts/check_argocd_sync_integrity_test.py
"""

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO / ".github" / "scripts" / "check-argocd-sync-integrity.py"

# The message ArgoCD actually served for three weeks, verbatim from #6371.
LOKI_MSG = (
    "Failed to compare desired state to live state: failed to calculate diff: error calculating "
    "server side diff: serverSideDiff error: error running server side apply in dryrun mode for "
    'resource StatefulSet/loki: StatefulSet.apps "loki" is invalid: spec: Forbidden: updates to '
    "statefulset spec for fields other than 'replicas' are forbidden"
)

OWN = "git@github.com:JiRaska/open-bank-oss.git"
UPSTREAM = "https://grafana.github.io/helm-charts"


def app(name, *, sync="Synced", health="Healthy", conditions=None, phase=None,
        revision=None, op_revision=None, repo=OWN):
    status = {"sync": {"status": sync}, "health": {"status": health}}
    if revision is not None:
        status["sync"]["revision"] = revision
    if conditions is not None:
        status["conditions"] = conditions
    if phase is not None:
        status["operationState"] = {
            "phase": phase,
            "finishedAt": "2026-08-07T14:47:51Z",
            "operation": {"sync": {"revision": op_revision if op_revision is not None else revision}},
        }
    return {"metadata": {"name": name}, "spec": {"source": {"repoURL": repo}}, "status": status}


def run(apps, *, repo=None):
    """Run the real script over a synthetic capture. Returns (exit_code, output)."""
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
        json.dump({"apiVersion": "v1", "kind": "List", "items": apps}, fh)
        path = fh.name
    argv = [sys.executable, str(SCRIPT), "--capture", path]
    if repo:
        argv += ["--repo", repo, "--base-ref", "origin/main"]
    proc = subprocess.run(argv, capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr


class TheMechanismItself(unittest.TestCase):
    """`ComparisonError` under Synced/Healthy — the state nothing in the estate could see."""

    def test_the_real_2026_08_21_loki_state_is_rejected(self):
        rc, out = run([app("loki", conditions=[{"type": "ComparisonError", "message": LOKI_MSG}])])
        self.assertEqual(rc, 1, out)
        self.assertIn("ERROR_CONDITION", out)
        self.assertIn("loki", out)

    def test_the_same_app_without_the_condition_passes(self):
        """The negative control. If this fails, the detector is keying on Synced/Healthy —
        which every healthy app in the estate also reports — and is worthless."""
        rc, out = run([app("loki", conditions=[])])
        self.assertEqual(rc, 0, out)

    def test_every_error_suffixed_condition_is_the_same_class(self):
        for ctype in ("SyncError", "InvalidSpecError", "UnknownError", "SomeFutureError"):
            with self.subTest(ctype=ctype):
                rc, out = run([app("x", conditions=[{"type": ctype, "message": "m"}])])
                self.assertEqual(rc, 1, out)

    def test_a_non_error_condition_does_not_fire(self):
        """`agent` carries SharedResourceWarning on the live cluster today. A detector that
        reddens on it is red from the day it lands."""
        rc, out = run([app("agent", sync="OutOfSync",
                           conditions=[{"type": "SharedResourceWarning", "message": "m"}])])
        self.assertEqual(rc, 0, out)


class FailedOperationMaskedBySynced(unittest.TestCase):
    def test_synced_at_the_revision_whose_only_sync_failed(self):
        rc, out = run([app("y", phase="Failed", revision="c" * 40)])
        self.assertEqual(rc, 1, out)
        self.assertIn("SYNCED_MASKING_FAILED_OP", out)

    def test_a_failure_at_an_older_revision_since_converged_does_not_fire(self):
        """The real `keycloak` state, 2026-08-23: a Failed operation from 2026-08-07 (its theme
        ConfigMap exceeded the 256KB last-applied-configuration annotation limit) while the app
        reports Synced at today's revision. An unmanaged probe — `kubectl get cm
        keycloak-theme-openbank` against the manifest in git — found all three keys
        byte-identical, so the app is genuinely converged and this must stay quiet. This test is
        what stops the rule being the estate's resting state."""
        rc, out = run([app("keycloak", phase="Failed", revision="a" * 40, op_revision="b" * 40)])
        self.assertEqual(rc, 0, out)

    def test_a_succeeded_operation_does_not_fire(self):
        rc, out = run([app("y", phase="Succeeded", revision="c" * 40)])
        self.assertEqual(rc, 0, out)

    def test_outofsync_with_a_failed_operation_is_honest_reporting(self):
        rc, out = run([app("y", sync="OutOfSync", phase="Failed", revision="c" * 40)])
        self.assertEqual(rc, 0, out)


class RevisionAncestry(unittest.TestCase):
    """A deploy is verified by commit ANCESTRY, never by a tag or a version string (#6234)."""

    def setUp(self):
        self.head = subprocess.run(
            ["git", "-C", str(REPO), "rev-parse", "origin/main"],
            capture_output=True, text=True,
        ).stdout.strip()
        if len(self.head) != 40:
            self.skipTest("origin/main is not resolvable in this checkout")

    def test_a_revision_on_main_passes(self):
        rc, out = run([app("z", revision=self.head)], repo=str(REPO))
        self.assertEqual(rc, 0, out)

    def test_a_revision_no_commit_in_this_repo_is_rejected(self):
        rc, out = run([app("z", revision="b" * 40)], repo=str(REPO))
        self.assertEqual(rc, 1, out)
        self.assertIn("UNVERIFIABLE_REVISION", out)

    def test_a_chart_version_string_is_rejected_for_a_repo_sourced_app(self):
        """An app sourced from THIS repo must be at a commit. `6.55.0` is not one."""
        rc, out = run([app("z", revision="6.55.0")], repo=str(REPO))
        self.assertEqual(rc, 1, out)

    def test_an_upstream_chart_app_is_exempt(self):
        """22 of the 93 live Applications track a pinned upstream chart and carry a chart
        version, not a sha. Ancestry is meaningless for them and must not fire."""
        rc, out = run([app("loki", revision="6.55.0", repo=UPSTREAM)], repo=str(REPO))
        self.assertEqual(rc, 0, out)

    def test_ancestry_is_not_checked_when_no_repo_is_given(self):
        rc, out = run([app("z", revision="6.55.0")])
        self.assertEqual(rc, 0, out)


class AFailedCaptureIsNeverNoFindings(unittest.TestCase):
    """The defining failure of this repo's controls: a probe that observed nothing reporting
    that it found nothing. Every way the capture can fail must exit non-zero."""

    def test_absent_capture(self):
        proc = subprocess.run(
            [sys.executable, str(SCRIPT), "--capture", "/nonexistent/apps.json"],
            capture_output=True, text=True)
        self.assertEqual(proc.returncode, 1, proc.stdout)

    def test_empty_capture(self):
        rc, out = run([])
        self.assertEqual(rc, 1, out)

    def test_unparseable_capture(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            fh.write("this is not json")
            path = fh.name
        proc = subprocess.run([sys.executable, str(SCRIPT), "--capture", path],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 1, proc.stdout)


class WiringCannotBeSilentlyRemoved(unittest.TestCase):
    """The live half runs in-cluster. If its manifest is deleted, unregistered, or pointed at
    an inline copy of the comparison, it stops producing findings — which is indistinguishable
    from producing none. This is the repo half that notices."""

    def test_the_real_repo_is_wired(self):
        proc = subprocess.run([sys.executable, str(SCRIPT), "--check-wiring"],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_the_cronjob_manifest_invokes_the_committed_checker(self):
        manifest = (REPO / "openbank-infra/gitops/components/argocd-sync-verifier"
                    / "argocd-sync-verifier.yaml").read_text()
        self.assertIn(".github/scripts/check-argocd-sync-integrity.py", manifest)
        self.assertIn("exit 1", manifest)

    def test_the_selftest_passes(self):
        proc = subprocess.run([sys.executable, str(SCRIPT), "--self-test"],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)


if __name__ == "__main__":
    unittest.main()
