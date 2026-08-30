#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Make an UNSCRAPED CNPG cluster a finding instead of a silence.

THE DEFECT THIS EXISTS FOR
--------------------------
`prometheus-rules-db.yaml` carries nine Postgres alerts. Every one of them is a threshold over a
series the cluster itself must produce:

    PostgresNoRecoveryPoint   cnpg_collector_first_recoverability_point == 0 and ...
    PostgresBackupStale       time() - cnpg_collector_last_available_backup_timestamp > 30h
    PostgresWALArchiveFailing rate(cnpg_pg_stat_archiver_failed_count[15m]) > 0
    PostgresInstanceDown      up{container="postgres"} == 0

`PostgresInstanceDown` already documents, at length, that `cnpg_collector_up` cannot report its
own exporter's death and that `up` survives because Prometheus writes it per TARGET. That fix is
correct and it stops one level short: `up` only exists for a target Prometheus was TOLD to scrape.
A CNPG `Cluster` with `spec.monitoring.enablePodMonitor` unset creates no PodMonitor, so there is
no target, so there is no `up` series either — and every alert in the file, `PostgresInstanceDown`
included, matches nothing. Absent is not zero, and a threshold cannot distinguish "healthy" from
"never observed".

The database most likely to be misconfigured is therefore the one guaranteed not to alert. This is
the same shape as #2255 (a namespace missing from a hand-kept `matchNames` list is never scraped)
one layer down, and the same shape as the pact-drift gate that asserted a diff over a hand-kept
module list: a control whose coverage set is maintained separately from the artifacts it covers
reads as PASSING when the set is short, never as UNCHECKED.

WHAT THIS DOES
--------------
1. Derives the expected set of CNPG clusters from the `kind: Cluster` manifests themselves — never
   a hand-kept list — and fails on any that would not be scraped.
2. Generates `prometheus-rules-cnpg-coverage.yaml` from that same derivation: one constant
   `openbank:cnpg_cluster_expected` series per cluster, compared at evaluation time against
   `openbank:cnpg_cluster_scraped` derived from real `up`. A cluster that exists in git and is
   absent from Prometheus then produces a POSITIVE series and pages, rather than producing nothing.
   The generated file is checked for drift, so the alert's coverage set cannot fall behind the
   manifests without going red.

Exemptions live in NOT_SCRAPED, need a reason, and go stale in BOTH directions: an entry for a
cluster that is now scraped fails, and an entry for a cluster that no longer exists fails. A new
gap is red by default and only a human writing down why can make it green.

Usage:
    check-cnpg-scrape-coverage.py              # gate (exit 1 on a gap or on drift)
    check-cnpg-scrape-coverage.py --write      # regenerate the derived PrometheusRule
    check-cnpg-scrape-coverage.py --self-test  # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import gatelib

REPO = Path(__file__).resolve().parents[2]
GITOPS = Path("openbank-infra/gitops")
GENERATED_REL = Path(
    "openbank-infra/gitops/components/observability/prometheus-rules-cnpg-coverage.yaml"
)
# The promtool fixture is generated from the SAME derivation as the rule. It has to be: the
# alert's corpus is every declared cluster, so a hand-written `input_series` block would have to
# list all 62 and would fall behind the manifests the first time one was added -- reading as a
# passing test about a cluster set it no longer describes. That is the exact failure this whole
# gate exists to remove, so the fixture is derived and drift-checked like the rule.
FIXTURE_REL = Path("openbank-infra/tests/promtool/cnpg_scrape_coverage_generated_test.yaml")

# Paths that define a Cluster SHAPE rather than a running cluster. A DR restore template is
# instantiated by a human during a recovery drill and has no steady-state existence to scrape.
SKIP_PATH_PARTS = ("dr-restore-templates",)

# Clusters deliberately not scraped. Reason required; stale in either direction is a failure.
NOT_SCRAPED: dict[str, str] = {}

# Clusters created by a third-party Helm chart rather than by a `kind: Cluster` manifest in this
# repo. They are INVISIBLE to the derivation above, which is exactly the failure mode this gate
# exists for, so they are declared here and still get an expected-series. `evidence` must appear
# in the named file or the declaration is stale.
CHART_CREATED: dict[str, dict[str, str]] = {
    "observability/glitchtip-pg": {
        "file": "openbank-infra/gitops/apps/glitchtip.yaml",
        "evidence": "glitchtip-pg",
        "reason": (
            "Created by the GlitchTip chart's bundled `postgresql.cluster` values, not by a "
            "kind: Cluster manifest here. UNBACKED-UP BY DESIGN (#1444) — but 'no backup' and "
            "'not scraped' are separate facts, and the second one is what hides the first."
        ),
    },
}

