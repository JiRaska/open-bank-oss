#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Assert the fleet PodMonitor scrapes every service's namespace.

openbank-infra/gitops/components/observability/podmonitor-openbank-services.yaml selects pods
by `app.kubernetes.io/name: Exists` but restricts them with an explicit
`namespaceSelector.matchNames` list. A service whose namespace is absent from that list is
simply never scraped: no metric reaches Prometheus, every PrometheusRule written against those
metrics can never fire, and nothing anywhere goes red — the workload is healthy, the alert rule
is valid, and the series does not exist.

That has now happened twice. The first drift (audit, dispute, interest, notifications,
customer-edge, security-scanner, platform) is recorded in the PodMonitor's header comment; the
second (anacredit, billing, documents, finrep, sdd, tpp-registry) was found by issue #2255. The
second one also shows why a comment is not a control: that comment asserted sdd-service was
covered "via `payments`", while sdd-service's Deployment is in namespace `sdd` and
payments-services.yaml does not mention sdd at all. The prod-readiness collector's C8 scorer
read the file as a plain substring, so the false claim scored sdd as scraped.

So coverage is derived from the workload manifests, never from prose:

    for each openbank-<short>-service in the repo
      -> find its Deployment/Rollout in openbank-infra/gitops
      -> take metadata.namespace, else the nearest kustomization.yaml's `namespace:`
      -> that namespace MUST appear in matchNames

Usage:
    check-podmonitor-namespace-coverage.py            # gate (exit 1 on a gap)
    check-podmonitor-namespace-coverage.py --self-test  # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys

import gatelib
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PODMON_REL = Path(
    "openbank-infra/gitops/components/observability/podmonitor-openbank-services.yaml"
)

# The namespace resolution below is shared with the prod-readiness collector's C8 scorer and the
# per-service runbook generator. All three answer "where does this workload actually run", and
# two of them had already answered it WRONG in two different ways before it was centralised
# (#2255) — so it lives in exactly one place now, tested by
# openbank-infra/scripts/prod_readiness_collector_test.py and the fixtures in --self-test below.
sys.path.insert(0, str(REPO / "openbank-infra" / "scripts"))
from gitops_facts import (  # noqa: E402
    management_port_workloads,
    management_scraped_namespaces,
    module_dir,
    money_path_services,
    podmonitor_namespaces,
    workload_namespaces,
)

# Namespaces that declare a `management` port but are deliberately not scraped. An entry needs a
# reason, and the gate fails on a stale declaration in EITHER direction — an entry for a namespace
# that is now scraped, or one whose workload is gone. That asymmetry is the point: a new gap is red
# by default and only a human writing down why can make it green, which is the opposite of the
# hand-kept matchNames list this check exists to police.
NOT_SCRAPED: dict[str, str] = {}


def audit(repo: Path) -> tuple[dict[str, list[str]], list[str], int]:
    """-> ({service: [missing namespaces]}, [services with no workload], ok_count)"""
    gitops = repo / "openbank-infra" / "gitops"
    selected = podmonitor_namespaces(gitops)
    missing: dict[str, list[str]] = {}
    no_workload: list[str] = []
    ok = 0
    # The `-service` glob alone skipped openbank-sepa-payment, openbank-sepa-instant and
    # openbank-domestic-payment, so the three modules that move SEPA and domestic payments were
    # never checked for scrape coverage at all — this gate reported a complete fleet while three
    # money-path workloads were outside its population (#2364).
    shorts = set()
    for d in repo.glob("openbank-*-service"):
        m = re.match(r"openbank-(.+)-service", d.name)
        if m:
            shorts.add(m.group(1))
    try:
        for s in money_path_services(repo):
            if module_dir(s, repo).is_dir():
                shorts.add(s)
    except RuntimeError:
        pass  # self-test fixtures carry no rules.yaml; the real repo always does
    for short in sorted(shorts):
        namespaces = workload_namespaces(short, gitops)
        if not namespaces:
            no_workload.append(short)
            continue
        gap = sorted(n for n in namespaces if n not in selected)
        if gap:
            missing[short] = gap
        else:
            ok += 1
    return missing, no_workload, ok


def audit_fleet(
    gitops: Path, not_scraped: dict[str, str] | None = None
) -> tuple[dict[str, set[str]], list[str], int]:
    """-> ({namespace: workloads} unscraped, [stale NOT_SCRAPED keys], covered_count)

    The population is every workload declaring a `management` container port, NOT the
    `openbank-*-service` module list [audit] iterates. Those are different questions and they had
    different answers: the module list covered 32 namespaces while 36 carried a `management`-port
    workload, and the four-namespace difference was the entire control-plane agent fleet.
    """
    declared = NOT_SCRAPED if not_scraped is None else not_scraped
    workloads = management_port_workloads(gitops)
    scraped = management_scraped_namespaces(gitops)
    everywhere = "*" in scraped
    unscraped = {
        ns: names
        for ns, names in workloads.items()
        if not everywhere and ns not in scraped and ns not in declared
    }
    stale = [ns for ns in declared if ns not in workloads or everywhere or ns in scraped]
    covered = sum(1 for ns in workloads if everywhere or ns in scraped)
    return unscraped, sorted(stale), covered


