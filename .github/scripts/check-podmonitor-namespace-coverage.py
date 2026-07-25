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
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PODMON_REL = Path(
    "openbank-infra/gitops/components/observability/podmonitor-openbank-services.yaml"
)


def read(p: Path) -> str:
    try:
        return p.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def nearest_kustomize_namespace(manifest: Path, gitops: Path) -> str | None:
    """The `namespace:` of the closest kustomization.yaml at or above the manifest."""
    for parent in manifest.parents:
        if parent != gitops and gitops not in parent.parents:
            break
        m = re.search(r"^namespace:\s*(\S+)", read(parent / "kustomization.yaml"), re.M)
        if m:
            return m.group(1)
    return None


def workload_namespaces(short: str, gitops: Path) -> set[str]:
    """Namespaces of every Deployment/Rollout that IS this service.

    A manifest merely *mentioning* the service does not count — a NetworkPolicy peer, an env
    var pointing at its URL or an initContainer waiting on it would otherwise resolve to the
    wrong namespace. The workload's metadata.name must be the service itself.
    """
    names = {f"{short}-service", f"openbank-{short}-service"}
    out: set[str] = set()
    for f in gitops.rglob("*.yaml"):
        text = read(f)
        if f"openbank-{short}-service" not in text:
            continue
        for doc in text.split("\n---"):
            kind = re.search(r"^kind:\s*(\S+)", doc, re.M)
            if not kind or kind.group(1) not in ("Deployment", "Rollout"):
                continue
            name = re.search(r"^\s{2}name:\s*(\S+)", doc, re.M)
            if not name or name.group(1) not in names:
                continue
            ns = re.search(r"^\s{2}namespace:\s*(\S+)", doc, re.M)
            out.add(
                ns.group(1) if ns else (nearest_kustomize_namespace(f, gitops) or "?UNRESOLVED")
            )
    return out


def match_names(podmon: Path) -> set[str]:
    text = read(podmon)
    if "matchNames:" not in text:
        return set()
    out: set[str] = set()
    for line in text.split("matchNames:", 1)[1].splitlines():
        m = re.match(r"^(\s+)-\s+(\S+)\s*$", line)
        if m:
            out.add(m.group(2))
        elif line.strip():
            break  # dedented back out of the list
    return out


def audit(repo: Path) -> tuple[dict[str, list[str]], list[str], int]:
    """-> ({service: [missing namespaces]}, [services with no workload], ok_count)"""
    gitops = repo / "openbank-infra" / "gitops"
    selected = match_names(repo / PODMON_REL)
    missing: dict[str, list[str]] = {}
    no_workload: list[str] = []
    ok = 0
    for d in sorted(repo.glob("openbank-*-service")):
        m = re.match(r"openbank-(.+)-service", d.name)
        if not m:
            continue
        short = m.group(1)
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


def run_gate(repo: Path, quiet: bool = False) -> int:
    missing, no_workload, ok = audit(repo)
    say = (lambda *a: None) if quiet else print
    say(f"PodMonitor namespace coverage: {ok} scraped, {len(missing)} missing, {len(no_workload)} not deployed")
    for short in no_workload:
        say(f"  note: openbank-{short}-service has no Deployment/Rollout in gitops (not deployed yet)")
    if not missing:
        return 0
    for short, gaps in sorted(missing.items()):
        for ns in gaps:
            say(
                f"::error file={PODMON_REL}::openbank-{short}-service runs in namespace "
                f"'{ns}', which is absent from namespaceSelector.matchNames — its metrics "
                f"never reach Prometheus and any PrometheusRule over them cannot fire. "
                f"Add '{ns}' to {PODMON_REL}."
            )
    return 1


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


def build_fixture(root: Path, workloads: list[tuple[str, str]], kustomize_ns: str | None = None):
    comp = root / "openbank-infra" / "gitops" / "components" / "fixture"
    comp.mkdir(parents=True)
    (root / PODMON_REL).parent.mkdir(parents=True, exist_ok=True)
    (root / PODMON_REL).write_text(SELF_TEST_PODMON)
    for short, ns in workloads:
        (root / f"openbank-{short}-service").mkdir(exist_ok=True)
        body = SELF_TEST_WORKLOAD.format(short=short, ns=ns) if ns else (
            SELF_TEST_WORKLOAD.format(short=short, ns="PLACEHOLDER").replace(
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
