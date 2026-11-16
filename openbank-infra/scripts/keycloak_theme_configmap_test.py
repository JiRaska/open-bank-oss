#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check that Keycloak receives the Admin UI theme exactly as generated."""

from base64 import b64decode
from hashlib import sha256
import json
from pathlib import Path
import unittest

import yaml


REPO = Path(__file__).resolve().parents[2]
COMPONENT = REPO / "openbank-infra/gitops/components/keycloak"
ADMIN_THEME = "openbank-admin"
ARTWORK = "explorer-prague-lion.webp"


class AdminLoginThemeTest(unittest.TestCase):
    def test_admin_client_has_the_only_theme_override(self):
        for filename, expected in (
            ("realm-template.json", ["openbank-admin-ui"]),
            ("customers-realm-template.json", []),
        ):
            with self.subTest(realm=filename):
                realm = json.loads((COMPONENT / filename).read_text())
                self.assertEqual(realm["loginTheme"], "openbank")
                clients = [
                    client["clientId"]
                    for client in realm["clients"]
                    if client.get("attributes", {}).get("login_theme") == ADMIN_THEME
                ]
                self.assertEqual(clients, expected)

    def test_deployment_mounts_every_generated_theme_resource(self):
        configmap = COMPONENT / f"keycloak-theme-{ADMIN_THEME}.yaml"
        document = yaml.safe_load(configmap.read_text())
        deployment = next(yaml.safe_load_all((COMPONENT / "keycloak.yaml").read_text()))
        pod = deployment["spec"]["template"]
        volume_name = f"theme-{ADMIN_THEME}"
        mount = next(
            mount for mount in pod["spec"]["containers"][0]["volumeMounts"]
            if mount["name"] == volume_name
        )
        self.assertEqual(mount["mountPath"], f"/opt/keycloak/themes/{ADMIN_THEME}/login")
        self.assertTrue(mount["readOnly"])

        volumes = {volume["name"]: volume for volume in pod["spec"]["volumes"]}
        volume = volumes[volume_name]["configMap"]
        self.assertEqual(volume["name"], document["metadata"]["name"])
        paths = {entry["key"]: entry["path"] for entry in volume["items"]}
        self.assertEqual(paths.keys(), document["data"].keys() | document["binaryData"].keys())

        sources = COMPONENT / "theme" / ADMIN_THEME / "login"
        for key, expected in document["data"].items():
            with self.subTest(key=key):
                self.assertEqual((sources / paths[key]).read_text(), expected)
        asset = REPO / "openbank-admin-ui/public/brand" / ARTWORK
        self.assertEqual(paths[ARTWORK], f"resources/img/{ARTWORK}")
        self.assertEqual(b64decode(document["binaryData"][ARTWORK], validate=True), asset.read_bytes())

        shared_configmap = COMPONENT / "keycloak-theme-openbank.yaml"
        checksum = sha256(shared_configmap.read_bytes() + configmap.read_bytes()).hexdigest()[:16]
        self.assertEqual(pod["metadata"]["annotations"]["openbank.dev/theme-checksum"], checksum)


if __name__ == "__main__":
    unittest.main()
