# SPDX-License-Identifier: Apache-2.0

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "audit-closed-deploy-pr.py"
spec = importlib.util.spec_from_file_location("audit_closed_deploy_pr", SCRIPT)
audit_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_module)

PATH = "openbank-infra/gitops/components/payments/payments-services.yaml"
REPO = "registry.example/openbank-domestic-payment"


class AuditClosedDeployPrTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.old = self.commit("old")
        self.middle = self.commit("middle")
        self.new = self.commit("new")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], text=True,
                              capture_output=True, check=True).stdout.strip()

    def commit(self, label):
        (self.root / "marker").write_text(label, encoding="utf-8")
        self.git("add", "marker")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", label)
        return self.git("rev-parse", "HEAD")

    def image(self, commit, suffix=""):
        return f"{REPO}:sandbox-{commit[:8]}{suffix}"

    def manifest(self, image):
        target = self.root / PATH
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(f"containers:\n  - image: {image}\n", encoding="utf-8")

    def record(self, image):
        return {"path": PATH, "repository": REPO, "old_image": self.image(self.old),
                "new_image": image}

    def test_newer_current_pin_covers_closed_pr(self):
        self.manifest(self.image(self.new))
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))])
        self.assertEqual(result[0]["status"], "covered")

    def test_older_current_pin_is_a_gap(self):
        self.manifest(self.image(self.old))
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))])
        self.assertEqual(result[0]["status"], "gap")

    def test_exact_image_is_covered(self):
        self.manifest(self.image(self.middle))
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))])
        self.assertEqual(result[0]["status"], "covered")

    def test_same_source_rebuild_is_not_assumed_covered(self):
        self.manifest(self.image(self.middle, "-run123"))
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))])
        self.assertEqual(result[0]["status"], "unknown")

    def test_missing_image_is_unknown(self):
        self.manifest("registry.example/openbank-other:sandbox-12345678")
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))])
        self.assertEqual(result[0]["status"], "unknown")

    def test_path_traversal_cannot_read_outside_components(self):
        self.manifest(self.image(self.old))
        record = self.record(self.image(self.middle))
        record["path"] = "openbank-infra/gitops/components/../../outside.yaml"
        result = audit_module.audit(self.root, [record])
        self.assertEqual(result[0]["status"], "unknown")

    def test_duplicate_key_fails(self):
        self.manifest(self.image(self.old))
        record = self.record(self.image(self.middle))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            audit_module.audit(self.root, [record, record])

    def test_newer_open_pr_is_visible_but_not_counted_as_deployed(self):
        self.manifest(self.image(self.old))
        successor = {"pr": 11388, "mergeable": "MERGEABLE",
                     "pins": [self.record(self.image(self.new))]}
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))], [successor])
        self.assertEqual(result[0]["status"], "pending-successor")
        self.assertEqual(result[0]["successor_pr"], 11388)

    def test_disjoint_successor_does_not_hide_a_missing_pin(self):
        self.manifest(self.image(self.old))
        other = {"pr": 11388, "pins": [{"path": PATH, "repository": "registry.example/other",
                                         "new_image": "registry.example/other:sandbox-12345678"}]}
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))], [other])
        self.assertEqual(result[0]["status"], "gap")
        self.assertIsNone(result[0]["successor_pr"])

    def test_partial_successor_preserves_unique_payment_gap(self):
        self.manifest(self.image(self.old))
        fx_path = "openbank-infra/gitops/components/fx-service/fx-service.yaml"
        fx_repo = "registry.example/openbank-fx-service"
        fx_manifest = self.root / fx_path
        fx_manifest.parent.mkdir(parents=True, exist_ok=True)
        fx_manifest.write_text(f"image: {fx_repo}:sandbox-{self.old[:8]}\n", encoding="utf-8")
        fx_record = {"path": fx_path, "repository": fx_repo,
                     "old_image": f"{fx_repo}:sandbox-{self.old[:8]}",
                     "new_image": f"{fx_repo}:sandbox-{self.middle[:8]}"}
        successor = {"pr": 11388, "mergeable": "MERGEABLE", "pins": [{**fx_record,
                                            "new_image": f"{fx_repo}:sandbox-{self.new[:8]}"}]}
        result = audit_module.audit(
            self.root, [fx_record, self.record(self.image(self.middle))], [successor],
        )
        self.assertEqual([row["status"] for row in result], ["pending-successor", "gap"])

    def test_stale_successor_cannot_suppress_gap(self):
        self.manifest(self.image(self.old))
        successor_pin = self.record(self.image(self.new))
        successor_pin["old_image"] = self.image(self.middle)
        successor = {"pr": 11388, "mergeable": "MERGEABLE", "pins": [successor_pin]}
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))], [successor])
        self.assertEqual(result[0]["status"], "gap")

    def test_unmergeable_successor_cannot_suppress_gap(self):
        self.manifest(self.image(self.old))
        successor = {"pr": 11388, "mergeable": "CONFLICTING",
                     "pins": [self.record(self.image(self.new))]}
        result = audit_module.audit(self.root, [self.record(self.image(self.middle))], [successor])
        self.assertEqual(result[0]["status"], "gap")


if __name__ == "__main__":
    unittest.main()
