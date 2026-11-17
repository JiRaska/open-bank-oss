# SPDX-License-Identifier: Apache-2.0
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from benchmark_scoped_reporting import seed
from scoped_trial_balance import produce


class ScopedTrialBalanceTest(unittest.TestCase):
    def test_three_contexts_reconcile_and_stay_separate(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            approved = seed(root, 6)
            index = produce(root / "manifest.json", approved, root / "journal.ndjson", root / "result")
            for context, expected in (("bank-a-domestic", "2.00"), ("bank-b-fos", "4.00"), ("bank-b-branch", "6.00")):
                content = (root / "result" / index[context]["file"]).read_bytes()
                self.assertEqual(hashlib.sha256(content).hexdigest(), index[context]["sha256"])
                snapshot = json.loads(content)
                self.assertEqual(snapshot["scope"]["contextId"], context)
                self.assertEqual(snapshot["trialBalance"]["lines"][0]["net"], expected)

    def test_missing_record_cannot_publish(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            approved = seed(root, 6)
            journal = root / "journal.ndjson"
            journal.write_bytes(b"".join(journal.read_bytes().splitlines(keepends=True)[1:]))
            with self.assertRaisesRegex(ValueError, "gap or duplicate"):
                produce(root / "manifest.json", approved, journal, root / "result")
            self.assertFalse((root / "result").exists())

    def test_balanced_export_with_wrong_scope_controls_cannot_publish(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            seed(root, 6)
            manifest_file = root / "manifest.json"
            manifest = json.loads(manifest_file.read_bytes())
            manifest["contexts"][1]["debitCents"] += 100
            manifest["contexts"][1]["creditCents"] += 100
            raw = json.dumps(manifest).encode()
            manifest_file.write_bytes(raw)
            with self.assertRaisesRegex(ValueError, "approved controls"):
                produce(manifest_file, hashlib.sha256(raw).hexdigest(), root / "journal.ndjson", root / "result")
            self.assertFalse((root / "result").exists())


if __name__ == "__main__":
    unittest.main()
