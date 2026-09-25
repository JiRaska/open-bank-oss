#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Cloud FinOps collector (ADR-0316).

Turns `infracost breakdown --format json` output (one file per OpenTofu env) plus an
`aws ce get-cost-and-usage` DAILY/SERVICE response into rows for four ClickHouse tables
(openbank-infra/gitops/components/analytics/cloud-finops-schema-configmap.yaml):

  finops_iac_resources   one row per priced resource per run (the IaC estimate)
  finops_findings        one row per (resource, rule) violation per run, with an estimated saving
  finops_aws_daily_cost  actual daily spend by AWS service (Cost Explorer), last write wins
  finops_ingest_runs     one row per SUCCESSFUL run, written LAST — the freshness signal

Run by the in-cluster `cloud-finops-collector` CronJob
(openbank-infra/gitops/components/admin-ui/cloud-finops-collector.yaml), which clones origin/main
and executes THIS file from the clone, so the rules and the evaluator the tests cover are the
ones that run.

Honest by construction (the "successful no-op" lesson in the root CLAUDE.md):
  * an env whose breakdown has zero resources is an ERROR, never a zero-cost estimate;
  * a Cost Explorer response with no rows is an ERROR, never zero spend;
  * every insert is verified by reading back count() for this run_id — a 200 from ClickHouse
    is not taken as evidence the rows exist;
  * finops_ingest_runs is written only after everything else verified, so the dashboard's
    freshness panel goes red when any part of a run failed.

Stdlib only.

  cloud-finops-collect.py list-envs --rules <rules.json>
  cloud-finops-collect.py ingest --rules <rules.json> --infracost-dir <dir> --ce <ce.json>
  cloud-finops-collect.py evaluate --rules <rules.json> --infracost-dir <dir>   (dry run, prints JSON)
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
import re
import sys
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

INSTANCE_RE = re.compile(r"\b(?:db\.|cache\.)?([a-z][a-z0-9-]*?)\.(?:nano|micro|small|medium|large|metal|\d*xlarge)\b")

# Coarse service category, shared by both sides so the estimate-vs-actual panel compares like
# with like. Deliberately coarse: an IaC resource type and a Cost Explorer SERVICE dimension do
# not map one-to-one (NAT and EBS bill under "EC2 - Other"), so the category is the finest grain
# at which the two sides can honestly be compared.
IAC_CATEGORY_PREFIXES = [
    ("aws_nat_gateway", "EC2-Other"),
    ("aws_ebs_", "EC2-Other"),
    ("aws_eip", "EC2-Other"),
    ("aws_instance", "EC2"),
    ("aws_launch_template", "EC2"),
    ("aws_autoscaling", "EC2"),
    ("aws_eks_node_group", "EC2"),
    ("aws_eks_", "EKS"),
    ("aws_db_", "RDS"),
    ("aws_rds_", "RDS"),
    ("aws_s3_", "S3"),
    ("aws_lambda_", "Lambda"),
    ("aws_kms_", "KMS"),
    ("aws_cloudwatch_", "CloudWatch"),
    ("aws_lb", "ELB"),
    ("aws_alb", "ELB"),
    ("aws_route53_", "Route53"),
    ("aws_ecr_", "ECR"),
    ("aws_secretsmanager_", "SecretsManager"),
    ("aws_cloudfront_", "CloudFront"),
    ("aws_vpc_endpoint", "VPC"),
    ("aws_config_", "Config"),
]
CE_CATEGORY_SUBSTRINGS = [
    ("EC2 - Other", "EC2-Other"),
    ("Elastic Compute Cloud", "EC2"),
    ("Kubernetes Service", "EKS"),
    ("Relational Database", "RDS"),
    ("Simple Storage Service", "S3"),
    ("Lambda", "Lambda"),
    ("Key Management", "KMS"),
    ("CloudWatch", "CloudWatch"),
    ("Elastic Load Balancing", "ELB"),
    ("Route 53", "Route53"),
    ("EC2 Container Registry", "ECR"),
    ("Secrets Manager", "SecretsManager"),
    ("CloudFront", "CloudFront"),
    ("Virtual Private Cloud", "VPC"),
    ("Config", "Config"),
]