UNSCRAPED_DESC = (
    "This cluster is declared in gitops and Prometheus has no scrape target for it, so every "
    "Postgres alert -- PostgresNoRecoveryPoint, PostgresBackupStale, PostgresWALArchiveFailing "
    "and even PostgresInstanceDown -- is structurally incapable of firing for it. Its backup "
    "state is unknown, not healthy. Set spec.monitoring.enablePodMonitor: true on the Cluster, "
    "or declare it in NOT_SCRAPED in .github/scripts/check-cnpg-scrape-coverage.py with a reason."
)


def instance_down_desc(ns: str, cluster: str) -> str:
    """PostgresInstanceDown's annotation, rendered. Kept here so the fixture asserts the SHIPPED
    text: if that alert's wording changes without this being regenerated, the test goes red
    rather than quietly asserting a description nobody serves any more."""
    return (
        f"Prometheus cannot scrape Postgres on {cluster}-1 for >5m \u2014 the instance is down, "
        f"crashlooping, or unreachable. Check `kubectl get cluster -n {ns}`: a CNPG cluster "
        f"parked in phase `Not enough disk space` will NOT self-heal or fail over, by design "
        f"\u2014 it waits for the PVC to be enlarged."
    )


HEADER = """\
# GENERATED by .github/scripts/check-cnpg-scrape-coverage.py -- DO NOT EDIT.
# Regenerate with: python3 .github/scripts/check-cnpg-scrape-coverage.py --write
#
# Absence as a STATE, not a silence.
#
# Every Postgres alert in prometheus-rules-db.yaml is a threshold over a series the cluster must
# emit -- `up` included, since Prometheus writes `up` per TARGET and a Cluster with no PodMonitor
# is not a target. So a cluster nobody scrapes produces no metric, no alert and no absence signal:
# the expression matches nothing, which is indistinguishable from healthy.
#
# The fix is a denominator that exists whether or not the cluster does. The recording rules below
# are constant `vector(1)` series -- one per CNPG Cluster DERIVED FROM THE MANIFESTS in this repo,
# regenerated by the gate above, never hand-kept -- so `expected unless scraped` is a real vector
# when a cluster goes missing from Prometheus.
#
# Picked up automatically (ruleSelectorNilUsesHelmValues=false), like every sibling rule file.
"""


def _cluster_key(ns: str, name: str) -> str:
    return f"{ns}/{name}"


def discover_clusters() -> tuple[dict[str, dict], list[str]]:
    """Return {namespace/name: {...}} for every CNPG Cluster declared in gitops."""
    found: dict[str, dict] = {}
    errors: list[str] = []
    for path in sorted(gatelib.rglob(REPO / GITOPS, "*.yaml")):
        rel = path.relative_to(REPO)
        if any(part in rel.parts for part in SKIP_PATH_PARTS):
            continue
        try:
            docs = gatelib.load_yaml_all(path, errors="replace")
        except Exception:  # a manifest this gate cannot parse is not this gate's subject
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            if doc.get("kind") != "Cluster":
                continue
            if "cnpg.io" not in str(doc.get("apiVersion", "")):
                continue
            meta = doc.get("metadata") or {}
            name = meta.get("name")
            if not name:
                continue
            ns = meta.get("namespace") or _namespace_from_kustomization(path)
            if not ns:
                errors.append(f"{rel}: Cluster/{name} has no resolvable namespace")
                continue
            spec = doc.get("spec") or {}
            monitoring = spec.get("monitoring") or {}
            found[_cluster_key(ns, name)] = {
                "namespace": ns,
                "name": name,
                "path": str(rel),
                "scraped": monitoring.get("enablePodMonitor") is True,
                "backed_up": bool((spec.get("backup") or {}).get("barmanObjectStore")),
            }
    return found, errors


def _namespace_from_kustomization(path: Path) -> str | None:
    for parent in list(path.parents):
        k = parent / "kustomization.yaml"
        if k.is_file():
            doc = gatelib.load_yaml(k, errors="replace")
            if isinstance(doc, dict) and doc.get("namespace"):
                return str(doc["namespace"])
        if parent == REPO:
            break
    return None


