#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Production Readiness collector (vrstva 1 — derived signals).

Per-service maturity scoring across 9 technicko-provozních dimenzí (C1–C9).
Derives what it can from the repo; reads manual attestations (M-dimenze) from
openbank-libs/governance/attestations.yaml with TTL-based decay so a stale
attestation degrades instead of staying green forever.

Score scale per cell: 0 Absent · 1 Declared · 2 Verified · 3 Bank-grade.

Emits prod-readiness.json (machine, for the admin-UI tab) and, with --table,
a human-readable scorecard. This is the ONLY source of truth for the matrix —
never hand-edit the JSON (rule #7: derived data is never hand-edited).

Usage:
    prod-readiness-collector.py ledger            # one service, with table
    prod-readiness-collector.py --all --json out  # whole fleet -> JSON
"""
from __future__ import annotations
import argparse
import json
import os
import re
import sys
from datetime import date
from dataclasses import dataclass, asdict, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gitops_facts import (  # noqa: E402  (path must be set before the import)
    declared_datastore,
    is_stateless,
    module_dir,
    money_path_services,
    podmonitor_namespaces,
    service_namespace,
)

REPO = Path(__file__).resolve().parents[2]
GITOPS = REPO / "openbank-infra" / "gitops"
THREAT_MODELS = REPO / "docs" / "threat-models"
RUNBOOKS = REPO / "docs" / "runbooks"
ATTESTATIONS = REPO / "openbank-libs" / "governance" / "attestations.yaml"
RELEASE_EVIDENCE = REPO / ".github" / "workflows" / "release-please.yml"
VEX_DIR = REPO / "openbank-libs" / "governance" / "vex"

# Money-path services come from rules.yaml, the authoritative list — never a copy. The literal
# that used to live here named 14 services while rules.yaml declared 20, so sdd, interest,
# billing, settlement, sanctions and vop were scored with the LENIENT gate and read GO (#2364).
# money_path_services() raises rather than returning an empty set, because an empty set would
# relax the gate for the whole fleet while looking like a clean run.
def money_path() -> set[str]:
    """The money-path set, read from rules.yaml at CALL time.

    Deliberately a function, not a module-level constant: REPO is rebindable (the test suite
    points it at a fixture tree), and a constant evaluated at import would both ignore that
    rebinding and raise on any tree without a rules.yaml.
    """
    return money_path_services(REPO)

DIMENSIONS = [
    ("C1", "Kód"),
    ("C2", "Testy"),
    ("C3", "API"),
    ("C4", "Data"),
    ("C5", "Zálohy"),
    ("C6", "DR/BCP"),
    ("C7", "Security"),
    ("C8", "Observab."),
    ("C9", "Provoz"),
]


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def svc_dir(short: str) -> Path:
    # Not every module is `openbank-<short>-service`: the three payment modules
    # (sepa-payment, sepa-instant, domestic-payment) drop the suffix. Resolving the shape here
    # is what lets them be scored at all (#2364).
    return module_dir(short, REPO)


def exists_dir(short: str) -> bool:
    return svc_dir(short).is_dir()


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def grep_any(root: Path, needles: list[str], globs=("*.kt", "*.kts")) -> bool:
    """True if any file under root matches any needle (cheap, bounded)."""
    if not root.is_dir():
        return False
    for g in globs:
        for f in root.rglob(g):
            text = read(f)
            if any(n in text for n in needles):
                return True
    return False


def gitops_files_for(short: str, kind: str) -> list[Path]:
    """gitops manifests that mention the service AND declare the given kind."""
    comp = GITOPS / "components" / short
    hits = []
    search_roots = [comp] if comp.is_dir() else [GITOPS]
    for root in search_roots:
        if not root.is_dir():
            continue
        for f in root.rglob("*.yaml"):
            text = read(f)
            if f"kind: {kind}" in text and (comp.is_dir() or short in text):
                hits.append(f)
    return hits


# ---------------------------------------------------------------------------
# attestations (M-dimensions) with TTL decay
# ---------------------------------------------------------------------------
def load_attestations(today: str) -> dict:
    """
    Minimal YAML reader for attestations (avoids a pyyaml dep for the demo).
    Schema per service:
        ledger:
          dr_drill:  { date: 2026-01-10, ttl_days: 180 }
          pentest:   { date: 2025-09-01, ttl_days: 365 }
    An attestation present + within TTL = bank-grade (+1); expired = decays.
    """
    if not ATTESTATIONS.exists():
        return {}
    # Intentionally tiny: real version uses pyyaml. Demo parses key: date pairs.
    data: dict = {}
    cur_svc = None
    cur_key = None
    for raw in read(ATTESTATIONS).splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        if indent == 0 and line.endswith(":"):
            cur_svc = line[:-1]
            data[cur_svc] = {}
        elif indent == 2 and line.endswith(":"):
            cur_key = line[:-1]
            data.setdefault(cur_svc, {})[cur_key] = {}
        elif indent == 2 and "{" in line:
            k, _, rest = line.partition(":")
            m = re.findall(r"(\w+):\s*([^\s,}]+)", rest)
            data.setdefault(cur_svc, {})[k.strip()] = dict(m)
    return data


def attest_fresh(att: dict, svc: str, key: str, today: str) -> bool:
    rec = att.get(svc, {}).get(key)
    if not rec or "date" not in rec:
        return False
    try:
        ttl = int(rec.get("ttl_days", "365"))
    except ValueError:
        ttl = 365
    # Exact calendar arithmetic. The previous form approximated a year as 365 days and a month
    # as 30, which let a TTL run PAST its own expiry — the one thing this mechanism exists to
    # prevent. consent's 21-day pentest, dated 2026-07-28, is 22 real days old on 2026-08-19 and
    # the approximation scored it (19-28)+... = 21 days, i.e. still fresh, so consent read
    # C7=Bank-grade off an expired attestation. The Node collector already used exact dates
    # (#2365); this is the same correction on the copy that had not received it.
    try:
        d = date.fromisoformat(rec["date"])
        t = date.fromisoformat(today)
    except ValueError:
        return False
    days = (t - d).days
    return 0 <= days <= ttl


# ---------------------------------------------------------------------------
# per-dimension scoring (each returns (score 0-3, evidence str))
# ---------------------------------------------------------------------------
def has_declared_ports(main: Path) -> bool:
    """True when the service declares hexagonal ports (ADR-0002).

    This used to require a FILE NAME containing `Port`, which is not the convention the fleet
    actually follows: anacredit and onboarding both carry a textbook
    `application/port/{in,out}` package whose files are named `*Repository.kt`, `*UseCases.kt`
    and `*Queries.kt`, and both were scored as having no ports at all. The architecture lives in
    the package, not in the filename — and the fix for a false negative here must never be to
    rename a file so the probe is satisfied. Either spelling counts now: a `port` package
    anywhere under the application layer, or the `*Port*.kt` naming other services use.
    """
    for p in main.rglob("*.kt"):
        parts = p.parts
        if "port" in parts or "ports" in parts:
            return True
        if "Port" in p.name:
            return True
    return False


def score_c1_code(short: str, att, today) -> tuple[int, str]:
    d = svc_dir(short)
    main = d / "src" / "main"
    if not main.is_dir():
        return 0, "no src/main"
    kt = list(main.rglob("*.kt"))
    has_ports = has_declared_ports(main)
    gov = (d / "governance.yaml").exists()
    # skeleton heuristic: very little code
    if len(kt) < 8:
        return 1, f"skeleton ({len(kt)} kt files)"
    base = 2 if (has_ports and gov) else 1
    if attest_fresh(att, short, "code_complete", today):
        base = 3
    return base, f"{len(kt)} kt, ports={'y' if has_ports else 'n'}, gov={'y' if gov else 'n'}"


def score_c2_tests(short: str, att, today) -> tuple[int, str]:
    d = svc_dir(short)
    test = d / "src" / "test"
    if not test.is_dir():
        return 0, "no src/test"
    kt = list(test.rglob("*.kt"))
    it = [f for f in kt if f.name.endswith("IT.kt")]
    unit = [f for f in kt if not f.name.endswith("IT.kt")]
    kover = "kover {" in read(d / "build.gradle.kts")
    if not kt:
        return 0, "empty test dir"
    s = 1
    if kover and kt:
        s = 2  # ratchet-gated coverage = verified
    # bank-grade needs explicit coverage floor attestation for money-path
    if attest_fresh(att, short, "coverage_floor", today):
        s = 3
    return s, f"{len(unit)} unit, {len(it)} IT, kover={'y' if kover else 'n'}"


def has_contract_test(short: str) -> tuple[bool, str]:
    """True when the service ships an actual contract test, with what proves it.

    This used to be `grep_any(src/test, ["Pact", "ContractTest", "contract"])` — a substring
    scan that counted the word "contract" ANYWHERE, including comments and KDoc. Five services
    matched on prose alone, and three of them (pid, psd2, sanctions) scored C3=Verified purely
    on a sentence like `// the mark-and-sweep reconciliation contract` while shipping no
    contract test at all. ap2 flipped 1→2 the moment an unrelated PR added the comment
    `// Cardinality + evidence contract: …`, which nobody wrote with a score in mind — the
    clearest possible demonstration that prose must never be evidence.

    So this asks for artifacts a contract test cannot exist without: the pact library imported,
    or the fleet's test-class naming (`*Pact*Test.kt`, `*ContractTest.kt`). It is the same
    correction the C8 scorer needed, where a comment in the PodMonitor claiming a service was
    scraped scored it as scraped (#2255).
    """
    test = svc_dir(short) / "src" / "test"
    if not test.is_dir():
        return False, ""
    by_import: list[str] = []
    by_name: list[str] = []
    for f in test.rglob("*.kt"):
        if "au.com.dius.pact" in read(f):
            by_import.append(f.name)
        elif "Pact" in f.name or f.name.endswith("ContractTest.kt"):
            by_name.append(f.name)
    if by_import:
        return True, f"{len(by_import)} pact test(s)"
    if by_name:
        return True, f"{len(by_name)} contract test(s) by naming"
    return False, ""


def committed_pacts(short: str) -> list[str]:
    """Pact files in the repo-root `pacts/` dir naming this service on either side (ADR-0063)."""
    pacts = REPO / "pacts"
    if not pacts.is_dir():
        return []
    token = svc_dir(short).name  # module dir name — the payment modules drop the -service suffix
    return sorted(p.name for p in pacts.glob("*.json") if token in p.name)


def score_c3_api(short: str, att, today) -> tuple[int, str]:
    d = svc_dir(short)
    openapi = (d / "src" / "main" / "resources" / "openapi.yaml").exists()
    contract, how = has_contract_test(short)
    pacts = committed_pacts(short)
    if not openapi:
        return 0, "no openapi.yaml"
    s = 2 if contract else 1
    if attest_fresh(att, short, "contract_verified", today):
        s = 3  # bank-grade needs an external consumer-verified pact (attested)
    ev = ["openapi=y", how if contract else "NO contract test in src/test"]
    if pacts:
        ev.append(f"{len(pacts)} committed pact(s)")
    return s, ", ".join(ev)


def score_c4_data(short: str, att, today) -> tuple[int, str]:
    d = svc_dir(short)
    migs = list((d).rglob("db/migration/V*.sql"))
    datastore = declared_datastore(short, REPO)
    stateless = is_stateless(datastore)
    if stateless and not migs:
        # A service that declares no datastore has no migrations to review and no rollback note
        # to write, so the old hardcoded `1, "no flyway (stateless?)"` made 2 UNREACHABLE for it
        # — the matrix asked ap2, copilot, finrep and mcp for an artifact that would be wrong to
        # produce. The dimension is not applicable, and the evidence string says so rather than
        # implying a migration was reviewed.
        return 2, "n/a — declares no datastore (stateless)"
    if stateless and migs:
        # Declared facts and shipped code disagree. Whichever is wrong, nobody can review a data
        # dimension that is described two different ways, so this is a finding, not a pass.
        return 1, f"CONTRADICTION: governance.yaml says datastore '{datastore or 'none'}' but {len(migs)} migration(s) exist"
    if not migs:
        return 1, f"declares datastore '{datastore}' but has no Flyway migration"
    # rollback note: a paired comment or docs/rollback reference
    rollback = any("rollback" in read(m).lower() for m in migs) or \
        grep_any(d, ["rollback"], globs=("*.md",))
    s = 2 if rollback else 1
    return s, f"{len(migs)} migrations, rollback_note={'y' if rollback else 'n'}"


def is_undeployed(short: str) -> bool:
    """True when the service has NO workload in gitops at all — not a Deployment, not a Rollout.

    Distinct from "deployed but unscraped" and from "stateless". A released component with no
    workload cannot own a CNPG cluster, a PodMonitor namespace or a NetworkPolicy, so the cells
    that read those all report absences the service could not have avoided while it stays
    undeployed. openbank-tax-reporting-service is the one such service today and it is
    deliberate — `openbank-infra/aws/envs/sandbox-platform/ecr-service-repositories.tf` names it
    as "a released component with no gitops workload, no auto-deploy entry and — correctly — no
    repository" (#5760). Naming that state is the honest representation; it is NOT a pass, see
    [ServiceReadiness.compute_gate].
    """
    return service_namespace(short, GITOPS) is None


def score_c5_backup(short: str, att, today) -> tuple[int, str]:
    if is_stateless(declared_datastore(short, REPO)):
        # No datastore, nothing to back up. finrep scored 0 ("no CNPG cluster") for the absence
        # of a cluster it must not have — an unachievable 0 that read like a missing backup.
        return 2, "n/a — declares no datastore (stateless), nothing to back up"
    if is_undeployed(short):
        # Still 0 — a stateful service with no backup is not ready, and an undeployed one is not
        # ready either. Only the EVIDENCE changes: "no CNPG cluster" reads as a backup someone
        # forgot to configure and sends the reader to the gitops backup docs, when the cluster is
        # absent because the entire workload is (#5760). The gate says which of the two it is.
        return 0, "no CNPG cluster — service has no gitops workload at all"
    clusters = gitops_files_for(short, "Cluster")
    cnpg = [f for f in clusters if "postgres" in f.name or "cnpg" in read(f).lower()]
    if not cnpg:
        return 0, "no CNPG cluster"
    has_backup = any("barmanObjectStore" in read(f) for f in cnpg)
    if not has_backup:
        return 1, "cluster present, NO backup"
    s = 2  # backup configured = verified
    if attest_fresh(att, short, "restore_drill", today):
        s = 3
    return s, "backup configured" + (" + drill" if s == 3 else "")


def score_c6_dr(short: str, att, today) -> tuple[int, str]:
    # 0 none · 1 generic infra runbooks only · 2 a service-specific DR procedure is
    # DOCUMENTED (per-service runbook carries a "Disaster recovery" section: RPO/RTO
    # + restore steps) · 3 a drill has been EXERCISED + attested (TTL). The 2/3 split
    # is the honesty line: a written, reviewed DR plan is Verified; only a real
    # rehearsal is Bank-grade.
    if attest_fresh(att, short, "dr_drill", today):
        return 3, "DR drill exercised"
    rb = RUNBOOKS / f"svc-{short}.md"
    if rb.exists() and re.search(r"^#+\s*Disaster recovery", read(rb), re.M | re.I):
        return 2, "service DR procedure documented"
    has_runbooks = RUNBOOKS.is_dir() and any(RUNBOOKS.glob("*.md"))
    return (1, "generic runbooks only") if has_runbooks else (0, "no DR")


def has_signed_provenance(short: str) -> bool:
    """Repo-derivable supply-chain signal: a released component (has version.txt) is covered
    by the signed evidence-bundle pipeline (release-please.yml's release-evidence job:
    SBOM+SLSA+VEX+manifest, KMS-cosign-signed, attached to each release; ADR-0030 D4). No
    network — mirrors the collector's repo-only design."""
    pipeline = RELEASE_EVIDENCE.exists() and "release-evidence" in read(RELEASE_EVIDENCE)
    released = (svc_dir(short) / "version.txt").exists()
    return pipeline and released


def has_vex_triage(short: str) -> bool:
    """Stronger, per-service signal: a human-reviewed OpenVEX triage store exists."""
    return any((VEX_DIR / f"{n}.openvex.json").exists() for n in (f"{short}-service", short))


def score_c7_security(short: str, att, today) -> tuple[int, str]:
    tm = (THREAT_MODELS / f"{svc_dir(short).name}.md").exists()
    netpol = bool(gitops_files_for(short, "NetworkPolicy"))
    sectest = grep_any(svc_dir(short) / "src" / "test",
                       ["Security", "schemathesis", "Authz"])
    prov = has_signed_provenance(short)
    bits = sum([tm, netpol, sectest, prov])
    if bits == 0:
        return 0, "no threat-model/netpol/sectest/provenance"
    s = 1 if bits == 1 else 2
    # Bank-grade C7 still requires an external pentest attestation (TTL). Supply-chain
    # provenance is a complementary control, not a substitute for adversarial testing.
    if attest_fresh(att, short, "pentest", today):
        s = 3
    ev = []
    if tm: ev.append("threat-model")
    if netpol: ev.append("netpol")
    if sectest: ev.append("sec-test")
    if prov: ev.append("signed-provenance")
    if has_vex_triage(short): ev.append("vex-triage")
    return s, ", ".join(ev)


def score_c8_observability(short: str, att, today) -> tuple[int, str]:
    # The fleet PodMonitor scrapes by namespaceSelector.matchNames, so "is this service scraped"
    # is a question about its NAMESPACE. This used to ask `short in read(podmonitor.yaml)` — a
    # substring match over the whole file, comments included. A comment asserting sdd-service was
    # covered "via `payments`" was simply false (sdd runs in namespace `sdd`), and that false
    # claim scored sdd as scraped while its metrics reached nothing; meanwhile ap2, mcp, vop,
    # card-issuance, settlement and standing-order WERE scraped via `platform`/`payments` and
    # scored as if they were not, because their names appear nowhere in the file (#2255).
    ns = service_namespace(short, GITOPS)
    scraped_namespaces = podmonitor_namespaces(GITOPS)
    monitored = ns is not None and ns in scraped_namespaces
    alerts = bool(gitops_files_for(short, "PrometheusRule"))
    metrics = grep_any(svc_dir(short) / "src" / "main",
                       ["MeterRegistry", "DomainMetrics", "@Counted", "@Timed"])
    # Evidence names the namespace and the specific missing half, so a 0 or 1 is actionable
    # without re-deriving it: "add the namespace to the PodMonitor" and "instrument the domain"
    # are different pieces of work and used to be reported identically as "not scraped".
    ev = []
    if ns is None:
        ev.append("not deployed (no Deployment/Rollout in gitops)")
    elif monitored:
        ev.append(f"scraped (ns {ns})")
    else:
        ev.append(f"NOT scraped — ns '{ns}' absent from PodMonitor matchNames")
    ev.append("domain metrics" if metrics else "NO domain metrics in src/main")
    if alerts:
        ev.append("alerts")
    evidence = ", ".join(ev)

    if not monitored and not metrics:
        return 0, evidence
    s = 1
    if monitored and metrics:
        s = 2  # bank-grade (3) needs defined SLO + burn-rate alerts (attestation)
    if attest_fresh(att, short, "slo_defined", today):
        s = 3
    return s, evidence


def score_c9_ops(short: str, att, today) -> tuple[int, str]:
    # 1 no per-service runbook · 2 a service runbook exists (docs/runbooks/svc-<x>.md)
    # · 3 on-call rotation + break-glass audited (attested, TTL). Match the runbook
    # by its exact conventional name so an unrelated file merely containing the short
    # name cannot inflate the score.
    if attest_fresh(att, short, "oncall", today):
        return 3, "on-call + break-glass audited"
    return (2, "service runbook") if (RUNBOOKS / f"svc-{short}.md").exists() else \
        (1, "no per-service runbook")


SCORERS = [
    score_c1_code, score_c2_tests, score_c3_api, score_c4_data,
    score_c5_backup, score_c6_dr, score_c7_security,
    score_c8_observability, score_c9_ops,
]


# ---------------------------------------------------------------------------
@dataclass
class ServiceReadiness:
    service: str
    money_path: bool
    scores: dict = field(default_factory=dict)
    evidence: dict = field(default_factory=dict)
    gate: str = ""

    def compute_gate(self):
        # money-path: all >= 2, critical (C1/C5/C7) >= 3 ; else all >= 2
        critical = {"C1", "C5", "C7"}
        ok = True
        for code, _ in DIMENSIONS:
            s = self.scores[code]
            need = 3 if (self.money_path and code in critical) else 2
            if s < need:
                ok = False
        # A released component with no workload anywhere in gitops gets its own verdict. It is
        # NOT a pass and can never become one from here: NOT-DEPLOYED is only ever reached in
        # place of NO-GO — a service that clears every dimension while undeployed is impossible
        # (C8 scores at most 1 without a namespace), and the `ok` branch is checked first anyway,
        # so no score this state can produce is treated more leniently than before.
        #
        # What it buys: "not production-ready" and "not in production" are different facts and
        # used to render identically. tax-reporting sat in the NO-GO column next to 25 services
        # that ARE deployed and failing a control, so its actual blocker — an undecided
        # deployment (#5760, #5706) — was invisible in every headline the matrix produced, and
        # three of its cells read as missing controls rather than as consequences.
        if ok:
            self.gate = "GO"
        elif is_undeployed(self.service):
            self.gate = "NOT-DEPLOYED"
        else:
            self.gate = "NO-GO"


def collect(short: str, att, today: str) -> ServiceReadiness:
    r = ServiceReadiness(service=short, money_path=short in money_path())
    # strict: a dimension without a scorer would otherwise be silently skipped and the
    # readiness score computed over fewer cells than it claims.
    for (code, _), scorer in zip(DIMENSIONS, SCORERS, strict=True):
        s, ev = scorer(short, att, today)
        r.scores[code] = s
        r.evidence[code] = ev
    r.compute_gate()
    return r


def all_services() -> list[str]:
    """Every module the matrix scores: the `-service` modules PLUS every declared money-path one.

    The `openbank-*-service` glob alone missed openbank-sepa-payment, openbank-sepa-instant and
    openbank-domestic-payment — three released components with a governance.yaml, an openapi.yaml
    and a threat model, which rules.yaml declares money-path and which move SEPA and domestic
    payments. They had no row at all, so every headline this collector produced ("N cells below
    Verified", "GO x/37") silently excluded them (#2364). A module governance calls money-path
    must never be absent from the readiness matrix.

    Deliberately NOT widened to every module carrying a governance.yaml + src/main: that would
    add 12 further agent/auxiliary modules and is a scoping decision about what the matrix
    covers, not a correctness fix.
    """
    out = set()
    for d in REPO.glob("openbank-*-service"):
        m = re.match(r"openbank-(.+)-service", d.name)
        if m:
            out.add(m.group(1))
    for short in money_path():
        if module_dir(short, REPO).is_dir():
            out.add(short)
    return sorted(out)


# ---------------------------------------------------------------------------
def render_table(results: list[ServiceReadiness]):
    glyph = {0: "·", 1: "○", 2: "◐", 3: "●"}
    head = "SLUŽBA".ljust(18) + " ".join(c for c, _ in DIMENSIONS) + "  │ GATE"
    print(head)
    print("─" * len(head))
    for r in results:
        tag = " ★" if r.money_path else "  "
        row = (r.service + tag).ljust(18) + " ".join(glyph[r.scores[c]] + " " for c, _ in DIMENSIONS)
        print(f"{row} │ {r.gate}")
    print()
    print("legenda: · Absent(0)  ○ Declared(1)  ◐ Verified(2)  ● Bank-grade(3)   ★ money-path")
    # per-service evidence for the demo (one service)
    if len(results) == 1:
        r = results[0]
        print(f"\nEvidence — {r.service}:")
        for code, name in DIMENSIONS:
            print(f"  {code} {name:<10} {r.scores[code]} {glyph[r.scores[code]]}  {r.evidence[code]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("services", nargs="*", help="service short names (e.g. ledger)")
    ap.add_argument("--all", action="store_true", help="score the whole fleet")
    # Default to the real current date so the baked `generated_for` and the TTL
    # decay arithmetic stay live across builds; READINESS_TODAY pins it for tests.
    ap.add_argument("--today", default=os.environ.get("READINESS_TODAY") or date.today().isoformat())
    ap.add_argument("--json", help="write prod-readiness.json to this path")
    ap.add_argument("--table", action="store_true", default=True)
    args = ap.parse_args()

    att = load_attestations(args.today)
    targets = all_services() if args.all else args.services
    if not targets:
        ap.error("give a service name or --all")

    results = []
    for short in targets:
        if not exists_dir(short):
            print(f"skip: no module directory for {short!r}", file=sys.stderr)
            continue
        results.append(collect(short, att, args.today))

    if args.table:
        render_table(results)

    if args.json:
        payload = {
            "generated_for": args.today,
            "dimensions": [{"code": c, "name": n} for c, n in DIMENSIONS],
            "services": [asdict(r) for r in results],
        }
        Path(args.json).write_text(json.dumps(payload, indent=2, ensure_ascii=False))
        print(f"\nwrote {args.json}", file=sys.stderr)


if __name__ == "__main__":
    main()