class CollectError(Exception):
    """A condition under which reporting success would be a lie."""


def iac_category(resource_type: str) -> str:
    for prefix, cat in IAC_CATEGORY_PREFIXES:
        if resource_type.startswith(prefix):
            return cat
    return "Other"


def ce_category(service: str) -> str:
    for needle, cat in CE_CATEGORY_SUBSTRINGS:
        if needle in service:
            return cat
    return "Other"


def _f(v) -> float:
    try:
        return float(v) if v is not None else 0.0
    except (TypeError, ValueError):
        return 0.0


def load_rules(path: Path) -> dict:
    rules = json.loads(path.read_text())
    for key in ("version", "environments", "required_tags", "rules"):
        if key not in rules:
            raise CollectError(f"rules file {path} lacks `{key}`")
    if not rules["environments"]:
        raise CollectError("rules file declares no environments")
    return rules


def _components(res: dict) -> list[tuple[str, float]]:
    """Every cost component of a resource, subresources included (an aws_instance's root
    volume is a SUBresource — the gp2 rule would never see it otherwise)."""
    out = [(c.get("name", ""), _f(c.get("monthlyCost"))) for c in res.get("costComponents") or []]
    for sub in res.get("subresources") or []:
        out.extend(_components(sub))
    return out


def flatten(breakdown: dict) -> list[dict]:
    """Top-level resources across every project of one infracost JSON document."""
    rows = []
    for project in breakdown.get("projects") or []:
        pname = project.get("name") or (project.get("metadata") or {}).get("path") or "unknown"
        for res in (project.get("breakdown") or {}).get("resources") or []:
            rows.append(
                {
                    "project": pname,
                    "resource": res.get("name", ""),
                    "resource_type": res.get("resourceType", ""),
                    "monthly_cost": _f(res.get("monthlyCost")),
                    # None = infracost did not report tags for this resource (not taggable, or
                    # a version that does not emit them) -> the tag rule does not apply. {} =
                    # taggable and untagged -> it does.
                    "tags": res.get("tags") if isinstance(res.get("tags"), dict) else None,
                    "components": _components(res),
                }
            )
    return rows


def _instance_family(component_name: str) -> str | None:
    m = INSTANCE_RE.search(component_name)
    return m.group(1) if m else None


def evaluate(resources: list[dict], rules: dict) -> list[dict]:
    """Findings for one env's resources. Pure function — the unit-tested core."""
    r = rules["rules"]
    findings: list[dict] = []

    def add(res, rule_id, message, saving):
        findings.append(
            {
                "project": res["project"],
                "resource": res["resource"],
                "resource_type": res["resource_type"],
                "rule_id": rule_id,
                "severity": r[rule_id]["severity"],
                "message": message,
                "est_monthly_saving": round(max(saving, 0.0), 4),
            }
        )

    gp2 = r.get("ebs-gp2-to-gp3")
    prev = r.get("previous-generation-instance")
    grav = r.get("non-graviton-instance")
    tags_rule = r.get("missing-required-tags")
    for res in resources:
        if gp2:
            pat = re.compile(gp2["component_pattern"])
            hits = [(n, c) for n, c in res["components"] if pat.search(n)]
            if hits:
                add(res, "ebs-gp2-to-gp3", f"gp2 storage: {hits[0][0]}",
                    sum(c for _, c in hits) * gp2["est_saving_ratio"])
        fam_cost: dict[str, float] = {}
        for n, c in res["components"]:
            fam = _instance_family(n)
            if fam:
                fam_cost[fam] = fam_cost.get(fam, 0.0) + c
        for fam, cost in sorted(fam_cost.items()):
            if prev and fam in prev["families"]:
                add(res, "previous-generation-instance", f"previous-generation family {fam}",
                    cost * prev["est_saving_ratio"])
            if grav and fam in grav["families"]:
                add(res, "non-graviton-instance", f"x86 family {fam}; Graviton alternative {grav['families'][fam]}",
                    cost * grav["est_saving_ratio"])
        if tags_rule and res["tags"] is not None:
            missing = [t for t in rules["required_tags"] if not str(res["tags"].get(t, "")).strip()]
            if missing:
                add(res, "missing-required-tags", "missing tags: " + ", ".join(missing), 0.0)

    nat = r.get("nat-gateway-per-az")
    if nat:
        by_project: dict[str, list[dict]] = {}
        for res in resources:
            if res["resource_type"] == nat["resource_type"]:
                by_project.setdefault(res["project"], []).append(res)
        for gws in by_project.values():
            if len(gws) > nat["max_per_project"]:
                ordered = sorted(gws, key=lambda x: x["resource"])
                for extra in ordered[nat["max_per_project"]:]:
                    add(extra, "nat-gateway-per-az",
                        f"{len(gws)} NAT gateways in one project (max {nat['max_per_project']})",
                        extra["monthly_cost"])
    return findings