def run_gate(repo: Path, quiet: bool = False) -> int:
    missing, no_workload, ok = audit(repo)
    say = (lambda *a: None) if quiet else print
    say(f"PodMonitor namespace coverage: {ok} scraped, {len(missing)} missing, {len(no_workload)} not deployed")
    for short in no_workload:
        say(f"  note: {short} has no Deployment/Rollout in gitops (not deployed yet)")
    for short, gaps in sorted(missing.items()):
        for ns in gaps:
            say(
                f"::error file={PODMON_REL}::{short} runs in namespace "
                f"'{ns}', which is absent from namespaceSelector.matchNames — its metrics "
                f"never reach Prometheus and any PrometheusRule over them cannot fire. "
                f"Add '{ns}' to {PODMON_REL}."
            )

    gitops = repo / "openbank-infra" / "gitops"
    unscraped, stale, covered = audit_fleet(gitops)
    gatelib.subjects(covered + len(unscraped), "namespaces running workloads")
    say(
        f"management-port scrape coverage: {covered} namespaces scraped, "
        f"{len(unscraped)} unscraped, {len(NOT_SCRAPED)} declared not-scraped"
    )
    for ns, names in sorted(unscraped.items()):
        say(
            f"::error file={PODMON_REL}::namespace '{ns}' runs "
            f"{', '.join(sorted(names))}, which declare a `management` container port, but no "
            f"PodMonitor/ServiceMonitor scrapes that port there — /q/metrics never reaches "
            f"Prometheus. Add '{ns}' to {PODMON_REL}, or declare it in NOT_SCRAPED in "
            f"{Path(__file__).name} with a reason."
        )
    for ns in stale:
        say(
            f"::error file={Path(__file__).name}::NOT_SCRAPED declares '{ns}', but it is now "
            f"scraped or has no `management`-port workload — remove the stale entry."
        )
    return 1 if (missing or unscraped or stale) else 0


# ---------------------------------------------------------------------------
# self-test: a gate that has only ever passed is unfalsified. Feed it a repo it MUST
# flag and a repo it MUST pass, and check what it PRINTS, not only its exit code.
# ---------------------------------------------------------------------------
SELF_TEST_PODMON = """apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: openbank-services
  namespace: observability
spec:
  namespaceSelector:
    matchNames:
      - ledger
      - payments
  podMetricsEndpoints:
    - port: management
"""

SELF_TEST_WORKLOAD = """apiVersion: apps/v1
kind: Deployment
metadata:
  name: {short}-service
  namespace: {ns}
spec:
  template:
    spec:
      containers:
        - name: {short}-service
          image: openbank-{short}-service:1.0.0
          ports:
            - name: http
              containerPort: 8080
            - name: management
              containerPort: 9000
"""

# The same workload WITHOUT a management port. The scrape contract is the port, not the pod: a
# workload that never exposes /q/metrics is not a coverage gap, and a fleet check that flagged it
# would produce permanent noise for admin-ui, external-dns, reposilite and the registry caches.
SELF_TEST_WORKLOAD_NO_MGMT = """apiVersion: apps/v1
kind: Deployment
metadata:
  name: {short}-service
  namespace: {ns}
spec:
  template:
    spec:
      containers:
        - name: {short}-service
          image: openbank-{short}-service:1.0.0
          ports:
            - name: http
              containerPort: 8080
"""

# A NetworkPolicy that only *mentions* the service. If the resolver ever counted a mere
# mention, this peer would resolve zoned-service to namespace 'wrong-ns' and the fixture
# below would stop failing for the right reason.
SELF_TEST_DECOY = """apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-to-zoned
  namespace: wrong-ns
spec:
  egress:
    - to:
        - podSelector:
            matchLabels:
              app.kubernetes.io/name: openbank-zoned-service
"""


def build_fixture(
    root: Path,
    workloads: list[tuple[str, str]],
    kustomize_ns: str | None = None,
    no_mgmt_port: set[str] | None = None,
):
    comp = root / "openbank-infra" / "gitops" / "components" / "fixture"
    comp.mkdir(parents=True)
    (root / PODMON_REL).parent.mkdir(parents=True, exist_ok=True)
    (root / PODMON_REL).write_text(SELF_TEST_PODMON)
    for short, ns in workloads:
        (root / f"openbank-{short}-service").mkdir(exist_ok=True)
        template = (
            SELF_TEST_WORKLOAD_NO_MGMT
            if short in (no_mgmt_port or set())
            else SELF_TEST_WORKLOAD
        )
        body = template.format(short=short, ns=ns) if ns else (
            template.format(short=short, ns="PLACEHOLDER").replace(
                "  namespace: PLACEHOLDER\n", ""
            )
        )
        (comp / f"{short}-service.yaml").write_text(body)
    (comp / "decoy-netpol.yaml").write_text(SELF_TEST_DECOY)
    if kustomize_ns:
        (comp / "kustomization.yaml").write_text(f"namespace: {kustomize_ns}\nresources: []\n")


