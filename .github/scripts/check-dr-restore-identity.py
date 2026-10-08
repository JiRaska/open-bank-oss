#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the automated CNPG restore identity against its declared S3 read grant.

This is source proof only. It cannot establish that Terraform was applied or that
EKS Pod Identity credentials work in a running restore pod.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/dr-restore-verify.yml"
TEMPLATE = ROOT / "openbank-infra/gitops/dr-restore-templates/cnpg-recovery-cluster.yaml.tmpl"
TERRAFORM = ROOT / "openbank-infra/aws/envs/sandbox-platform/db-backups.tf"


def check(workflow: str, template: str, terraform: str) -> list[str]:
    findings: list[str] = []
    # Require the actual namespace passed to both kubectl and the template, rather
    # than accepting an unused declaration elsewhere in the workflow.
    match = re.search(r'^\s*NS="([^"]+)"\s*$', workflow, re.M)
    namespace = match.group(1) if match else None
    if not namespace or 'kubectl create namespace "$NS"' not in workflow or not re.search(
        r'sed [^\n]*\$\{NS\}[^\n]*cnpg-recovery-cluster\.yaml\.tmpl', workflow
    ):
        findings.append("workflow namespace is not traceable from NS through cluster apply")
        return findings

    try:
        cluster = yaml.safe_load(template.replace("${NS}", namespace))
        assert isinstance(cluster, dict) and cluster.get("kind") == "Cluster"
        sa = cluster["metadata"]["name"]  # CNPG instance SA has the Cluster name.
        rendered_namespace = cluster["metadata"]["namespace"]
        stores = [c["barmanObjectStore"] for c in cluster["spec"]["externalClusters"]]
        assert len(stores) == 1
        destination = stores[0]["destinationPath"]
        assert stores[0]["s3Credentials"]["inheritFromIAMRole"] is True
        prefix = destination.split("/", 3)[3].split("/", 1)[0]
    except (AssertionError, KeyError, TypeError, IndexError, yaml.YAMLError):
        findings.append("recovery Cluster source, IAM credential mode, or archive prefix is unrecognized")
        return findings
    if rendered_namespace != namespace:
        findings.append("rendered Cluster namespace differs from workflow namespace")

    # The association uses the fleet map's namespace/SA and the shared backup role.
    associations = set(re.findall(
        r'\{\s*namespace\s*=\s*"([^"]+)"\s*,\s*sa\s*=\s*"([^"]+)"\s*\}', terraform
    ))
    if not re.search(
        r'resource "aws_eks_pod_identity_association" "db_backups_fleet"\s*\{'
        r'[^}]*for_each\s*=\s*local\.db_backup_clusters'
        r'[^}]*namespace\s*=\s*each\.value\.namespace'
        r'[^}]*service_account\s*=\s*each\.value\.sa'
        r'[^}]*role_arn\s*=\s*aws_iam_role\.db_backups\.arn', terraform, re.S
    ):
        findings.append("fleet association no longer binds mapped namespace/SA to backup role")
    if (namespace, sa) not in associations:
        findings.append(f"restore identity ({namespace}, {sa}) has no declared Pod Identity association")

    # Require both object read and bucket listing to carry the SAME principal
    # tags. An untagged or wrong-prefix statement must never satisfy this check.
    for sid, action in (("LedgerDrillReadsLedgerArchive", "s3:GetObject"),
                        ("LedgerDrillListsLedgerArchive", "s3:ListBucket")):
        statement = re.search(r'\bstatement\s*\{\s*sid\s*=\s*"' + sid +
                              r'"(.*?)\n  \}', terraform, re.S)
        body = statement.group(1) if statement else ""
        if (action not in body or f'/{prefix}/*' not in body and
                (action != "s3:ListBucket" or f'"{prefix}/*"' not in body)):
            findings.append(f"{sid} lacks the archive prefix or {action}")
        for tag, value in (("kubernetes-namespace", namespace),
                           ("kubernetes-service-account", sa)):
            if not re.search(r'variable\s*=\s*"aws:PrincipalTag/' + tag +
                             r'"\s*\n\s*values\s*=\s*\["' + re.escape(value) + r'"\]', body):
                findings.append(f"{sid} is not scoped to {tag}={value}")
    return findings


def self_test() -> None:
    workflow = 'NS="dr-verify"\nkubectl create namespace "$NS"\nsed "s#${NS}#${NS}#g" "$TEMPLATES/cnpg-recovery-cluster.yaml.tmpl" | kubectl apply -f -\n'
    template = '''kind: Cluster
metadata: {name: restore-db, namespace: "${NS}"}
spec:
  externalClusters:
    - barmanObjectStore:
        destinationPath: s3://example/ledger-db
        s3Credentials: {inheritFromIAMRole: true}
'''
    tf = '''resource "aws_eks_pod_identity_association" "db_backups_fleet" {
 for_each = local.db_backup_clusters
 namespace = each.value.namespace
 service_account = each.value.sa
 role_arn = aws_iam_role.db_backups.arn
}
x = { namespace = "dr-verify", sa = "restore-db" }
  statement {
    sid = "LedgerDrillReadsLedgerArchive"
    actions = ["s3:GetObject"]
    resources = ["${bucket}/ledger-db/*"]
    condition {
      variable = "aws:PrincipalTag/kubernetes-namespace"
      values = ["dr-verify"]
    }
    condition {
      variable = "aws:PrincipalTag/kubernetes-service-account"
      values = ["restore-db"]
    }
  }
  statement {
    sid = "LedgerDrillListsLedgerArchive"
    actions = ["s3:ListBucket"]
    values = ["ledger-db/*"]
    condition {
      variable = "aws:PrincipalTag/kubernetes-namespace"
      values = ["dr-verify"]
    }
    condition {
      variable = "aws:PrincipalTag/kubernetes-service-account"
      values = ["restore-db"]
    }
  }
'''
    assert not check(workflow, template, tf)
    assert any("association" in f for f in check(workflow, template,
                                                   tf.replace('sa = "restore-db"', 'sa = "wrong-db"')))
    assert any("kubernetes-namespace" in f for f in check(
        workflow, template, tf.replace('values = ["dr-verify"]', 'values = ["ledger"]')))
    assert any("archive prefix" in f for f in check(
        workflow, template, tf.replace('ledger-db/*', 'other-db/*')))
    assert any("not traceable" in f for f in check(
        workflow.replace('kubectl create namespace "$NS"', 'kubectl create namespace ledger'),
        template, tf))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("restore identity checker self-test passed")
    else:
        issues = check(WORKFLOW.read_text(), TEMPLATE.read_text(), TERRAFORM.read_text())
        for issue in issues:
            print(f"::error::{issue}")
        if not issues:
            print("restore identity matches Pod Identity association and cross-prefix grant")
        raise SystemExit(bool(issues))