def compliance(resources: list[dict], findings: list[dict]) -> float:
    """Share of resources with no finding. 1.0 over zero resources is refused by the caller."""
    bad = {(f["project"], f["resource"]) for f in findings}
    total = {(x["project"], x["resource"]) for x in resources}
    return 1.0 - len(bad & total) / len(total) if total else 0.0


def load_env_breakdowns(infracost_dir: Path, envs: list[str]) -> dict[str, list[dict]]:
    out = {}
    for env in envs:
        path = infracost_dir / f"{env}.json"
        if not path.is_file():
            raise CollectError(f"no infracost output for env {env} at {path}")
        resources = flatten(json.loads(path.read_text()))
        if not resources:
            # An empty breakdown is what a wrong --path, an HCL parse failure or a pricing-API
            # outage all look like. Reporting it as $0 would be the successful-no-op defect.
            raise CollectError(f"infracost reported ZERO resources for env {env} — refusing to record a $0 estimate")
        out[env] = resources
    return out


def parse_cost_explorer(ce: dict) -> list[dict]:
    rows = []
    for period in ce.get("ResultsByTime") or []:
        day = (period.get("TimePeriod") or {}).get("Start")
        for g in period.get("Groups") or []:
            metric = (g.get("Metrics") or {}).get("UnblendedCost") or {}
            rows.append(
                {
                    "usage_date": day,
                    "service": g["Keys"][0],
                    "category": ce_category(g["Keys"][0]),
                    "amount": _f(metric.get("Amount")),
                    "currency": metric.get("Unit", "USD"),
                }
            )
    if not rows:
        raise CollectError("Cost Explorer returned no rows — refusing to record zero spend")
    return rows


def ch_time(t: dt.datetime) -> str:
    # DateTime64 over JSONEachRow wants a space and no zone suffix (ADR-0255 lesson 2).
    return t.strftime("%Y-%m-%d %H:%M:%S.") + f"{t.microsecond // 1000:03d}"


def build_rows(env_resources: dict[str, list[dict]], rules: dict, run_id: str, run_ts: str):
    res_rows, finding_rows = [], []
    for env, resources in env_resources.items():
        for x in resources:
            res_rows.append(
                {
                    "run_ts": run_ts, "run_id": run_id, "env": env, "project": x["project"],
                    "resource": x["resource"], "resource_type": x["resource_type"],
                    "category": iac_category(x["resource_type"]),
                    "tags": {k: str(v) for k, v in (x["tags"] or {}).items()},
                    "monthly_cost": x["monthly_cost"],
                }
            )
        for f in evaluate(resources, rules):
            finding_rows.append({"run_ts": run_ts, "run_id": run_id, "env": env,
                                 "rules_version": rules["version"], **f})
    return res_rows, finding_rows


