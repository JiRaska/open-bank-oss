#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise provenance and publication failures without contacting a broker."""
import copy
import base64
import fnmatch
import re
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch, MagicMock
import urllib.error

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-admin-ui-pacts.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "pacts").mkdir()
        for provider in ["openbank-billing-service", "openbank-product-catalog",
                         "openbank-flaky-test-hunter", "openbank-case-coordinator-agent",
                         "openbank-context-service"]:
            p = self.root / "pacts" / f"openbank-admin-ui-{provider}.json"
            p.write_text(json.dumps({"consumer": {"name": "openbank-admin-ui"},
                                     "provider": {"name": provider},
                                     "interactions": [{"description": provider}]}))
        def git(*args):
            return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.DEVNULL)
        git("init")
        git("add", *[str(path.relative_to(self.root)) for path in (self.root / "pacts").glob("*.json")])
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
            "-c", "commit.gpgsign=false", "commit", "-m", "fixture")
        self.sha = git("rev-parse", "HEAD").decode().strip()
        self.marker = self.root / "marker"
        self.marker.touch()
        os.utime(self.marker, ns=(1, 1))
        self.bundle = publisher.prepare(self.root, self.sha, self.marker)
        self.env = {"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/main",
                    "PACT_BROKER_URL": "https://broker.example", "PACT_BROKER_USERNAME": "fixture",
                    "PACT_BROKER_PASSWORD": "test-only"}

    def body(self, bundle=None):
        return publisher.payload(self.root, self.sha, self.bundle if bundle is None else bundle,
                                 "https://example.test/build")

    def test_real_consumer_and_generator_inputs_trigger_both_lanes(self):
        workflow = Path(__file__).parents[1] / "workflows" / "pact-drift-check.yml"
        text = workflow.read_text()
        for event in ["pull_request", "push"]:
            block = re.search(rf"(?ms)^  {event}:\n(.*?)(?=^  [a-z_]+:|^permissions:)", text)
            self.assertIsNotNone(block)
            patterns = re.findall(r'^      - "([^"\n]+)"$', block.group(1), re.M)
            for path in ["openbank-admin-ui/src/lib/services/bff.ts",
                         "openbank-admin-ui/vitest.config.ts",
                         "openbank-admin-ui/src/app/api/approvals/pending/route.ts",
                         "openbank-case-coordinator-agent/build.gradle.kts",
                         "openbank-flaky-test-hunter/src/test/kotlin/contract/FlakyTestTriggerPactConsumerTest.kt",
                         "openbank-product-catalog/src/main/kotlin/domain/Product.kt",
                         "openbank-libs-testing/src/main/kotlin/TestSupport.kt",
                         "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts",
                         "gradle/libs.versions.toml", "gradle.properties", "settings.gradle.kts",
                         ".github/scripts/await-admin-ui-pact-publication.py",
                         ".github/scripts/test-await-admin-ui-pact-publication.py",
                         ".github/workflows/verify-provider.yml"]:
                with self.subTest(event=event, path=path):
                    self.assertTrue(any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns))

    def test_complete_set_and_revision(self):
        body = self.body()
        self.assertEqual(len(body["contracts"]), 5)
        self.assertEqual(body["pacticipantVersionNumber"], self.sha)
        self.assertEqual(body["branch"], "main")
        self.assertEqual({x["providerName"] for x in body["contracts"]},
                         {"openbank-billing-service", "openbank-product-catalog",
                          "openbank-flaky-test-hunter", "openbank-case-coordinator-agent",
                          "openbank-context-service"})

    def test_revision_mismatch(self):
        with self.assertRaises(ValueError):
            publisher.prepare(self.root, "0" * 40, self.marker)
        bad = copy.deepcopy(self.bundle); bad["sourceSha"] = "0" * 40
        with self.assertRaises(ValueError): self.body(bad)

    def test_missing_and_extra_contracts(self):
        for extra in [False, True]:
            bad = copy.deepcopy(self.bundle)
            if extra: bad["files"]["pacts/extra.json"] = {}
            else: bad["files"].pop(next(iter(bad["files"])))
            with self.assertRaises(ValueError): self.body(bad)

    def test_corrupt_content_and_digest(self):
        for field in ["sha256", "content"]:
            bad = copy.deepcopy(self.bundle)
            bad["files"][next(iter(bad["files"]))][field] = "corrupt"
            with self.assertRaises(ValueError): self.body(bad)

    def test_dirty_and_symlink_contracts(self):
        p = self.root / next(iter(self.bundle["files"]))
        p.write_text(p.read_text() + " ")
        with self.assertRaises(ValueError): self.body()
        p.unlink(); p.symlink_to(self.marker)
        with self.assertRaises(ValueError): self.body()

    def test_not_regenerated(self):
        self.marker.touch()
        with self.assertRaises(ValueError): publisher.prepare(self.root, self.sha, self.marker)

    def test_untrusted_events_never_contact_broker(self):
        for event, ref in [("pull_request", "refs/heads/main"), ("push", "refs/heads/topic"),
                           ("workflow_run", "refs/heads/main")]:
            with self.subTest(event=event), patch.object(publisher.urllib.request, "build_opener") as op:
                with self.assertRaises(publisher.PublicationFailure) as failure:
                    publisher.publish(self.body(), dict(self.env, GITHUB_EVENT_NAME=event, GITHUB_REF=ref))
                self.assertEqual(failure.exception.stage, "trusted-main")
                op.assert_not_called()

    def test_missing_config_never_contacts_broker(self):
        for key in ["PACT_BROKER_URL", "PACT_BROKER_USERNAME", "PACT_BROKER_PASSWORD"]:
            with patch.object(publisher.urllib.request, "build_opener") as op:
                with self.assertRaises(ValueError): publisher.publish(self.body(), dict(self.env, **{key: ""}))
                op.assert_not_called()
        with self.assertRaises(ValueError):
            publisher.publish(self.body(), dict(self.env, PACT_BROKER_URL="http://broker.example"))

    def test_acceptance_and_rejection(self):
        for status in [302, 401, 500]:
            opener = MagicMock()
            opener.open.return_value.__enter__.return_value.status = status
            with patch.object(publisher.urllib.request, "build_opener", return_value=opener):
                with self.assertRaises(ValueError): publisher.publish(self.body(), self.env)
            request = opener.open.call_args.args[0]
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.full_url, "https://broker.example/contracts/publish")
            self.assertEqual(json.loads(request.data)["pacticipantVersionNumber"], self.sha)

    def test_readback_precedes_provider_dispatch_and_matches_exact_version(self):
        body = self.body()
        requests = []
        class Response:
            status = 200
            def __init__(self, data): self.data = data
            def __enter__(self): return self
            def __exit__(self, *_): return False
            def read(self, *_): return self.data
        def open_request(request, timeout):
            requests.append(request)
            if request.method == "POST": return Response(b"{}")
            contract = next(x for x in body["contracts"] if
                            f"/{x['providerName']}/" in request.full_url)
            expected = json.loads(base64.b64decode(contract["content"]))
            expected["_links"] = {"self": {"href": "broker-added"}}
            return Response(json.dumps(expected).encode())
        opener = MagicMock()
        opener.open.side_effect = open_request
        with patch.object(publisher.urllib.request, "build_opener", return_value=opener):
            providers = publisher.publish(body, self.env)
        self.assertEqual(providers, sorted(x["providerName"] for x in body["contracts"]))
        self.assertEqual([r.method for r in requests], ["POST"] + ["GET"] * 5)
        self.assertTrue(all(f"/version/{self.sha}" in r.full_url for r in requests[1:]))

    def test_mismatched_readback_fails_closed(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.status = 201
        opener.open.return_value.__enter__.return_value.read.return_value = b'{"interactions":[]}'
        with (patch.object(publisher.urllib.request, "build_opener", return_value=opener),
              self.assertRaises(publisher.PublicationFailure) as failure):
            publisher.publish(self.body(), self.env)
        self.assertEqual(failure.exception.stage, "readback-mismatch")
        self.assertEqual(failure.exception.category, "consumer,provider,interactions")
        self.assertRegex(failure.exception.provider, publisher.PROVIDER)

    def test_readback_mismatch_category_never_contains_broker_keys_or_values(self):
        expected = {"consumer": {"name": "openbank-admin-ui"}, "interactions": [1]}
        received = {"consumer": {"name": "openbank-admin-ui"}, "interactions": [2],
                    "secret-token": "private-value"}
        self.assertEqual(publisher.mismatch_category(expected, received), "interactions")
        self.assertEqual(publisher.mismatch_category(expected, {"secret-token": "private-value"}),
                         "consumer,interactions")
        self.assertEqual(publisher.mismatch_category({}, {"secret-token": "private-value"}), "other")

    def test_malformed_broker_body_reports_only_safe_stage_and_provider(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.status = 201
        opener.open.return_value.__enter__.return_value.read.return_value = b'not-json secret-token'
        with (patch.object(publisher.urllib.request, "build_opener", return_value=opener),
              self.assertRaises(publisher.PublicationFailure) as failure):
            publisher.publish(self.body(), self.env)
        self.assertEqual(failure.exception.stage, "readback-json")
        self.assertRegex(failure.exception.provider, publisher.PROVIDER)
        self.assertNotIn("secret-token", str(failure.exception))

    def test_provider_name_must_be_a_service_not_a_matrix_expression(self):
        path = self.root / next(iter(self.bundle["files"]))
        pact = json.loads(path.read_text())
        pact["provider"]["name"] = "${{ github.token }}"
        path.write_text(json.dumps(pact))
        with self.assertRaises(ValueError): publisher.source(self.root, self.sha)

    def test_network_failure_and_redirect_not_success(self):
        with patch.object(publisher.urllib.request, "build_opener") as op:
            op.return_value.open.side_effect = urllib.error.URLError("secret broker URL")
            with self.assertRaises(publisher.PublicationFailure) as failure:
                publisher.publish(self.body(), self.env)
        self.assertEqual(failure.exception.stage, "broker-write-network")
        self.assertNotIn("secret broker URL", str(failure.exception))
        self.assertIsNone(publisher.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://other.example"))

    def test_http_failure_identifies_write_or_provider_readback(self):
        body = self.body()
        first_provider = body["contracts"][0]["providerName"]
        for fail_on_readback in (False, True):
            opener = MagicMock()
            response = MagicMock()
            response.__enter__.return_value.status = 200
            http_error = urllib.error.HTTPError("https://secret.example/path", 503,
                                               "private response", {}, None)
            opener.open.side_effect = [response, http_error] if fail_on_readback else http_error
            with (patch.object(publisher.urllib.request, "build_opener", return_value=opener),
                  self.assertRaises(publisher.PublicationFailure) as failure):
                publisher.publish(body, self.env)
            self.assertEqual(failure.exception.stage,
                             "broker-readback" if fail_on_readback else "broker-write")
            self.assertEqual(failure.exception.provider, first_provider if fail_on_readback else None)
            self.assertEqual(failure.exception.status, 503)
            self.assertNotIn("secret.example", str(failure.exception))
            http_error.close()


if __name__ == "__main__":
    unittest.main()
