#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Keep a cold campaign JVM from being killed by its steady-state liveness probe."""
import pathlib
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "openbank-infra/gitops/components/campaign/campaign-service.yaml"


class CampaignStartupProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        documents = list(yaml.safe_load_all(MANIFEST.read_text()))
        deployment = next(doc for doc in documents if doc and doc.get("kind") == "Deployment"
                          and doc["metadata"]["name"] == "campaign-service")
        cls.container = next(container for container in deployment["spec"]["template"]["spec"]["containers"]
                             if container["name"] == "campaign-service")

    def test_startup_uses_the_started_health_check_on_the_management_listener(self):
        probe = self.container.get("startupProbe")
        self.assertIsNotNone(probe, "Cold JVM startup must gate liveness with a startupProbe")
        self.assertEqual(probe["httpGet"], {"path": "/q/health/started", "port": "management"})
        ports = {port["name"]: port["containerPort"] for port in self.container["ports"]}
        self.assertEqual(ports[probe["httpGet"]["port"]], 8085)

    def test_startup_window_covers_cold_boot_and_remains_bounded(self):
        probe = self.container.get("startupProbe", {})
        for key in ("initialDelaySeconds", "periodSeconds", "failureThreshold"):
            self.assertIs(type(probe.get(key)), int, key)
            self.assertGreater(probe[key], 0, key)
        # The previous liveness deadline killed a JVM that needed over a minute.
        # Keep the existing fleet startup budget, without allowing an unbounded delay.
        window = probe["initialDelaySeconds"] + probe["periodSeconds"] * probe["failureThreshold"]
        self.assertGreaterEqual(window, 120)
        self.assertLessEqual(window, 180)

    def test_steady_state_readiness_and_liveness_keep_their_original_checks(self):
        self.assertEqual(self.container["readinessProbe"], {
            "httpGet": {"path": "/q/health/ready", "port": 8085},
            "initialDelaySeconds": 10, "periodSeconds": 10,
        })
        self.assertEqual(self.container["livenessProbe"], {
            "httpGet": {"path": "/q/health/live", "port": 8085},
            "initialDelaySeconds": 20, "periodSeconds": 15,
        })


if __name__ == "__main__":
    unittest.main()
