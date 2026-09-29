#!/usr/bin/env python3
"""Threat-model coverage gate for money-path services (ADR-0030 D2).

Enforces `rules.yaml: money_path_services`: every money-path bounded context MUST
ship a structured threat model at `docs/threat-models/<service>.md`. A design-phase
STRIDE/DFD review is mandatory for the services that move money (ledger, payments,
SCA, …); this gate makes that a *technical* fact, not etiquette, and ratchets it —
adding a money-path service or deleting its threat model fails CI.

A file counts as a real threat model only if it is non-trivial AND mentions the
STRIDE method (or its categories) — so an empty stub can't satisfy the gate.

stdlib only — runs in PR CI with no cloud credentials.

Modes:
  (default)   gate     -> print findings, exit 1 if any money-path service lacks
                          a valid threat model.
  --report             -> markdown report to stdout (exit 0); first line is
                          `THREATMODEL_FINDING=0|1` for an audit workflow.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"
TM_DIR = REPO / "docs" / "threat-models"

MIN_BYTES = 500  # a real STRIDE/DFD model is well past this; a stub is not.
# STRIDE categories — at least one must appear, so the doc is a real threat model
# and not just a placeholder heading.
STRIDE = re.compile(
    r"\bSTRIDE\b|\bspoofing\b|\btampering\b|\brepudiation\b|"
    r"information disclosure|\bdenial of service\b|elevation of privilege",
    re.IGNORECASE,
)


def money_path_services(rules: pathlib.Path = None) -> list[str]:
    """Parse the `money_path_services:` YAML list without a yaml dependency."""
    rules = rules or RULES
    if not rules.exists():
        return []
    out: list[str] = []
    in_block = False
    for line in rules.read_text(encoding="utf-8").splitlines():
        if re.match(r"^money_path_services:\s*$", line):
            in_block = True
            continue
        if in_block:
            # Trailing `# comment` allowed — entries carry ADR references inline.
            m = re.match(r"^\s+-\s+(\S+)\s*(?:#.*)?$", line)
            if m:
                out.append(m.group(1))
            elif line.strip() and not line.startswith((" ", "\t")):
                break  # next top-level key ends the block
    return out


GITOPS = REPO / "openbank-infra" / "gitops" / "components"
IMAGE_REF = re.compile(r"image:[^\n]*?(openbank-[a-z0-9-]+)")

# Deployed workloads that carry NO threat model today (#9167). SHRINK-ONLY: a new deployed
# workload without one FAILS, and an entry here that stops being a gap fails too, so the list
# cannot rot into permanence the way a hand-kept scope list does.
#
# Why this population and not "every service": the question #9167 asks is whether a service that
# owns a secret or a NetworkPolicy should need a model, and a workload under gitops/components is
# exactly one that does — it has an image, a namespace and a policy. Money-path services are a
# SEPARATE, harder rule above: they have no baseline and never will.
#
# The entries are not all the same kind of debt, and the reason says which. A third-party image
# has a different answer from a bounded context nobody has modelled yet, and flattening the two
# would make this list read as 23 identical omissions.
DEPLOYED_BASELINE: dict[str, str] = {
    "openbank-admin-ui":
        "#9167 — bounded context, not yet modelled",
    "openbank-aml-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-analytics-sink":
        "#9167 — bounded context, not yet modelled",
    "openbank-ap2-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-audit-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-authz-policy-auditor":
        "#9167 — agent, not yet modelled",
    "openbank-campaign-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-case-coordinator-agent":
        "#9167 — agent, not yet modelled",
    "openbank-clearing-simulator":
        "#9167 — bounded context, not yet modelled",
    "openbank-communication-service":
        "#9167 — a model exists but carries no STRIDE framing — prose that satisfies the claims gate and not this one",
    "openbank-control-liveness-sentinel":
        "#9167 — agent, not yet modelled",
    "openbank-developer-portal":
        "#9167 — bounded context, not yet modelled",
    "openbank-devops-agent":
        "#9167 — agent, not yet modelled",
    "openbank-docs-truth-agent":
        "#9167 — agent, not yet modelled",
    "openbank-document-renderer":
        "#9167 — bounded context, not yet modelled",
    "openbank-document-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-engagement-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-finops-agent":
        "#9167 — agent, not yet modelled",
    "openbank-finrep-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-flaky-test-hunter":
        "#9167 — agent, not yet modelled",
    "openbank-governance-auditor":
        "#9167 — agent, not yet modelled",
    "openbank-keycloak":
        "#9167 — third-party image; a model here is about its CONFIGURATION, not code this repo ships",
    "openbank-kyb-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-kyc-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-onboarding-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-party-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-pid-service":
        "#9167 — bounded context, not yet modelled",
    "openbank-product-catalog":
        "#9167 — bounded context, not yet modelled",
    "openbank-pyroscope-agent":
        "#9167 — third-party image; a model here is about its CONFIGURATION, not code this repo ships",
    "openbank-referral-service":
        "#9167 — a model exists but carries no STRIDE framing — prose that satisfies the claims gate and not this one",
    "openbank-release-steward":
        "#9167 — agent, not yet modelled",
    "openbank-statement-service":
        "#9167 — bounded context, not yet modelled",
}


def deployed_workloads(gitops: pathlib.Path = None) -> list[str]:
    """Every `openbank-*` image referenced by a manifest under gitops/components.

    Derived, never listed: a hand-kept population is the shape that reads as full coverage while
    silently shrinking, which is the defect this whole file exists to avoid.
    """
    root = gitops or GITOPS
    if not root.exists():
        return []
    found: set[str] = set()
    for path in sorted(root.rglob("*.yaml")):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        found.update(IMAGE_REF.findall(text))
    return sorted(found)


def evaluate(service: str, tm_dir: pathlib.Path = None) -> tuple[str, str]:
    """Return (status, detail) where status in {ok, missing, stub}."""
    path = (tm_dir or TM_DIR) / f"{service}.md"
    if not path.exists():
        return "missing", "no docs/threat-models/%s.md" % service
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        return "missing", f"unreadable: {e}"
    if len(text.encode("utf-8")) < MIN_BYTES:
        return "stub", f"only {len(text)} chars (< {MIN_BYTES} — looks like a stub)"
    if not STRIDE.search(text):
        return "stub", "no STRIDE/threat categories found — not a structured model"
    return "ok", f"{len(text)} chars"


def self_test() -> int:
    """Falsify the model classifier and the money-path parser.

    ADR-0030 D2: a money-path service needs a real STRIDE threat model. The two ways this
    gate can fail quietly are opposite and both look like coverage — a STUB passing as a
    model, and a money-path list that parses SHORT so the services with no model at all are
    never asked about. The second is the one that reads as a clean fleet.
    """
    import tempfile

    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    with tempfile.TemporaryDirectory() as td:
        d = pathlib.Path(td)

        # A real model: past the byte floor AND structured.
        (d / "openbank-real.md").write_text("# Threat model\n\nSpoofing: ...\n" + "x" * 600)
        case("a structured model over the floor is ok", evaluate("openbank-real", d)[0], "ok")

        # THE DEFECT: a file exists, so the naive check ("is there a file?") would pass — but
        # it is a stub, which is the shape a coverage sweep produces when someone is closing
        # a gate rather than modelling a threat.
        (d / "openbank-stub.md").write_text("# TODO\n")
        case("a short stub is NOT a model", evaluate("openbank-stub", d)[0], "stub")

        # SHORT but structured: only the byte floor can reject this one. Without it the file
        # passes on the word "Spoofing" alone — which is exactly how a stub gets written to
        # close a gate. The previous fixture could not reach this branch because it also
        # failed the STRIDE check, so removing the floor went UNCAUGHT.
        (d / "openbank-shortstride.md").write_text("Spoofing: TODO\n")
        st, detail = evaluate("openbank-shortstride", d)
        case("a short file that merely NAMES a STRIDE category is a stub", st, "stub")
        if "< " not in detail:
            fails.append(f"the short-but-structured case failed for the wrong reason: {detail!r}")

        # Long but unstructured: prose about the service is not a threat model.
        (d / "openbank-prose.md").write_text("This service handles payments. " * 40)
        case("a long file with no STRIDE categories is a stub",
             evaluate("openbank-prose", d)[0], "stub")

        # Absent entirely. The DETAIL is asserted, not just the status: an unreadable file
        # also returns "missing", so without the message a broken existence check is
        # indistinguishable from a working one (measured — removing the check went uncaught).
        st, detail = evaluate("openbank-nothing", d)
        case("a missing model is missing", st, "missing")
        if "no docs/threat-models/" not in detail:
            fails.append(f"a missing model reported the wrong reason: {detail!r}")

        # --- the money-path parser, which decides WHO is asked -------------------------
        r = d / "rules.yaml"
        r.write_text("money_path_services:\n  - openbank-a\n  - openbank-b  # with a comment\n"
                     "other_key:\n  - openbank-not-money-path\n")
        got = money_path_services(r)
        case("the money-path list parses, comments and all", got, ["openbank-a", "openbank-b"])
        # The NEXT top-level key must end the block — without that the parser swallows the
        # rest of rules.yaml and the gate demands threat models for things that are not
        # services at all, which gets it switched off.
        if "openbank-not-money-path" in got:
            fails.append("the parser ran past the end of its block into the next top-level key")

        # An absent rules.yaml yields an empty list, which the comparison would report as a
        # clean fleet — so main() must refuse it. Asserted here as the parser's own contract.
        case("an absent rules.yaml parses as empty", money_path_services(d / "nope.yaml"), [])

    # A live read: the fixtures cannot tell that RULES and TM_DIR still resolve in this repo.
    live = money_path_services()
    if not live:
        fails.append("reading the real rules.yaml produced NO money-path services — "
                     "the parser or the path moved, and this gate is asking about nobody")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: threat-model coverage is falsifiable "
          f"(9 cases + a live read of {len(live)} money-path services)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="markdown report, exit 0")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    services = money_path_services()
    if not services:
        # An empty money-path list is not a fleet with nothing to model — it is the parser or
        # the path having moved. Reporting "0 gaps" about it would be a pass earned by
        # reading nothing.
        print("::error::check-threat-models: parsed ZERO money-path services from rules.yaml "
              "— refusing to report coverage about nobody.")
        return 1
    results = [(s, *evaluate(s)) for s in services]
    bad = [(s, st, d) for (s, st, d) in results if st != "ok"]

    # The SECOND population (#9167): every deployed workload, ratcheted. Money-path above is a hard
    # requirement with no baseline; this one carries today's debt so a NEW deployed workload cannot
    # join without a model. The ratchet is two-way — a baseline entry that stops being a gap fails
    # too, so the list cannot outlive its subject the way a hand-kept scope list does.
    deployed = deployed_workloads()
    if not deployed:
        print("::error::check-threat-models: parsed ZERO deployed workloads from gitops/components "
              "— refusing to report coverage about nobody.")
        return 1
    deployed_gaps = {s: st for s in deployed for st, _ in [evaluate(s)] if st != "ok"}
    new_gaps = sorted(set(deployed_gaps) - set(DEPLOYED_BASELINE))
    stale = sorted(set(DEPLOYED_BASELINE) - set(deployed_gaps))

    if args.report:
        print(f"THREATMODEL_FINDING={1 if bad else 0}")
        print("\n## Threat-model coverage — money-path (ADR-0030 D2)\n")
        print(f"- money-path services: **{len(services)}**")
        print(f"- with a valid threat model: **{len(services) - len(bad)}**")
        print(f"- gaps: **{len(bad)}**\n")
        if bad:
            print("| service | status | detail |")
            print("|---|---|---|")
            for s, st, d in bad:
                print(f"| `{s}` | {st} | {d} |")
        else:
            print("All money-path services carry a structured threat model. ✅")
        return 0

    for s_ in new_gaps:
        print(f"::error::{s_}: deployed under gitops/components with no structured threat model "
              f"(#9167). Write docs/threat-models/{s_}.md, or add it to DEPLOYED_BASELINE with a "
              f"reason if that is genuinely the right answer for this workload.")
    for s_ in stale:
        print(f"::error::DEPLOYED_BASELINE entry `{s_}` is no longer a gap — drop it from "
              f"check-threat-models.py so the baseline cannot outlive its subject.")

    print(f"SUBJECTS={len(services) + len(deployed)}  # money-path + deployed workloads examined")
    print(f"deployed-workload coverage (#9167): {len(deployed)} workloads, "
          f"{len(deployed_gaps)} without a model, {len(DEPLOYED_BASELINE)} baselined, "
          f"{len(new_gaps)} new, {len(stale)} stale")
    print(f"Threat-model gate (ADR-0030 D2): {len(services)} money-path services")
    for s, st, d in results:
        mark = "OK " if st == "ok" else "!! "
        print(f"  {mark}{s}: {st} ({d})")
    if bad:
        print(
            f"\nFAIL: {len(bad)} money-path service(s) without a valid threat model.\n"
            "Add docs/threat-models/<service>.md (STRIDE/DFD) — ADR-0030 D2, rules.yaml.",
            file=sys.stderr,
        )
        return 1
    if new_gaps or stale:
        print(
            f"\nFAIL: {len(new_gaps)} deployed workload(s) with no threat model and not baselined, "
            f"{len(stale)} stale baseline entr(ies) — #9167.",
            file=sys.stderr,
        )
        return 1
    print(f"\nOK: all {len(services)} money-path services have a structured threat model, "
          f"and every deployed workload without one is baselined against #9167.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