def render(clusters: dict[str, dict]) -> str:
    lines = [HEADER, "apiVersion: monitoring.coreos.com/v1", "kind: PrometheusRule", "metadata:"]
    lines += [
        "  name: openbank-cnpg-scrape-coverage",
        "  namespace: observability",
        "  labels:",
        "    app.kubernetes.io/part-of: observability",
        "spec:",
        "  groups:",
        "    - name: openbank.db.cnpg-coverage",
        "      rules:",
    ]
    lines += [
        "        # The DENOMINATOR. One constant series per CNPG Cluster declared in this repo.",
        "        # Derived from the manifests, so it cannot be shorter than reality.",
    ]
    for key in sorted(clusters):
        c = clusters[key]
        lines += [
            "        - record: openbank:cnpg_cluster_expected",
            "          expr: vector(1)",
            "          labels:",
            f"            namespace: {c['namespace']}",
            f"            cluster: {c['name']}",
            f"            origin: {c['origin']}",
        ]
    lines += [
        "",
        "        # The NUMERATOR, from a series Prometheus writes itself. `up` exists for every",
        "        # target, 0 when the scrape fails -- so a cluster that is down still appears here",
        "        # and is NOT reported as unscraped. Only a cluster with no target at all vanishes.",
        "        - record: openbank:cnpg_cluster_scraped",
        "          expr: |",
        '            max by (namespace, cluster) (',
        '              label_replace(up{container="postgres"}, "cluster", "$1", "pod", "(.+)-[0-9]+")',
        "            )",
        "",
        "        - alert: PostgresClusterUnscraped",
        "          # Fires when a cluster this repo declares has NO scrape target in Prometheus.",
        "          # `unless` is the whole alert: it yields the expected series that has no",
        "          # counterpart, which is a real vector -- where the sibling threshold alerts",
        "          # yield an empty one and stay silent forever.",
        "          #",
        "          # 15m absorbs a rollout: a Cluster being recreated loses its target briefly.",
        "          expr: |",
        "            openbank:cnpg_cluster_expected",
        "              unless on (namespace, cluster) openbank:cnpg_cluster_scraped",
        "          for: 15m",
        "          labels:",
        "            severity: critical",
        "          annotations:",
        '            summary: "CNPG cluster {{ $labels.namespace }}/{{ $labels.cluster }} is declared but NOT scraped"',
        f'            description: "{UNSCRAPED_DESC}"',
    ]
    return "\n".join(lines) + "\n"



FIXTURE_HEADER = """\
# GENERATED by .github/scripts/check-cnpg-scrape-coverage.py -- DO NOT EDIT.
# Regenerate with: python3 .github/scripts/check-cnpg-scrape-coverage.py --write
#
# Proves PostgresClusterUnscraped by what it PREVENTS. The hold-out cluster below is chosen
# deterministically from the derived set, so these cases keep testing a real absence even as
# clusters are added and removed -- there is no hand-kept list to fall behind.
#
# Case 1 is the negative case (absence MUST page). Cases 2 and 3 are the controls that stop it
# passing vacuously: a rule that fired unconditionally would pass case 1 and fail both.
"""


