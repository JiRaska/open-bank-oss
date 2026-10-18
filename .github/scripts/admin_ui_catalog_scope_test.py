# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Keep the Admin UI's requested catalog read grant in both canonical realm imports."""

import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
REALMS = (
    ROOT / "openbank-infra/docker/keycloak/realm/openbank-realm.json",
    ROOT / "openbank-infra/gitops/components/keycloak/realm-template.json",
)


class AdminUiCatalogScopeTest(unittest.TestCase):
    def test_catalog_read_is_an_optional_token_scope_in_both_imports(self):
        for path in REALMS:
            with self.subTest(realm=path.name, directory=path.parent.name):
                realm = json.loads(path.read_text())
                client = next(c for c in realm["clients"] if c["clientId"] == "openbank-admin-ui")
                scope = next(s for s in realm["clientScopes"] if s["name"] == "catalog:read")
                self.assertEqual(scope["attributes"]["include.in.token.scope"], "true")
                self.assertIn("catalog:read", client["optionalClientScopes"])
                self.assertNotIn("catalog:read", client["defaultClientScopes"])

    def test_pension_scopes_remain_optional_without_catalog_write_grants(self):
        for path in REALMS:
            with self.subTest(realm=path.name, directory=path.parent.name):
                realm = json.loads(path.read_text())
                client = next(c for c in realm["clients"] if c["clientId"] == "openbank-admin-ui")
                optional = set(client["optionalClientScopes"])
                self.assertTrue({"pension:legal-approve", "pension:product-approve"} <= optional)
                assigned = optional | set(client["defaultClientScopes"])
                self.assertTrue({"catalog:author", "catalog:publish"}.isdisjoint(assigned))


if __name__ == "__main__":
    unittest.main()
