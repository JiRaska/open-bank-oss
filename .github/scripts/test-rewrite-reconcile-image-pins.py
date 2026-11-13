#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prove reconcile pins only the exact pushed image digest."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "rewrite_reconcile_image_pins", Path(__file__).with_name("rewrite-reconcile-image-pins.py")
)
assert _spec is not None and _spec.loader is not None
_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
rewrite = _module.rewrite


class RewriteReconcileImagePinsTest(unittest.TestCase):
    def test_rewrites_mutable_tag_to_tag_and_exact_digest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / "openbank-infra/gitops/components/demo/workloads.yaml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                "image: registry.invalid/openbank-demo-service:sandbox-oldtag # workload\n"
                "other: registry.invalid/openbank-ledger-service:sandbox-ledger\n",
                encoding="utf-8",
            )
            digests = '{"openbank-demo-service":"sha256:' + "a" * 64 + '"}'
            counts = rewrite(
                root,
                "registry.invalid",
                '{"openbank-demo-service":"sandbox-01234567"}',
                digests,
            )
            self.assertEqual(counts, {"openbank-demo-service": 1})
            self.assertIn(
                f"registry.invalid/openbank-demo-service:sandbox-01234567@sha256:{'a' * 64} # workload",
                manifest.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "registry.invalid/openbank-ledger-service:sandbox-ledger",
                manifest.read_text(encoding="utf-8"),
            )

    def test_rewrites_existing_digest_when_tag_is_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / "openbank-infra/gitops/components/demo/app.yaml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                f"image: registry.invalid/openbank-demo-service:sandbox-01234567@sha256:{'b' * 64}\n",
                encoding="utf-8",
            )
            digests = '{"openbank-demo-service":"sha256:' + "a" * 64 + '"}'
            counts = rewrite(
                root,
                "registry.invalid",
                '{"openbank-demo-service":"sandbox-01234567"}',
                digests,
            )
            self.assertEqual(counts, {"openbank-demo-service": 1})
            self.assertIn(f"@sha256:{'a' * 64}", manifest.read_text(encoding="utf-8"))

    def test_rejects_missing_digest_and_duplicate_map_keys(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "openbank-infra/gitops/components").mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, "same services"):
                rewrite(root, "registry.invalid", '{"openbank-demo-service":"sandbox-x"}', "{}")
            with self.assertRaisesRegex(ValueError, "duplicate JSON object key"):
                rewrite(
                    root,
                    "registry.invalid",
                    '{"openbank-demo-service":"sandbox-x","openbank-demo-service":"sandbox-y"}',
                    '{"openbank-demo-service":"sha256:' + "a" * 64 + '"}',
                )

    def test_rejects_missing_service_reference_without_partial_rewrites(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / "openbank-infra/gitops/components/demo/workloads.yaml"
            manifest.parent.mkdir(parents=True)
            original = (
                "image: registry.invalid/openbank-demo-service:sandbox-oldtag\n"
                "# image: registry.invalid/openbank-missing-service:sandbox-commented\n"
                "other: registry.invalid/openbank-ledger-service:sandbox-ledger\n"
            )
            manifest.write_text(original, encoding="utf-8")
            tags = (
                '{"openbank-demo-service":"sandbox-01234567",'
                '"openbank-missing-service":"sandbox-89abcdef"}'
            )
            digests = (
                '{"openbank-demo-service":"sha256:'
                + "a" * 64
                + '","openbank-missing-service":"sha256:'
                + "b" * 64
                + '"}'
            )

            with self.assertRaisesRegex(
                ValueError,
                "no GitOps sandbox image reference found for selected service "
                "openbank-missing-service",
            ):
                rewrite(root, "registry.invalid", tags, digests)

            self.assertEqual(manifest.read_text(encoding="utf-8"), original)


if __name__ == "__main__":
    unittest.main()
