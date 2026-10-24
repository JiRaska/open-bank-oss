#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prove the scoped JVM run follows discovered Pact ownership, without a module roster."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AdminUiPactScopeTest(unittest.TestCase):
    def fixture(self, include_ui_producer=True):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        scripts = root / ".github/scripts"
        scripts.mkdir(parents=True)
        shutil.copy2(ROOT / ".github/scripts/derive-pact-drift-scope.sh", scripts)
        shutil.copy2(ROOT / ".github/scripts/pact-js-contracts.py", scripts)
        ui_test = root / "openbank-admin-ui/src/test/sample-pact.contract.test.ts"
        ui_test.parent.mkdir(parents=True)
        ui_test.write_text(
            "new PactV3({ consumer: 'openbank-admin-ui', provider: 'openbank-js-provider' })\n",
            encoding="utf-8",
        )

        tests = {
            "openbank-ui-module/src/test/kotlin/com/example/contract/UiPactConsumerTest.kt": (
                '@Pact(consumer = "openbank-admin-ui", provider = "openbank-ui-provider")\n'
                '@Pact(consumer = "openbank-side-consumer", provider = "openbank-side-provider")\n'
            ),
            "openbank-other-module/src/test/kotlin/com/example/contract/OtherPactConsumerTest.kt": (
                '@Pact(consumer = "openbank-other-consumer", provider = "openbank-other-provider")\n'
            ),
        }
        if not include_ui_producer:
            tests["openbank-ui-module/src/test/kotlin/com/example/contract/UiPactConsumerTest.kt"] = (
                '@Pact(consumer = "openbank-side-consumer", provider = "openbank-side-provider")\n'
            )
        for relative, source in tests.items():
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source, encoding="utf-8")

        pacts = root / "pacts"
        pacts.mkdir()
        pairs = [
            ("openbank-admin-ui", "openbank-js-provider"),
            ("openbank-admin-ui", "openbank-ui-provider"),
            ("openbank-side-consumer", "openbank-side-provider"),
            ("openbank-other-consumer", "openbank-other-provider"),
        ]
        if not include_ui_producer:
            pairs.remove(("openbank-admin-ui", "openbank-ui-provider"))
        for consumer, provider in pairs:
            (pacts / f"{consumer}-{provider}.json").write_text(
                json.dumps({"consumer": {"name": consumer}, "provider": {"name": provider},
                            "interactions": []}),
                encoding="utf-8",
            )
        return root

    def run_scope(self, root):
        return subprocess.run(
            ["bash", str(root / ".github/scripts/derive-pact-drift-scope.sh"),
             "--admin-ui-selected-scope"],
            cwd=root, env=dict(os.environ), capture_output=True, text=True, check=False,
        )

    def test_scope_includes_all_discovered_ui_producers_and_their_whole_output_set(self):
        root = self.fixture()
        result = self.run_scope(root)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        scope = json.loads(result.stdout)
        self.assertEqual(scope["tests"], [[
            "openbank-ui-module", "com.example.contract.UiPactConsumerTest"
        ]])
        self.assertEqual(scope["selectedPacts"], [
            "pacts/openbank-admin-ui-openbank-ui-provider.json",
            "pacts/openbank-side-consumer-openbank-side-provider.json",
        ])
        self.assertEqual(scope["consumerPacts"], [
            "pacts/openbank-admin-ui-openbank-ui-provider.json"
        ])
        self.assertEqual(scope["allJvmPacts"], [
            "pacts/openbank-admin-ui-openbank-ui-provider.json",
            "pacts/openbank-other-consumer-openbank-other-provider.json",
            "pacts/openbank-side-consumer-openbank-side-provider.json",
        ])
        self.assertEqual(scope["uncoveredPacts"], [])

    def test_missing_ui_jvm_consumer_producer_rejects_empty_scoped_run(self):
        root = self.fixture(include_ui_producer=False)
        result = self.run_scope(root)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("refusing an empty scoped run", result.stderr)


if __name__ == "__main__":
    unittest.main()
