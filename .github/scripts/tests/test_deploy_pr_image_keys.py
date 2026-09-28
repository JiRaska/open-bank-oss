# SPDX-License-Identifier: Apache-2.0

import json
import pathlib
import subprocess
import sys
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "deploy-pr-image-keys.py"


def run_patch(patch, *args):
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args], input=patch, text=True,
        capture_output=True, check=False,
    )


class DeployPrImageKeysTest(unittest.TestCase):
    def test_valid_image_pin_and_registry_port(self):
        result = run_patch("""diff --git a/openbank-infra/gitops/components/a/deployment.yaml b/openbank-infra/gitops/components/a/deployment.yaml
--- a/openbank-infra/gitops/components/a/deployment.yaml
+++ b/openbank-infra/gitops/components/a/deployment.yaml
@@ -1 +1 @@
-          image: \"registry.example:5000/openbank-ledger:sandbox-old\"
+          image: 'registry.example:5000/openbank-ledger:sandbox-new'
""")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout,
            "openbank-infra/gitops/components/a/deployment.yaml\tregistry.example:5000/openbank-ledger\n",
        )

    def test_disjoint_files_are_sorted(self):
        result = run_patch("""diff --git a/z.yaml b/z.yaml
--- a/z.yaml
+++ b/z.yaml
@@ -1 +1 @@
-image: registry/openbank-z:old
+image: registry/openbank-z:new
diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1 +1 @@
-image: registry/openbank-a:old
+image: registry/openbank-a:new
""")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [
            "a.yaml\tregistry/openbank-a",
            "z.yaml\tregistry/openbank-z",
        ])

    def test_shared_file_emits_each_image_key(self):
        result = run_patch("""diff --git a/shared.yaml b/shared.yaml
--- a/shared.yaml
+++ b/shared.yaml
@@ -1,2 +1,2 @@
-  - image: registry/openbank-a:old
+  - image: registry/openbank-a:new
-  - image: registry/openbank-b:old
+  - image: registry/openbank-b:new
""")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [
            "shared.yaml\tregistry/openbank-a",
            "shared.yaml\tregistry/openbank-b",
        ])

    def test_unpaired_image_edit_fails_without_partial_output(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1 +1 @@
-image: registry/openbank-a:old
""")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_repository_change_fails(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1 +1 @@
-image: registry/openbank-a:old
+image: registry/openbank-b:new
""")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_duplicate_key_within_file_fails(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1,2 +1,2 @@
-image: registry/openbank-a:old1
+image: registry/openbank-a:new1
-image: registry/openbank-a:old2
+image: registry/openbank-a:new2
""")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_non_image_only_diff_fails(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1 +1 @@
-allowed: old
+allowed: new
""")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_json_includes_tag_and_digest_replacements(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1 +1 @@
-image: registry.example:5000/openbank-a:old
+image: registry.example:5000/openbank-a:new@sha256:abcdef01
""", "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout,
            '[{"path":"a.yaml","repository":"registry.example:5000/openbank-a","old_image":"registry.example:5000/openbank-a:old","new_image":"registry.example:5000/openbank-a:new@sha256:abcdef01"}]\n')

    def test_json_strips_yaml_quotes(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1 +1 @@
-image: "registry/openbank-a:old"
+image: 'registry/openbank-a:new'
""", "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout,
            '[{"path":"a.yaml","repository":"registry/openbank-a","old_image":"registry/openbank-a:old","new_image":"registry/openbank-a:new"}]\n')

    def test_json_shared_manifest_is_sorted_by_repository(self):
        result = run_patch("""diff --git a/shared.yaml b/shared.yaml
--- a/shared.yaml
+++ b/shared.yaml
@@ -1,2 +1,2 @@
-  - image: registry/openbank-z:old
+  - image: registry/openbank-z:new
-  - image: registry/openbank-a:old
+  - image: registry/openbank-a:new
""", "--json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([record["repository"] for record in json.loads(result.stdout)],
            ["registry/openbank-a", "registry/openbank-z"])

    def test_malformed_digest_fails_without_partial_json(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1 +1 @@
-image: registry/openbank-a:old
+image: registry/openbank-a:new@sha256:
""", "--json")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_ambiguous_duplicate_key_fails_without_partial_json(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1,2 +1,2 @@
-image: registry/openbank-a:old1
+image: registry/openbank-a:new1
-image: registry/openbank-a:old2
+image: registry/openbank-a:new2
""", "--json")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_later_malformed_file_does_not_emit_earlier_json_record(self):
        result = run_patch("""diff --git a/a.yaml b/a.yaml
--- a/a.yaml
+++ b/a.yaml
@@ -1 +1 @@
-image: registry/openbank-a:old
+image: registry/openbank-a:new
diff --git a/z.yaml b/z.yaml
--- a/z.yaml
+++ b/z.yaml
@@ -1 +1 @@
-image: registry/openbank-z:old
""", "--json")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
