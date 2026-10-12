#!/usr/bin/env python3
"""Offline evidence for the embedded in-cluster Grype scanner identity rules."""

import ast
import pathlib
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "openbank-infra/gitops/components/infra-vuln-scanner/infra-vuln-scanner.yaml"


def scanner_functions():
    docs = yaml.safe_load_all(MANIFEST.read_text())
    cron = next(doc for doc in docs if isinstance(doc, dict) and doc.get("kind") == "CronJob")
    script = cron["spec"]["jobTemplate"]["spec"]["template"]["spec"]["initContainers"][1]["args"][0]
    python = script.split("'PYEOF'\n", 1)[1].split("\nPYEOF\n", 1)[0]
    tree = ast.parse(python)
    # The production script executes Grype and kubectl at module level. Load
    # only its pure identity helpers; this test must never touch a cluster.
    definitions = ast.Module(body=[node for node in tree.body if isinstance(node, ast.FunctionDef)], type_ignores=[])
    namespace = {"re": __import__("re")}
    exec(compile(definitions, str(MANIFEST), "exec"), namespace)
    namespace["DIGEST"] = namespace["re"].compile(r"@(?P<digest>sha256:[a-f0-9]{64})$")
    return namespace


class ScannerIdentityTest(unittest.TestCase):
    def setUp(self):
        self.functions = scanner_functions()

    def pod(self, digest, *, ready=True, image_id=None):
        return {"status": {"phase": "Running", "conditions": [{"type": "Ready", "status": "True"}],
                           "containerStatuses": [{"name": "db", "ready": ready,
                                                  "imageID": image_id or f"docker-pullable://repo/db@{digest}"}]},
                "spec": {"containers": [{"name": "db", "image": "ghcr.io/cloudnative-pg/postgresql:18.6"}]}}

    def test_immutable_digest_per_ready_pod_in_rollout(self):
        a, b = "sha256:" + "a" * 64, "sha256:" + "b" * 64
        found = self.functions["active_images"]({"items": [self.pod(a), self.pod(b)]}, "ghcr.io/cloudnative-pg/postgresql")
        self.assertEqual(found, {a: "ghcr.io/cloudnative-pg/postgresql@" + a,
                                 b: "ghcr.io/cloudnative-pg/postgresql@" + b})

    def test_decoy_suffixes_do_not_match(self):
        digest = "sha256:" + "a" * 64
        decoy = self.pod(digest)
        decoy["spec"]["containers"][0]["image"] = "ghcr.io/cloudnative-pg/postgresql-helper:1"
        self.assertEqual(self.functions["active_images"]({"items": [decoy]},
                                                        "ghcr.io/cloudnative-pg/postgresql"), {})
        self.assertEqual(self.functions["normalized_repository"](
            "ghcr.io/kafbat/kafka-ui-proxy:1"), "ghcr.io/kafbat/kafka-ui-proxy")
        self.assertNotEqual(self.functions["normalized_repository"](
            "ghcr.io/kafbat/kafka-ui-proxy:1"), "ghcr.io/kafbat/kafka-ui")
        self.assertEqual(self.functions["normalized_repository"](
            "ghcr.io/cloudnative-pg/postgresql:18.6"), "ghcr.io/cloudnative-pg/postgresql")

    def test_bare_container_id_and_unready_pod_do_not_become_scan_identity(self):
        digest = "sha256:" + "a" * 64
        with self.assertRaisesRegex(RuntimeError, "no registry digest"):
            self.functions["active_images"]({"items": [self.pod(digest, image_id="containerd://" + digest)]},
                                             "ghcr.io/cloudnative-pg/postgresql")
        with self.assertRaisesRegex(RuntimeError, "no registry digest"):
            self.functions["active_images"]({"items": [self.pod(digest, ready=False)]},
                                             "ghcr.io/cloudnative-pg/postgresql")


if __name__ == "__main__":
    unittest.main()
