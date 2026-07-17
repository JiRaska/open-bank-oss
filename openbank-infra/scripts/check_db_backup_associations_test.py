#!/usr/bin/env python3
"""Unit tests for check-db-backup-associations.py (issue #1444)."""

from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import textwrap
import unittest

_SPEC = importlib.util.spec_from_file_location(
    "check_db_backup_associations",
    pathlib.Path(__file__).with_name("check-db-backup-associations.py"),
)
mod = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(mod)


class DeclaredAssociationsTest(unittest.TestCase):
    def test_reads_sa_entries_from_the_map(self):
        tf = 'ledger = { namespace = "ledger", sa = "ledger-db" }\nkyc = { namespace = "kyc", sa = "kyc-db" }'
        self.assertEqual(mod.declared_associations(tf), {"ledger-db", "kyc-db"})

    def test_reads_standalone_service_account_resources(self):
        # A cluster covered by a standalone aws_eks_pod_identity_association is still covered;
        # the check is about the outcome, not the style it was written in.
        tf = 'resource "aws_eks_pod_identity_association" "x" {\n  service_account = "statements-db"\n}'
        self.assertEqual(mod.declared_associations(tf), {"statements-db"})

    def test_empty_file_declares_nothing(self):
        self.assertEqual(mod.declared_associations(""), set())


class BackingUpClustersTest(unittest.TestCase):
    def _write(self, name: str, body: str) -> pathlib.Path:
        p = pathlib.Path(self.tmp.name) / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(textwrap.dedent(body))
        return p

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = pathlib.Path(self.tmp.name)

    def test_finds_a_cnpg_cluster_with_a_backup(self):
        self._write(
            "svc/postgres.yaml",
            """
            apiVersion: postgresql.cnpg.io/v1
            kind: Cluster
            metadata:
              name: foo-db
              namespace: foo
            spec:
              backup:
                barmanObjectStore:
                  destinationPath: s3://openbank-sandbox-db-backups/foo-db
            """,
        )
        found = mod.backing_up_clusters(self.dir)
        self.assertEqual([(n, ns) for n, ns, _, _ in found], [("foo-db", "foo")])

    def test_ignores_strimzi_kafka_which_also_uses_kind_Cluster(self):
        # The real trap: kafka-*-mtls.yaml manifests are `kind: Cluster` too. Matching on kind
        # alone would file Kafka brokers as databases missing their backups.
        self._write(
            "svc/kafka.yaml",
            """
            apiVersion: kafka.strimzi.io/v1beta2
            kind: Cluster
            metadata:
              name: my-kafka
              namespace: messaging
            spec:
              backup:
                barmanObjectStore:
                  destinationPath: s3://openbank-sandbox-db-backups/my-kafka
            """,
        )
        self.assertEqual(mod.backing_up_clusters(self.dir), [])

    def test_parses_multi_document_manifests(self):
        # 30 of the 42 files declaring a CNPG Cluster are multi-doc. This is exactly why the
        # check is Python and not Terraform's yamldecode, which cannot parse them at all.
        self._write(
            "svc/postgres.yaml",
            """
            apiVersion: v1
            kind: Namespace
            metadata:
              name: bar
            ---
            apiVersion: postgresql.cnpg.io/v1
            kind: Cluster
            metadata:
              name: bar-db
              namespace: bar
            spec:
              backup:
                barmanObjectStore:
                  destinationPath: s3://openbank-sandbox-db-backups/bar-db
            """,
        )
        self.assertEqual([n for n, _, _, _ in mod.backing_up_clusters(self.dir)], ["bar-db"])

    def test_skips_a_cluster_with_no_backup_stanza(self):
        # These exist (12 of them) and are a real problem — but a DIFFERENT one, fixed in
        # gitops, not by an association. This check must not claim to cover them.
        self._write(
            "svc/postgres.yaml",
            """
            apiVersion: postgresql.cnpg.io/v1
            kind: Cluster
            metadata:
              name: nobackup-db
              namespace: svc
            spec:
              instances: 1
            """,
        )
        self.assertEqual(mod.backing_up_clusters(self.dir), [])

    def test_reports_a_cluster_pointing_at_another_bucket_with_its_destination(self):
        # goalert-db's real shape: it backs up fine, just to the legacy unmanaged bucket. The
        # caller needs the destination to tell "governance gap" from "no backups at all".
        self._write(
            "svc/postgres.yaml",
            """
            apiVersion: postgresql.cnpg.io/v1
            kind: Cluster
            metadata:
              name: legacy-db
              namespace: obs
            spec:
              backup:
                barmanObjectStore:
                  destinationPath: s3://some-other-bucket/legacy-db
            """,
        )
        found = mod.backing_up_clusters(self.dir)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0][3], "s3://some-other-bucket/legacy-db")

    def test_unparseable_yaml_does_not_silently_drop_the_file(self):
        self._write("svc/broken.yaml", "kind: Cluster\n  bad: [indent")
        # Must not raise; the script warns to stderr instead so a malformed manifest is loud.
        self.assertEqual(mod.backing_up_clusters(self.dir), [])


if __name__ == "__main__":
    unittest.main()