def self_test() -> int:
    failures = []

    def case(name: str, workloads, kustomize_ns, want_rc: int, want_in_output: str = ""):
        tmp = Path(tempfile.mkdtemp())
        try:
            build_fixture(tmp, workloads, kustomize_ns)
            missing, no_workload, ok = audit(tmp)
            rc = 1 if missing else 0
            rendered = "; ".join(f"{s}->{','.join(g)}" for s, g in sorted(missing.items()))
            bad = []
            if rc != want_rc:
                bad.append(f"exit {rc}, wanted {want_rc}")
            if want_in_output and want_in_output not in rendered:
                bad.append(f"output {rendered!r} lacks {want_in_output!r}")
            print(f"  {'ok  ' if not bad else 'FAIL'} {name}" + (f" — {'; '.join(bad)}" if bad else ""))
            if bad:
                failures.append(name)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    print("self-test: cases the gate MUST flag")
    case("namespace absent from matchNames", [("zoned", "zoned")], None, 1, "zoned->zoned")
    case(
        "namespace inherited from kustomization and absent",
        [("zoned", "")],
        "inherited-ns",
        1,
        "zoned->inherited-ns",
    )
    case(
        "one covered service does not mask an uncovered one",
        [("ledgerish", "ledger"), ("zoned", "zoned")],
        None,
        1,
        "zoned->zoned",
    )
    print("self-test: cases the gate MUST pass")
    case("namespace present in matchNames", [("ledgerish", "ledger")], None, 0)
    case(
        "namespace inherited from kustomization and present",
        [("ledgerish", "")],
        "payments",
        0,
    )

    # The fleet pass. Its population is the `management` port, so these cases must not be
    # expressible through the module-name path above — a fixture module that is NOT named
    # openbank-<short>-service is invisible to audit() and visible to audit_fleet(), which is
    # exactly the eight-agent blind spot that made the original gate print `0 missing`.
    def fleet_case(
        name: str,
        workloads,
        want_rc: int,
        want_in_output: str = "",
        not_scraped: dict[str, str] | None = None,
        no_mgmt_port: set[str] | None = None,
    ):
        tmp = Path(tempfile.mkdtemp())
        try:
            build_fixture(tmp, workloads, None, no_mgmt_port)
            unscraped, stale, _covered = audit_fleet(
                tmp / "openbank-infra" / "gitops", not_scraped or {}
            )
            rc = 1 if (unscraped or stale) else 0
            rendered = "; ".join(
                f"{ns}->{','.join(sorted(w))}" for ns, w in sorted(unscraped.items())
            ) + ("" if not stale else f" stale:{','.join(stale)}")
            bad = []
            if rc != want_rc:
                bad.append(f"exit {rc}, wanted {want_rc}")
            if want_in_output and want_in_output not in rendered:
                bad.append(f"output {rendered!r} lacks {want_in_output!r}")
            print(f"  {'ok  ' if not bad else 'FAIL'} {name}" + (f" — {'; '.join(bad)}" if bad else ""))
            if bad:
                failures.append(name)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    print("self-test: fleet pass — cases the gate MUST flag")
    fleet_case(
        "management-port workload in an unscraped namespace",
        [("agentish", "devops-agent")],
        1,
        "devops-agent->agentish-service",
    )
    fleet_case(
        "a covered namespace does not mask an uncovered one",
        [("ledgerish", "ledger"), ("agentish", "devops-agent")],
        1,
        "devops-agent->agentish-service",
    )
    fleet_case(
        "NOT_SCRAPED entry for a namespace that IS scraped is stale",
        [("ledgerish", "ledger")],
        1,
        "stale:ledger",
        not_scraped={"ledger": "reason"},
    )
    fleet_case(
        "NOT_SCRAPED entry for a namespace with no management-port workload is stale",
        [("ledgerish", "ledger")],
        1,
        "stale:ghost",
        not_scraped={"ghost": "reason"},
    )
    print("self-test: fleet pass — cases the gate MUST pass")
    fleet_case("management-port workload in a scraped namespace", [("ledgerish", "ledger")], 0)
    fleet_case(
        "workload with NO management port in an unscraped namespace",
        [("portless", "developer-portal")],
        0,
        no_mgmt_port={"portless"},
    )
    fleet_case(
        "declared NOT_SCRAPED gap",
        [("agentish", "devops-agent")],
        0,
        not_scraped={"devops-agent": "reason"},
    )

    if failures:
        print(f"\n::error::self-test failed for: {', '.join(failures)}")
        return 1
    print("\nself-test passed: the gate flags a missing namespace and clears a covered one.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="prove the gate can fail")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run_gate(REPO)


if __name__ == "__main__":
    sys.exit(main())
