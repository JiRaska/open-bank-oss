#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Focused fixtures for reconcile's exact build-push image source proof."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "verify_reconcile_image_map", Path(__file__).with_name("verify-reconcile-image-map.py")
)
assert _spec is not None and _spec.loader is not None
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
verify = _module.verify


SHA = "0123456789abcdef0123456789abcdef01234567"
TAG = "sandbox-01234567"
SERVICES = '["openbank-demo-service","openbank-ledger-service"]'
TAGS = '{"openbank-demo-service":"sandbox-01234567","openbank-ledger-service":"sandbox-01234567"}'


class VerifyReconcileImageMapTest(unittest.TestCase):
    def test_accepts_exact_service_map_from_this_build(self) -> None:
        verify(SERVICES, TAGS, TAG, SHA)

    def test_rejects_duplicate_json_keys(self) -> None:
        with self.assertRaisesRegex(ValueError, "duplicate JSON object key"):
            verify(
                '["openbank-demo-service"]',
                '{"openbank-demo-service":"sandbox-01234567","openbank-demo-service":"sandbox-01234567"}',
                TAG,
                SHA,
            )

    def test_rejects_missing_or_extra_service_coverage(self) -> None:
        for tags in (
            '{"openbank-demo-service":"sandbox-01234567"}',
            '{"openbank-demo-service":"sandbox-01234567","openbank-ledger-service":"sandbox-01234567","openbank-extra":"sandbox-01234567"}',
        ):
            with self.subTest(tags=tags), self.assertRaisesRegex(ValueError, "cover exactly"):
                verify(SERVICES, tags, TAG, SHA)

    def test_rejects_wrong_source_tag(self) -> None:
        with self.assertRaisesRegex(ValueError, "actual build-push tag"):
            verify(SERVICES, TAGS.replace(TAG, "sandbox-deadbeef"), TAG, SHA)

    def test_rejects_tag_not_bound_to_workflow_sha(self) -> None:
        with self.assertRaisesRegex(ValueError, "does not identify"):
            verify(SERVICES, TAGS, "sandbox-deadbeef", SHA)

    def test_rejects_malformed_service_list(self) -> None:
        with self.assertRaisesRegex(ValueError, "unique valid service"):
            verify('["openbank-demo-service","openbank-demo-service"]', '{"openbank-demo-service":"sandbox-01234567"}', TAG, SHA)


if __name__ == "__main__":
    unittest.main()
