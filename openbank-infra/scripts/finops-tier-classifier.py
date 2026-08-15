#!/usr/bin/env python3
"""FinOps workload-tier classifier — the MEASURED side (ADR-0057).

The companion to check-finops-tiers.py (which validates the *declared* side). This
reads live signals from Prometheus and emits, per deployed service, a **recommended**
tier plus the realised idle ratio, then compares it against the *declared* tier in
rules.yaml. Divergence (e.g. a service idle ~all the time with no money-path reason but
declared/run always-on) is surfaced as a finding — the "keep it honest" half of the
ADR-0057 govern-as-code loop (derive -> enforce -> show), exactly as ADR-0054 does for
version lifecycle.

Read-only: only HTTP GETs against the Prometheus query API; it changes no scaling and
mutates nothing. stdlib only (urllib), so it runs in CI / an in-cluster CronJob with no
extra deps.

Recommendation logic (v1, deliberately conservative):
  - money-path service (rules.yaml)               -> T0  (never recommend below T0)
  - already has a KEDA ScaledObject, minReplicas 0 -> already scale-to-zero (keep)
  - idle (avg CPU < --idle-cpu cores over window):
        event consumer (@Incoming in repo)        -> recommend T2 (event -> 0)
        otherwise (HTTP)                           -> recommend T1 (HTTP -> 0, needs native image)
  - not idle                                       -> T0 / min>0 (keep warm)

Exit code is advisory (0) by default — honours rules.yaml finops_tiers.enforced.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"
DEFAULT_PROM = "http://kube-prometheus-stack-prometheus.observability.svc:9090"
# app namespaces that hold openbank service deployments (sandbox naming)
APP_NS_RE = r"openbank.*|notifications|accounts|ledger|balances|products|audit|analytics"


def prom_query(base: str, expr: str) -> list[dict]:
    url = base.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    with urllib.request.urlopen(url, timeout=15) as resp:
        data = json.load(resp)
    if data.get("status") != "success":
        raise RuntimeError(f"prometheus query failed: {expr}")
    return data["data"]["result"]


def _val(results: list[dict], key: str) -> dict[str, float]:
    out: dict[str, float] = {}
    for r in results:
        name = r["metric"].get(key) or r["metric"].get("deployment") or r["metric"].get("pod", "?")
        try:
            out[name] = float(r["value"][1])
        except (KeyError, ValueError):
            pass
    return out


# ---- rules.yaml (declared side) — reuse the same lightweight parsing approach -------
def _read_rules() -> str:
    return RULES.read_text(encoding="utf-8")


def _top_level_block(text: str, key: str) -> list[str]:
    out, inside = [], False
    for line in text.splitlines():
        if not inside:
            if re.match(rf"^{re.escape(key)}:\s*$", line):
                inside = True
            continue
        if line and not line[0].isspace() and not line.lstrip().startswith("#"):
            break
        out.append(line)
    return out


def money_path(text: str) -> set[str]:
    return {
        m.group(1)
        for line in _top_level_block(text, "money_path_services")
        if (m := re.match(r"\s*-\s*([A-Za-z0-9_-]+)", line))
    }


def declared_tiers(text: str) -> dict[str, str]:
    out, in_decl = {}, False
    for line in _top_level_block(text, "finops_tiers"):
        if re.match(r"^  declared:\s*$", line):
            in_decl = True
            continue
        if re.match(r"^  [A-Za-z0-9_]+:", line) and not line.startswith("    "):
            in_decl = False
            continue
        if in_decl and (m := re.match(r"^    ([A-Za-z0-9_-]+):\s*([A-Za-z0-9]+)", line)):
            out[m.group(1)] = m.group(2)
    return out


def event_consumers() -> set[str]:
    """Services with a Kafka @Incoming consumer = T2-eligible (event-driven)."""
    out = set()
    for p in REPO.glob("openbank-*/src/main"):
        svc = p.parent.parent.name  # openbank-<svc>/src/main -> openbank-<svc>
        for kt in p.rglob("*.kt"):
            try:
                if "@Incoming" in kt.read_text(encoding="utf-8", errors="ignore"):
                    out.add(svc)
                    break
            except OSError:
                pass
    return out


def svc_dir(deployment: str) -> str:
    """Map a deployment name (e.g. notification-service) to its repo dir (openbank-...)."""
    return deployment if deployment.startswith("openbank-") else f"openbank-{deployment}"


def classify(prom: str, idle_cpu: float, window: str) -> tuple[list[dict], list[str]]:
    replicas = _val(prom_query(prom, f'kube_deployment_spec_replicas{{namespace=~"{APP_NS_RE}"}}'), "deployment")
    cpu = _val(
        prom_query(prom, f'sum by (deployment) (label_replace(rate(container_cpu_usage_seconds_total{{namespace=~"{APP_NS_RE}",container!=""}}[{window}]), "deployment", "$1", "pod", "(.*)-[a-z0-9]+-[a-z0-9]+"))'),
        "deployment",
    )
    scaled = set(_val(prom_query(prom, 'keda_scaler_active'), "scaledObject").keys()) if _try(prom, 'keda_scaler_active') else set()

    text = _read_rules()
    mp = money_path(text)
    declared = declared_tiers(text)
    consumers = event_consumers()

    rows, findings = [], []
    # infra / non-service deployments to ignore
    ignore = {"redis", "kube-prometheus-stack-grafana", "kube-prometheus-stack-operator"}

    for dep in sorted(replicas):
        if dep in ignore:
            continue
        sd = svc_dir(dep)
        c = cpu.get(dep, 0.0)
        idle = c < idle_cpu
        is_consumer = sd in consumers
        has_s2z = (dep in scaled) or (sd in scaled) or replicas.get(dep) == 0

        if sd in mp:
            rec, why = "T0", "money-path (always-on by policy)"
        elif has_s2z:
            rec, why = ("T2" if is_consumer else "T1"), f"already scale-to-zero; idle-cpu={c:.3f}"
        elif idle and is_consumer:
            rec, why = "T2", f"event consumer, idle (cpu={c:.3f} < {idle_cpu})"
        elif idle:
            rec, why = "T1", f"HTTP, idle (cpu={c:.3f} < {idle_cpu}) — needs native image"
        else:
            rec, why = "T0", f"active (cpu={c:.3f} >= {idle_cpu}) — keep warm"

        decl = declared.get(sd) or ("T0" if sd in mp else "(undeclared)")
        drift = decl not in ("(undeclared)",) and decl != rec
        rows.append({"service": sd, "declared": decl, "recommended": rec, "cpu": c, "why": why, "drift": drift})
        if drift:
            findings.append(f"{sd}: declared {decl} but measured behaviour recommends {rec} ({why}).")

    return rows, findings


def _try(prom: str, expr: str) -> bool:
    try:
        prom_query(prom, expr)
        return True
    except Exception:
        return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--prometheus-url", default=DEFAULT_PROM)
    ap.add_argument("--idle-cpu", type=float, default=0.05, help="avg cores below which a service is 'idle'")
    ap.add_argument("--window", default="30m")
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()

    try:
        rows, findings = classify(args.prometheus_url, args.idle_cpu, args.window)
    except Exception as e:  # network/Prometheus unreachable -> degrade, never crash CI
        print("FINOPS_TIER_CLASSIFIER_FINDING=0")
        print(f"classifier could not reach Prometheus ({args.prometheus_url}): {e}")
        print("(advisory — skipped, not a failure)")
        return 0

    header = f"{'service':32} {'declared':10} {'recommended':12} {'cpu(cores)':11} reason"
    if args.report:
        print(f"FINOPS_TIER_CLASSIFIER_FINDING={1 if findings else 0}")
        print("## FinOps workload-tier classifier — measured vs declared (ADR-0057)\n")
    print(header)
    print("-" * len(header))
    for r in rows:
        flag = "  ⚠️ DRIFT" if r["drift"] else ""
        print(f"{r['service']:32} {r['declared']:10} {r['recommended']:12} {r['cpu']:<11.3f} {r['why']}{flag}")
    if findings:
        print("\nFindings (advisory — declared tier diverges from measured behaviour):")
        for f in findings:
            print(f"  ⚠️ {f}")
    else:
        print("\nNo declared-vs-measured drift.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
