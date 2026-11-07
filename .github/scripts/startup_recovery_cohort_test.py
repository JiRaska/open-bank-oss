#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Protect bounded cold startup and unchanged steady-state checks for three services."""
import pathlib
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]


class StartupProbeContract:
    manifest: str
    container_name: str
    management_port: int
    steady_port: object
    liveness_period: int

    @classmethod
    def setUpClass(cls):
        documents = yaml.safe_load_all((ROOT / cls.manifest).read_text())
        deployment = next(doc for doc in documents if doc and doc.get("kind") == "Deployment"
                          and doc["metadata"]["name"] == cls.container_name)
        cls.container = next(container for container in deployment["spec"]["template"]["spec"]["containers"]
                             if container["name"] == cls.container_name)

    def test_startup_checks_started_on_the_real_management_listener(self):
        probe = self.container.get("startupProbe")
        self.assertIsNotNone(probe, "A cold JVM must finish startup before liveness applies")
        self.assertEqual(probe["httpGet"], {"path": "/q/health/started", "port": "management"})
        ports = {port["name"]: port["containerPort"] for port in self.container["ports"]}
        self.assertEqual(ports[probe["httpGet"]["port"]], self.management_port)

    def test_startup_window_covers_cold_boot_and_remains_bounded(self):
        probe = self.container.get("startupProbe", {})
        for key in ("initialDelaySeconds", "periodSeconds", "failureThreshold"):
            self.assertIs(type(probe.get(key)), int, key)
            self.assertGreater(probe[key], 0, key)
        window = probe["initialDelaySeconds"] + probe["periodSeconds"] * probe["failureThreshold"]
        self.assertGreaterEqual(window, 120)
        self.assertLessEqual(window, 180)

    def test_original_steady_state_checks_are_preserved(self):
        self.assertEqual(self.container["readinessProbe"], {
            "httpGet": {"path": "/q/health/ready", "port": self.steady_port},
            "initialDelaySeconds": 10, "periodSeconds": 10,
        })
        self.assertEqual(self.container["livenessProbe"], {
            "httpGet": {"path": "/q/health/live", "port": self.steady_port},
            "initialDelaySeconds": 20, "periodSeconds": self.liveness_period,
        })


class DevopsStartupProbeTest(StartupProbeContract, unittest.TestCase):
    manifest = "openbank-infra/gitops/components/devops-agent/devops-agent.yaml"
    container_name = "devops-agent"
    management_port = 8085
    steady_port = "management"
    liveness_period = 30


class KybStartupProbeTest(StartupProbeContract, unittest.TestCase):
    manifest = "openbank-infra/gitops/components/kyb/kyb-service.yaml"
    container_name = "kyb-service"
    management_port = 8090
    steady_port = 8090
    liveness_period = 15


class WealthStartupProbeTest(StartupProbeContract, unittest.TestCase):
    manifest = "openbank-infra/gitops/components/wealth/wealth-service.yaml"
    container_name = "wealth-service"
    management_port = 8090
    steady_port = 8090
    liveness_period = 15


if __name__ == "__main__":
    unittest.main()