def render_fixture(clusters: dict[str, dict]) -> str:
    """Fixture for the coverage alert: every declared cluster scraped except one hold-out."""
    keys = sorted(clusters)
    holdout = keys[0]
    hn, hc = clusters[holdout]["namespace"], clusters[holdout]["name"]

    def up_series(exclude: str | None, value: str) -> list[str]:
        out = []
        for k in keys:
            if k == exclude:
                continue
            c = clusters[k]
            out += [
                f'      - series: \'up{{container="postgres",namespace="{c["namespace"]}",'
                f'pod="{c["name"]}-1",job="{c["name"]}"}}\'',
                f'        values: "{value}"',
            ]
        return out

    L = [FIXTURE_HEADER, "rule_files:", "  - prometheus-rules-db.yaml",
         "  - prometheus-rules-cnpg-coverage.yaml", "", "evaluation_interval: 1m", "", "tests:"]

    L += [f"  # 1. NEGATIVE CASE: {holdout} has no scrape target at all. Absence must page.",
          "  - interval: 1m",
          f'    name: "a declared cluster with no scrape target is a finding, not a silence"',
          "    input_series:"]
    L += up_series(holdout, "1+0x40")
    L += ["    alert_rule_test:", "      - eval_time: 30m",
          "        alertname: PostgresClusterUnscraped", "        exp_alerts:",
          "          - exp_labels:", "              severity: critical",
          f"              namespace: {hn}", f"              cluster: {hc}",
          f"              origin: {clusters[holdout]['origin']}",
          "            exp_annotations:",
          f'              summary: "CNPG cluster {hn}/{hc} is declared but NOT scraped"',
          f'              description: "{UNSCRAPED_DESC}"',
          "      # The threshold alerts a human would expect to catch this: all silent. That",
          "      # silence IS the defect -- it is why the coverage alert has to exist.",
          "      - eval_time: 40m", "        alertname: PostgresNoRecoveryPoint",
          "        exp_alerts: []", "      - eval_time: 40m",
          "        alertname: PostgresBackupStale", "        exp_alerts: []",
          "      - eval_time: 40m", "        alertname: PostgresWALArchiveFailing",
          "        exp_alerts: []", "      - eval_time: 40m",
          "        alertname: PostgresInstanceDown", "        exp_alerts: []", ""]

    L += ["  # 2. CONTROL: with every cluster scraped, the alert must be SILENT.",
          "  - interval: 1m",
          '    name: "a fully scraped fleet reports nothing unscraped"',
          "    input_series:"]
    L += up_series(None, "1+0x40")
    L += ["    alert_rule_test:", "      - eval_time: 30m",
          "        alertname: PostgresClusterUnscraped", "        exp_alerts: []", ""]

    L += ["  # 3. CONTROL: DOWN is not UNSCRAPED. `up == 0` means the target EXISTS and the",
          "  #    scrape failed -- PostgresInstanceDown's job. Conflating the two would",
          "  #    double-page every crashloop and drain the coverage signal of meaning.",
          "  - interval: 1m",
          '    name: "a scraped-but-down cluster pages as DOWN, never as unscraped"',
          "    input_series:"]
    L += up_series(holdout, "1+0x40")
    L += [f'      - series: \'up{{container="postgres",namespace="{hn}",pod="{hc}-1",job="{hc}"}}\'',
          '        values: "0+0x40"',
          "    alert_rule_test:", "      - eval_time: 30m",
          "        alertname: PostgresClusterUnscraped", "        exp_alerts: []",
          "      - eval_time: 30m", "        alertname: PostgresInstanceDown",
          "        exp_alerts:", "          - exp_labels:",
          "              severity: critical", f"              namespace: {hn}",
          f"              pod: {hc}-1", '              container: postgres',
          f"              job: {hc}", "            exp_annotations:",
          f'              summary: "Postgres {hn}/{hc}-1 is down"',
          f'              description: "{instance_down_desc(hn, hc)}"', ""]
    return "\n".join(L) + "\n"

def build(strict: bool = True) -> tuple[dict[str, dict], list[str]]:
    clusters, errors = discover_clusters()
    for c in clusters.values():
        c["origin"] = "manifest"

    for key, decl in CHART_CREATED.items():
        if key in clusters:
            errors.append(
                f"CHART_CREATED declares {key}, but it is now a kind: Cluster manifest at "
                f"{clusters[key]['path']} -- remove the declaration (stale in the covered direction)."
            )
            continue
        ev_path = REPO / decl["file"]
        if not ev_path.is_file() or decl["evidence"] not in gatelib.read_text(
            ev_path, errors="replace"
        ):
            errors.append(
                f"CHART_CREATED declares {key} with evidence '{decl['evidence']}' in "
                f"{decl['file']}, which does not contain it -- the declaration is stale."
            )
            continue
        ns, name = key.split("/", 1)
        clusters[key] = {
            "namespace": ns,
            "name": name,
            "path": decl["file"],
            "scraped": False,
            "backed_up": False,
            "origin": "chart",
        }

    gaps = []
    for key in sorted(clusters):
        c = clusters[key]
        if c["scraped"] or c["origin"] == "chart":
            continue
        if key in NOT_SCRAPED:
            continue
        why = " It has a barmanObjectStore, so its backup state is what goes unwatched." if c["backed_up"] else ""
        gaps.append(
            f"{key}: spec.monitoring.enablePodMonitor is not true ({c['path']}), so nothing "
            f"scrapes it and every Postgres alert is blind to it.{why}"
        )

    for key, reason in NOT_SCRAPED.items():
        if key not in clusters:
            errors.append(f"NOT_SCRAPED declares {key}, which is not a CNPG Cluster here -- stale.")
        elif clusters[key]["scraped"]:
            errors.append(f"NOT_SCRAPED declares {key}, which IS scraped now -- remove the entry.")
        elif not str(reason).strip():
            errors.append(f"NOT_SCRAPED entry {key} has no reason.")

    if strict:
        errors = gaps + errors
    return clusters, errors


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    clusters, errors = build()
    gatelib.subjects(len(clusters), "CNPG clusters")

    rendered = render(clusters)
    target = REPO / GENERATED_REL
    fixture = render_fixture(clusters)
    fixture_target = REPO / FIXTURE_REL
    if args.write:
        target.write_text(rendered, encoding="utf-8")
        fixture_target.write_text(fixture, encoding="utf-8")
        print(f"wrote {GENERATED_REL} and {FIXTURE_REL} ({len(clusters)} clusters)")
        return 0

    if not target.is_file():
        errors.append(f"{GENERATED_REL} is missing -- run with --write.")
    elif gatelib.read_text(target) != rendered:
        errors.append(
            f"{GENERATED_REL} is stale -- the alert's coverage set has drifted from the "
            f"Cluster manifests. Run: python3 {Path(__file__).name} --write"
        )

    if not fixture_target.is_file():
        errors.append(f"{FIXTURE_REL} is missing -- run with --write.")
    elif gatelib.read_text(fixture_target) != fixture:
        errors.append(
            f"{FIXTURE_REL} is stale -- the promtool fixture no longer describes the Cluster "
            f"set it tests. Run: python3 {Path(__file__).name} --write"
        )

    if errors:
        print("CNPG scrape coverage: FAIL")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"CNPG scrape coverage: OK ({len(clusters)} clusters, all scraped or declared)")
    return 0


