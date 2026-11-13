# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("renderer", Path(__file__).with_name("render-retained-worker.py"))
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


class RetainedWorkerTest(unittest.TestCase):
    def setUp(self):
        self.source = {
            "kind": "Rollout", "metadata": {"name": "transaction-service", "namespace": "synthetic"},
            "spec": {
                "selector": {"matchLabels": {renderer.APP_LABEL: "transaction-service"}},
                "template": {
                    "metadata": {"labels": {renderer.APP_LABEL: "transaction-service"}},
                    "spec": {"containers": [{
                        "name": "transaction-service", "image": "example.invalid/service:old",
                        "env": [{"name": "POSTGRES_PASSWORD", "valueFrom": {
                            "secretKeyRef": {"name": "synthetic-db", "key": "password"},
                        }}],
                        "volumeMounts": [{"name": "policy", "mountPath": "/policy"}],
                    }], "volumes": [{"name": "policy", "configMap": {"name": "synthetic-policy"}}]},
                },
            },
        }
        self.image = "example.invalid/service@sha256:" + "a" * 64

    def test_retention_is_independent_from_api_and_preserves_runtime_dependencies(self):
        original = copy.deepcopy(self.source)
        deployment, policy = renderer.render(self.source, self.image)
        self.assertEqual(self.source, original)
        self.assertNotEqual(deployment["spec"]["selector"], self.source["spec"]["selector"])
        pod = deployment["spec"]["template"]["spec"]
        container = pod["containers"][0]
        env = {e["name"]: e for e in container["env"]}
        self.assertEqual(env["POSTGRES_PASSWORD"], original["spec"]["template"]["spec"]["containers"][0]["env"][0])
        self.assertEqual(container["image"], self.image)
        self.assertEqual(env["OPENBANK_TRANSACTION_WORKER_VERSIONING_BUILD_ID"]["value"], "a" * 64)
        for key in ["QUARKUS_SCHEDULER_ENABLED", "OPENBANK_OUTBOX_DISPATCH_ENABLED", "OPENBANK_OUTBOX_RETENTION_ENABLED"]:
            self.assertEqual(env[key]["value"], "false")
        self.assertGreaterEqual(pod["terminationGracePeriodSeconds"], 60)
        self.assertEqual(policy["spec"]["ingress"][0]["ports"], [{"protocol": "TCP", "port": 8085}])

    def test_legacy_worker_keeps_unversioned_polling(self):
        resources = renderer.render(self.source, self.image, legacy=True)
        env = {e["name"]: e for e in resources[0]["spec"]["template"]["spec"]["containers"][0]["env"]}
        self.assertEqual(env["OPENBANK_TRANSACTION_WORKER_VERSIONING_ENABLED"]["value"], "false")
        self.assertNotIn("OPENBANK_TRANSACTION_WORKER_VERSIONING_BUILD_ID", env)

    def test_different_images_have_separate_retained_deployments(self):
        a = renderer.render(self.source, self.image)[0]
        b = renderer.render(self.source, "example.invalid/service@sha256:" + "b" * 64)[0]
        self.assertNotEqual(a["metadata"]["name"], b["metadata"]["name"])
        self.assertNotEqual(a["spec"]["selector"], b["spec"]["selector"])

    def test_mutable_image_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "complete sha256"):
            renderer.render(self.source, "example.invalid/service:latest")


if __name__ == "__main__":
    unittest.main()
