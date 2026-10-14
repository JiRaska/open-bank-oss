#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reject unsafe historical broker workflow wiring; does not prove task behavior."""

import copy
import re
import unittest
from pathlib import Path

import yaml


def validate(document):
    def require(value):
        if not value:
            raise ValueError("Unsafe historical broker workflow")

    trigger = document.get("on", document.get(True))
    require(trigger == {"workflow_dispatch": None})
    require(document["permissions"] == {"contents": "read"})
    require(document["concurrency"]["cancel-in-progress"] is False)
    require(set(document["jobs"]) == {"prove"})
    job = document["jobs"]["prove"]
    condition = " ".join(job["if"].split())
    require(condition == (
        "github.repository == 'JiRaska/open-bank-oss' && "
        "github.ref == 'refs/heads/main' && "
        "github.event_name == 'workflow_dispatch' && github.run_attempt == 1"
    ))
    require(job["runs-on"] == "openbank-build")
    require("secrets." not in str(job.get("env", {})))
    steps = job["steps"]
    for step in steps:
        require(not step.get("continue-on-error", False))
        if "uses" in step:
            require(re.fullmatch(r"[^@]+@[0-9a-f]{40}", step["uses"]) is not None)
    checkout = steps[0]
    require(checkout["with"]["ref"] == "${{ github.sha }}")
    require(checkout["with"]["fetch-depth"] == 0)
    require(checkout["with"]["persist-credentials"] is False)
    indexed = {step.get("name"): index for index, step in enumerate(steps)}
    proof = indexed["Prove fresh trusted main checkout before any build"]
    build = indexed["Build all ledger production inputs without cached task outputs"]
    verify = indexed["Verify the independently pinned historical broker contract"]
    publish = indexed["Publish once and verify exact broker receipt"]
    recheck = indexed["Revalidate checkout identity after build and verification"]
    upload = indexed["Upload verified public evidence"]
    require(proof < build < verify < recheck < publish < upload)
    require("--post-build" in steps[recheck]["run"])
    require("git fetch --no-tags 'https://github.com/JiRaska/open-bank-oss.git'" in steps[proof]["run"])
    require("prove-finrep-historical-checkout.py" in steps[proof]["run"])
    require(":openbank-ledger-service:clean :openbank-ledger-service:build" in steps[build]["run"])
    for index in [build, verify, publish]:
        command = steps[index]["run"]
        require("set -euo pipefail" in command)
        require("--no-build-cache" in command and "--no-configuration-cache" in command)
    require("--rerun-tasks" in steps[build]["run"])
    for index, step in enumerate(steps):
        has_secret = "secrets." in str(step.get("env", {}))
        require(has_secret == (index in [verify, publish]))
    for index in [verify, publish]:
        step = steps[index]
        require("if" not in step)
        expected_env = {
            "PACT_BROKER_URL": "${{ vars.PACT_BROKER_URL }}",
            "PACT_BROKER_USERNAME": "${{ vars.PACT_BROKER_USERNAME }}",
            "PACT_BROKER_PASSWORD": "${{ secrets.PACT_BROKER_PASSWORD }}",
        }
        if index == publish:
            expected_env["FINREP_PUBLICATION_ENABLED"] = "true"
        require(step["env"] == expected_env)
        require("-Dpact.verifier.publishResults=false" in step["run"])
        require("-Dpactbroker.auth" not in step["run"])
    require("--rerun-tasks" not in steps[publish]["run"])
    require("hostedHistoricalFinrepPactTest" not in steps[publish]["run"])
    require(steps[upload]["with"]["path"] == "openbank-ledger-service/build/finrep-historical-public-evidence.json")
    require(steps[upload]["with"]["if-no-files-found"] == "error")


class WorkflowBoundaryTest(unittest.TestCase):
    def setUp(self):
        source = Path(__file__).parents[1] / "workflows/finrep-historical-broker.yml"
        self.document = yaml.safe_load(source.read_text())

    def test_source_contract(self):
        validate(self.document)

    def test_security_boundary_mutations_reject(self):
        changes = [
            lambda d: d["jobs"]["prove"].update({"if": "true"}),
            lambda d: d["jobs"]["prove"].update({"if": " ".join(d["jobs"]["prove"]["if"].split()).replace(" && github.run_attempt == 1", "")}),
            lambda d: d["permissions"].update({"contents": "write"}),
            lambda d: d["concurrency"].update({"cancel-in-progress": True}),
            lambda d: d["jobs"]["prove"]["steps"][0]["with"].update({"ref": "${{ inputs.ref }}"}),
            lambda d: d["jobs"]["prove"]["steps"][0]["with"].update({"persist-credentials": True}),
            lambda d: d["jobs"]["prove"]["steps"][0].update({"uses": "actions/checkout@main"}),
            lambda d: d["jobs"]["prove"]["env"].update({"PASSWORD": "${{ secrets.PACT_BROKER_PASSWORD }}"}),
            lambda d: d["jobs"]["prove"]["steps"][-2].update({"if": "always()"}),
            lambda d: d["jobs"]["prove"]["steps"][-2].update({"continue-on-error": True}),
            lambda d: d["jobs"]["prove"]["steps"][-1]["with"].update({"path": "openbank-ledger-service/build/**"}),
        ]
        for index, change in enumerate(changes):
            with self.subTest(mutation=index):
                candidate = copy.deepcopy(self.document)
                change(candidate)
                with self.assertRaises(ValueError):
                    validate(candidate)


if __name__ == "__main__":
    unittest.main()
