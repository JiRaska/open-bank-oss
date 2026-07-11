#!/usr/bin/env python3
"""Unit tests for check-threat-model-diff.py — the diff-aware threat-model gate (ADR-0030 D2).

The gate flags a money-path service whose PR moves a TRUST BOUNDARY (inbound REST, outbound
client edge, authn/transport application.yaml keys, or its gitops NetworkPolicy/Deployment)
WITHOUT a matching docs/threat-models/<service>.md update in the same diff. This suite proves:

  - a boundary change on a money-path service without a TM update IS a finding (and exits 1
    under --enforce);
  - the same change WITH the TM update in the diff is NOT a finding;
  - a non-money-path service's boundary change is ignored;
  - a money-path change that is NOT a trust boundary (e.g. a domain-layer file) is ignored;
  - the gitops name-token matcher resolves the shared-component services correctly.

The module has a hyphenated filename, so it is loaded via importlib — same technique as
gen_network_policies_test.py / authz_coverage_report_test.py.

Run:  python3 -m unittest openbank-infra/scripts/check_threat_model_diff_test.py -v
  or: python3 openbank-infra/scripts/check_threat_model_diff_test.py
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_SCRIPT_PATH = Path(__file__).parent / "check-threat-model-diff.py"
_spec = importlib.util.spec_from_file_location("check_threat_model_diff", _SCRIPT_PATH)
mod = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(mod)

# A real money-path service and a real non-money-path service, so the rules.yaml-backed
# money_path_services() loader (shared with check-threat-models.py) is exercised for real.
MONEY_PATH_SVC = "openbank-ledger-service"
NON_MONEY_PATH_SVC = "openbank-product-catalog"


class MoneyPathLoaderTest(unittest.TestCase):
    def test_ledger_is_money_path_and_catalog_is_not(self):
        services = mod.load_money_path_services()
        self.assertIn(MONEY_PATH_SVC, services)
        self.assertNotIn(NON_MONEY_PATH_SVC, services)


class GitopsTokenTest(unittest.TestCase):
    def test_service_suffix_and_bare_name_are_tokens(self):
        toks = mod.gitops_tokens("openbank-ledger-service")
        self.assertIn("ledger-service", toks)
        self.assertIn("ledger", toks)

    def test_multiword_service_splits_into_shared_component_tokens(self):
        # openbank-sepa-payment lives in the shared `payments` gitops component; its tokens must
        # include the sub-words so a token match on the component dir/file still resolves it.
        toks = mod.gitops_tokens("openbank-sepa-payment")
        self.assertIn("sepa-payment", toks)
        self.assertIn("sepa", toks)
        self.assertIn("payment", toks)

    def test_token_in_matches_whole_token_not_substring(self):
        self.assertTrue(mod.token_in({"ledger"}, "ledger-service.yaml"))
        # "ledger" must not spuriously match an unrelated word that merely contains the letters.
        self.assertFalse(mod.token_in({"ledger"}, "fledgling.yaml"))


class BoundaryReasonsTest(unittest.TestCase):
    """boundary_reasons is pure for REST/CLIENT/APP_YAML paths when base is None (no git needed)."""

    def test_inbound_rest_change_is_a_boundary(self):
        changed = [f"{MONEY_PATH_SVC}/src/main/kotlin/com/openbank/ledger/infrastructure/rest/LedgerResource.kt"]
        reasons = mod.boundary_reasons(MONEY_PATH_SVC, changed, base=None)
        self.assertEqual(len(reasons), 1)
        self.assertIn("inbound REST surface", reasons[0])

    def test_outbound_client_change_is_a_boundary(self):
        changed = [f"{MONEY_PATH_SVC}/src/main/kotlin/com/openbank/ledger/infrastructure/client/FooClient.kt"]
        reasons = mod.boundary_reasons(MONEY_PATH_SVC, changed, base=None)
        self.assertEqual(len(reasons), 1)
        self.assertIn("outbound client edge", reasons[0])

    def test_application_yaml_is_a_boundary_when_base_unknown(self):
        # With base=None the hunks cannot be inspected, so the gate is conservative and treats
        # an application.yaml touch as boundary-relevant.
        changed = [f"{MONEY_PATH_SVC}/src/main/resources/application.yaml"]
        reasons = mod.boundary_reasons(MONEY_PATH_SVC, changed, base=None)
        self.assertEqual(len(reasons), 1)
        self.assertIn("authn/listener/transport", reasons[0])

    def test_domain_layer_change_is_not_a_boundary(self):
        changed = [f"{MONEY_PATH_SVC}/src/main/kotlin/com/openbank/ledger/domain/model/JournalEntry.kt"]
        self.assertEqual(mod.boundary_reasons(MONEY_PATH_SVC, changed, base=None), [])

    def test_another_services_rest_change_is_not_this_services_boundary(self):
        changed = ["openbank-fx-service/src/main/kotlin/com/openbank/fx/infrastructure/rest/FxResource.kt"]
        self.assertEqual(mod.boundary_reasons(MONEY_PATH_SVC, changed, base=None), [])


def _run_gate(changed_lines: list[str], enforce: bool) -> subprocess.CompletedProcess[str]:
    """Drive the script end-to-end via --changed-files (bypasses git), like the CI PR run."""
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as fh:
        fh.write("\n".join(changed_lines))
        listing = fh.name
    argv = [sys.executable, str(_SCRIPT_PATH), "--changed-files", listing]
    if enforce:
        argv.append("--enforce")
    return subprocess.run(argv, capture_output=True, text=True)


class EndToEndTest(unittest.TestCase):
    REST_CHANGE = f"{MONEY_PATH_SVC}/src/main/kotlin/com/openbank/ledger/infrastructure/rest/LedgerResource.kt"
    TM_FILE = f"docs/threat-models/{MONEY_PATH_SVC}.md"

    def test_boundary_change_without_tm_update_is_flagged(self):
        res = _run_gate([self.REST_CHANGE], enforce=False)
        self.assertEqual(res.returncode, 0, "advisory mode must exit 0")
        self.assertIn("::warning::", res.stdout)
        self.assertIn(MONEY_PATH_SVC, res.stdout)

    def test_boundary_change_without_tm_update_fails_under_enforce(self):
        res = _run_gate([self.REST_CHANGE], enforce=True)
        self.assertEqual(res.returncode, 1, "enforce mode must exit 1 on a finding")
        self.assertIn("::error::", res.stdout)

    def test_boundary_change_with_tm_update_is_clean(self):
        res = _run_gate([self.REST_CHANGE, self.TM_FILE], enforce=True)
        self.assertEqual(res.returncode, 0, "a matching TM update clears the finding")
        self.assertIn("OK", res.stdout)
        self.assertNotIn("::error::", res.stdout)

    def test_non_money_path_boundary_change_is_ignored(self):
        catalog_rest = (
            f"{NON_MONEY_PATH_SVC}/src/main/kotlin/com/openbank/productcatalog/infrastructure/rest/FeesResource.kt"
        )
        res = _run_gate([catalog_rest], enforce=True)
        self.assertEqual(res.returncode, 0, "a non-money-path service is out of the gate's scope")
        self.assertNotIn("::error::", res.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