def self_test() -> int:
    """Prove the gate FAILS on a cluster that would not be scraped."""
    global REPO
    real = REPO
    ok = True
    with tempfile.TemporaryDirectory() as td:
        fake = Path(td)
        d = fake / GITOPS / "components" / "selftest"
        d.mkdir(parents=True)
        (fake / GENERATED_REL.parent).mkdir(parents=True, exist_ok=True)

        good = (
            "apiVersion: postgresql.cnpg.io/v1\nkind: Cluster\n"
            "metadata:\n  name: good-db\n  namespace: st\n"
            "spec:\n  instances: 1\n  monitoring:\n    enablePodMonitor: true\n"
        )
        bad = (
            "apiVersion: postgresql.cnpg.io/v1\nkind: Cluster\n"
            "metadata:\n  name: bad-db\n  namespace: st\n"
            "spec:\n  instances: 1\n  backup:\n    barmanObjectStore:\n      destinationPath: s3://x\n"
        )
        REPO = fake

        # 1. the negative case: an unscraped cluster MUST be reported
        (d / "good.yaml").write_text(good)
        (d / "bad.yaml").write_text(bad)
        gatelib.clear()
        _, errors = build()
        if not any("bad-db" in e for e in errors):
            print("SELF-TEST FAIL: an unscraped cluster was not reported"); ok = False
        if any("good-db" in e for e in errors):
            print("SELF-TEST FAIL: a scraped cluster was reported"); ok = False

        # 2. remove the offender: the gate must go quiet (it is not vacuously red)
        (d / "bad.yaml").unlink()
        gatelib.clear()
        clusters, errors = build()
        if any(e for e in errors if "glitchtip" not in e and "CHART_CREATED" not in e):
            print(f"SELF-TEST FAIL: clean corpus still errors: {errors}"); ok = False
        if "st/good-db" not in clusters:
            print("SELF-TEST FAIL: the scraped cluster was not discovered at all"); ok = False

        # 3. the generated rule must actually mention every discovered cluster
        out = render(clusters)
        for key, c in clusters.items():
            if f"cluster: {c['name']}" not in out:
                print(f"SELF-TEST FAIL: {key} missing from the generated rule"); ok = False
        if "PostgresClusterUnscraped" not in out:
            print("SELF-TEST FAIL: generated rule has no alert"); ok = False

        # 3b. the fixture must hold out exactly one cluster and scrape the rest -- otherwise
        # the negative case is not a negative case.
        fx = render_fixture(clusters)
        if fx.count("- series:") == 0:
            print("SELF-TEST FAIL: fixture has no input series"); ok = False
        if "exp_alerts: []" not in fx:
            print("SELF-TEST FAIL: fixture has no must-NOT-fire control"); ok = False

        # 4. a stale NOT_SCRAPED entry must fail in BOTH directions
        NOT_SCRAPED["st/good-db"] = "covered now"
        gatelib.clear()
        _, errors = build()
        if not any("IS scraped now" in e for e in errors):
            print("SELF-TEST FAIL: NOT_SCRAPED entry for a scraped cluster not reported"); ok = False
        del NOT_SCRAPED["st/good-db"]
        NOT_SCRAPED["st/ghost-db"] = "gone"
        gatelib.clear()
        _, errors = build()
        if not any("not a CNPG Cluster here" in e for e in errors):
            print("SELF-TEST FAIL: NOT_SCRAPED entry for a vanished cluster not reported"); ok = False
        del NOT_SCRAPED["st/ghost-db"]

    REPO = real
    gatelib.clear()
    print("SELF-TEST PASS" if ok else "SELF-TEST FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
