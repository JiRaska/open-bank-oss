#!/usr/bin/env python3
"""Focused checks for the exact-version Pact replay guard and wiring."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/historical-admin-ui-pact.py"
spec = importlib.util.spec_from_file_location("historical_admin_ui_pact", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class HistoricalPactTest(unittest.TestCase):
    def setUp(self):
        self.env = {
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REF": "refs/heads/main",
            "SERVICE": module.PROVIDER,
            "REQUESTED_REF": "",
            "REQUESTED_PROVIDER_VERSION": "",
            "HISTORICAL_ADMIN_UI_VERSION": module.VERSION,
            "PACT_BROKER_URL": "http://broker.example.test/",
        }

    def test_exact_url(self):
        self.assertEqual(
            module.validate(self.env),
            f"http://broker.example.test/pacts/provider/{module.PROVIDER}/consumer/{module.CONSUMER}/version/{module.VERSION}",
        )

    def test_rejects_other_targets_and_contexts(self):
        cases = {
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_REF": "refs/heads/feature",
            "SERVICE": "openbank-ledger-service",
            "REQUESTED_REF": "main",
            "REQUESTED_PROVIDER_VERSION": "a" * 40,
            "HISTORICAL_ADMIN_UI_VERSION": "a" * 40,
            "PACT_BROKER_URL": "file:///tmp/broker",
        }
        for key, value in cases.items():
            with self.subTest(key=key):
                env = dict(self.env, **{key: value})
                with self.assertRaises(ValueError):
                    module.validate(env)

    def test_property_reaches_forked_test_jvm_only_on_targeted_path(self):
        gradle = (ROOT / "build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts").read_text()
        workflow = (ROOT / ".github/workflows/_service-ci.yml").read_text()
        self.assertIn('"pact.filter.pacturl",', gradle)
        self.assertIn('"pact.filter.consumers",', gradle)
        self.assertIn('pact_url_arg=(-Dpact.filter.pacturl="${HISTORICAL_PACT_URL}" -Dpact.filter.consumers=openbank-admin-ui)', workflow)
        self.assertIn('"${pact_url_arg[@]}"', workflow)
        self.assertIn("inputs.historical_admin_ui_version == ''", workflow)
        verifier = (ROOT / "openbank-product-catalog/src/test/kotlin/com/openbank/productcatalog/contract/ProductCatalogPactBrokerProviderVerificationTest.kt").read_text()
        self.assertIn('System.getenv("HISTORICAL_PACT_URL")', verifier)
        self.assertIn('source.url == expectedUrl', verifier)

    def test_redirect_is_rejected_before_credentials_follow_it(self):
        handler = module.NoRedirect()
        with self.assertRaises(ValueError):
            handler.redirect_request(None, None, 302, "Moved", {}, "https://other.example.test/pact")

    def test_nonempty_broker_result_required(self):
        with tempfile.TemporaryDirectory() as temp:
            result_dir = Path(temp, module.PROVIDER, "build/test-results/providerPactTest")
            result_dir.mkdir(parents=True)
            result = result_dir / f"TEST-{module.TEST_CLASS}.xml"
            old_provider = module.PROVIDER
            try:
                module.PROVIDER = str(Path(temp, old_provider))
                result.write_text('<testsuite><testcase classname="ProductCatalogPactBrokerProviderVerificationTest" name="one"/></testsuite>')
                module.check_result()
                result.write_text('<testsuite><testcase classname="ProductCatalogPactBrokerProviderVerificationTest" name="one"><skipped/></testcase></testsuite>')
                with self.assertRaises(ValueError):
                    module.check_result()
            finally:
                module.PROVIDER = old_provider


if __name__ == "__main__":
    unittest.main()