class ClickHouse:
    def __init__(self, url: str, user: str, password: str):
        self.url = url.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()

    def _post(self, query: str, body: bytes = b"") -> str:
        req = urllib.request.Request(
            f"{self.url}/?{urllib.parse.urlencode({'query': query})}",
            data=body, method="POST", headers={"Authorization": self.auth},
        )
        with urllib.request.urlopen(req, timeout=60) as resp:  # raises on HTTP >= 400
            return resp.read().decode()

    def insert(self, table: str, rows: list[dict]) -> None:
        body = "\n".join(json.dumps(r) for r in rows).encode()
        self._post(f"INSERT INTO openbank_analytics.{table} FORMAT JSONEachRow", body)

    def count(self, table: str, where: str) -> int:
        return int(self._post(f"SELECT count() FROM openbank_analytics.{table} WHERE {where}").strip())


def ingest(args) -> int:
    rules = load_rules(Path(args.rules))
    env_resources = load_env_breakdowns(Path(args.infracost_dir), rules["environments"])
    ce_rows = parse_cost_explorer(json.loads(Path(args.ce).read_text()))

    now = dt.datetime.now(dt.timezone.utc)
    run_id, run_ts = str(uuid.uuid4()), ch_time(now)
    res_rows, finding_rows = build_rows(env_resources, rules, run_id, run_ts)
    collected_at = run_ts
    for row in ce_rows:
        row["collected_at"] = collected_at

    ch = ClickHouse(os.environ["CLICKHOUSE_URL"], os.environ["CLICKHOUSE_USER"], os.environ["CLICKHOUSE_PASSWORD"])
    rid = f"run_id = '{run_id}'"
    ch.insert("finops_iac_resources", res_rows)
    if ch.count("finops_iac_resources", rid) != len(res_rows):
        raise CollectError("finops_iac_resources read-back count differs from rows sent")
    if finding_rows:
        ch.insert("finops_findings", finding_rows)
    if ch.count("finops_findings", rid) != len(finding_rows):
        raise CollectError("finops_findings read-back count differs from rows sent")
    ch.insert("finops_aws_daily_cost", ce_rows)
    got = ch.count("finops_aws_daily_cost", f"collected_at = toDateTime64('{collected_at}', 3, 'UTC')")
    if got != len(ce_rows):
        raise CollectError(f"finops_aws_daily_cost read-back {got} != {len(ce_rows)} sent")

    # Key by env too: the same resource address can exist in two envs.
    all_resources = [{"project": f"{r['env']}/{r['project']}", "resource": r["resource"]} for r in res_rows]
    keyed = [{"project": f"{f['env']}/{f['project']}", "resource": f["resource"]} for f in finding_rows]
    summary = {
        "run_ts": run_ts, "run_id": run_id, "rules_version": rules["version"],
        "envs": len(env_resources), "resources": len(res_rows), "findings": len(finding_rows),
        "compliance": round(compliance(all_resources, keyed), 6),
        "iac_monthly_cost": round(sum(r["monthly_cost"] for r in res_rows), 4),
        "potential_monthly_saving": round(sum(f["est_monthly_saving"] for f in finding_rows), 4),
        "ce_rows": len(ce_rows),
    }
    ch.insert("finops_ingest_runs", [summary])
    if ch.count("finops_ingest_runs", rid) != 1:
        raise CollectError("finops_ingest_runs read-back missing")
    print(json.dumps(summary))
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    le = sub.add_parser("list-envs")
    le.add_argument("--rules", required=True)
    ev = sub.add_parser("evaluate")
    ev.add_argument("--rules", required=True)
    ev.add_argument("--infracost-dir", required=True)
    ig = sub.add_parser("ingest")
    ig.add_argument("--rules", required=True)
    ig.add_argument("--infracost-dir", required=True)
    ig.add_argument("--ce", required=True)
    args = ap.parse_args(argv)
    try:
        if args.cmd == "list-envs":
            print("\n".join(load_rules(Path(args.rules))["environments"]))
            return 0
        if args.cmd == "evaluate":
            rules = load_rules(Path(args.rules))
            envs = load_env_breakdowns(Path(args.infracost_dir), rules["environments"])
            print(json.dumps({e: evaluate(r, rules) for e, r in envs.items()}, indent=2))
            return 0
        return ingest(args)
    except CollectError as e:
        print(f"::error::cloud-finops: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
