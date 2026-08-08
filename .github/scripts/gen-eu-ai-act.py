#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Generate docs/compliance/eu-ai-act.md from openbank-libs/governance/agents.yaml (ADR-0148).
#
# WHY THIS IS GENERATED, NOT HAND-WRITTEN (rule #7, derived data):
#   The EU AI Act mapping must stay true to the charters as they change. A hand-maintained
#   table drifts the first time a charter is added or a tool moved. This script derives the
#   risk classification and the article-by-article coverage from the single source of truth
#   (agents.yaml), so the mapping is regenerated — never edited — whenever the charters change.
#
# It answers, for each AI system (agent charter) the platform runs:
#   1. Its EU AI Act risk class (Annex III high-risk vs limited/minimal), from what it does.
#   2. For a high-risk system, which Art. 9-15 obligation each EXISTING control already
#      satisfies, and which remain open.
#
# The platform position (2026-07): NO production high-risk AI system exists today — no charter
# performs creditworthiness assessment (Annex III 5(b)); ADR-0142 (credit decisioning) is
# Proposed and unbuilt. This document is the precondition ADR-0142 must reference before it can
# move past Proposed, and the standing inventory the AI Act (in force for high-risk from
# 2026-08-02) requires a deployer to keep.
#
# Regenerate:  python3 .github/scripts/gen-eu-ai-act.py
# Verify:      the check-eu-ai-act guard fails CI if the committed doc drifts from a fresh run.

import hashlib
import pathlib
import sys

try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML required: pip install pyyaml\n")
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parents[2]
AGENTS = ROOT / "openbank-libs" / "governance" / "agents.yaml"
# Non-agent AI/ML systems (fraud scoring plane, ML decisioning substrate) — the AI systems that
# are NOT LLM agent charters. Kept out of agents.yaml on purpose (that file is hashed verbatim
# into ~29 OPA bundles); consumed only here to complete the EU AI Act inventory. See its header.
ML_SYSTEMS = ROOT / "openbank-libs" / "governance" / "ml-systems.yaml"
OUT = ROOT / "docs" / "compliance" / "eu-ai-act.md"

# --- classification ruleset (encoded, reviewed here — not free text in the doc) -------------
# Annex III high-risk triggers relevant to a bank. A charter is high-risk if any tool or data
# scope matches a trigger. Creditworthiness (5(b)) is the one that bites; fraud/AML oversight is
# NOT itself Annex III high-risk when it only produces proposals a human dispositions.
HIGH_RISK_TRIGGERS = {
    # substring in a tool name -> (Annex III point, why)
    "credit.score": ("III.5(b)", "creditworthiness assessment / credit scoring"),
    "credit.decision": ("III.5(b)", "credit decisioning"),
    "propose.credit": ("III.5(b)", "credit decisioning (proposal)"),
}

# Art. 9-15 high-risk obligations, and the platform control that (partly) satisfies each.
OBLIGATIONS = [
    ("Art. 9  Risk management system",
     "HITL approval queue (ADR-0031 D4) + kill switch (D7); deny-by-default OPA charter."),
    ("Art. 10 Data & data governance",
     "PII masking (defaults.pii=masked); data_scope is least-privilege per charter."),
    ("Art. 11 Technical documentation",
     "This ADR-0148 mapping + the charter in agents.yaml + the model card (ADR-0141)."),
    ("Art. 12 Record-keeping / logging",
     "AI-attributed AuditEvent per action with model_id/model_version/prompt_hash (ADR-0031 D5, ADR-0086 chain)."),
    ("Art. 13 Transparency to deployers",
     "Charter + prompt registry (ADR-0148) make each agent's inputs/behaviour inspectable."),
    ("Art. 14 Human oversight",
     "requires_human on every write; approver_must_differ_from author (segregation of duties)."),
    ("Art. 15 Accuracy, robustness, cybersecurity",
     "Prompt-injection guard; evals gate (ADR-0148) blocks a regressing model/prompt; SPIFFE identity."),
]


def classify(agent):
    tools = agent.get("tools", {}) or {}
    allow = tools.get("allow", []) or []
    names = []
    for t in allow:
        if isinstance(t, str):
            names.append(t)
        elif isinstance(t, dict):
            names.extend(str(r) for r in (t.get("resources", []) or []))
            names.append(str(t.get("tier", "")))
    hay = " ".join(names).lower() + " " + str(agent.get("charter", "")).lower()
    for needle, (point, why) in HIGH_RISK_TRIGGERS.items():
        if needle in hay:
            return ("HIGH-RISK", point, why)
    return ("Limited / minimal risk", "—",
            "produces proposals only; a human dispositions every effect (not autonomous decision-making on a natural person).")


def main():
    data = yaml.safe_load(AGENTS.read_text())
    agents = data.get("agents", []) or []
    ml_data = yaml.safe_load(ML_SYSTEMS.read_text()) if ML_SYSTEMS.exists() else {}
    ml_systems = (ml_data or {}).get("ml_systems", []) or []
    # A non-agent ML system flips the obligations to "APPLIES NOW" only if it is both HIGH-RISK
    # and actually deployed-and-acting (deployed: true). A planned or shadow-only high-risk system
    # is inventoried but does not yet trigger the article-by-article obligations.
    ml_high_risk_live = [
        m.get("id") for m in ml_systems
        if m.get("risk_class") == "HIGH-RISK" and m.get("deployed") is True
    ]

    L = []
    w = L.append
    w("<!-- GENERATED by .github/scripts/gen-eu-ai-act.py from openbank-libs/governance/agents.yaml.")
    w("     Do not hand-edit — edit the charters and regenerate (ADR-0148, rule #7). -->")
    w("")
    w("# EU AI Act — system inventory and obligation mapping")
    w("")
    w("_Generated from `openbank-libs/governance/agents.yaml` (ADR-0148). Regenerate with")
    w("`python3 .github/scripts/gen-eu-ai-act.py`._")
    w("")
    w("## Position")
    w("")
    w("The EU AI Act's obligations for **high-risk** systems apply from **2026-08-02**.")
    w("Creditworthiness assessment of natural persons is high-risk under **Annex III(5)(b)**.")
    w("")
    w("**No production high-risk AI system exists on this platform today.** No agent charter")
    w("performs credit scoring or credit decisioning; ADR-0142 (credit decisioning) is")
    w("*Proposed* and unbuilt. The customer- and operator-facing agents are")
    w("proposal-only — a human dispositions every effect — which keeps them out of the")
    w("autonomous-decision-making category. The one ML system near the money path, the fraud")
    w("scoring plane (ADR-0084), runs its model in **shadow mode** — the verdict is logged, never")
    w("enforced — and fraud detection is not Annex III creditworthiness. This document is (a) the")
    w("standing AI-system inventory a deployer must keep — LLM agents *and* the non-agent ML")
    w("systems below — and (b) the precondition ADR-0142 must satisfy article-by-article before")
    w("it can move past *Proposed*.")
    w("")
    w("## System inventory")
    w("")
    w("| Agent charter | Plane | Risk class | Annex III | Basis |")
    w("|---|---|---|---|---|")
    high_risk = []
    for a in agents:
        cls, point, why = classify(a)
        if cls == "HIGH-RISK":
            high_risk.append(a.get("id"))
        w(f"| `{a.get('id')}` | {a.get('plane', '—')} | {cls} | {point} | {why} |")
    w("")

    # --- Non-agent ML / statistical systems (ADR-0084 fraud plane, ADR-0139/0140/0141/0142) -----
    # The inventory the AI Act requires covers EVERY AI system, not only the LLM agent charters.
    # The fraud scoring plane and the ML decisioning substrate are AI systems that are not charters;
    # they are inventoried here from ml-systems.yaml so the document names them explicitly rather
    # than leaving them out (an inventory that omits a running system reads as a false all-clear).
    if ml_systems:
        w("### Non-agent ML and statistical systems")
        w("")
        w("AI systems that are not LLM agent charters — the fraud scoring plane and the ML")
        w("decisioning substrate (source: `openbank-libs/governance/ml-systems.yaml`). Credit")
        w("decisioning is the only entry that becomes high-risk, and it is unbuilt.")
        w("")
        w("| System | ADR | Status | Plane | Risk class | Annex III | Basis |")
        w("|---|---|---|---|---|---|---|")
        for m in ml_systems:
            basis = " ".join(str(m.get("basis", "—")).split())
            w(f"| `{m.get('id')}` | {m.get('adr', '—')} | {m.get('status', '—')} | "
              f"{m.get('plane', '—')} | {m.get('risk_class', '—')} | {m.get('annex', '—')} | {basis} |")
        w("")
        planned = [m.get("id") for m in ml_systems
                   if m.get("risk_class") == "Planned high-risk (not deployed)"]
        if planned:
            w("> **Planned high-risk (not deployed):** "
              f"{', '.join('`'+p+'`' for p in planned)}. Inventoried in advance; the Art. 9–15")
            w("> obligations below apply the day it ships, which is why this document is ADR-0142's")
            w("> named precondition. No such system runs today.")
            w("")

    w("## Obligation coverage (Art. 9–15) for a high-risk system")
    w("")
    w("The controls below already exist and satisfy each obligation *in substance* for the day")
    w("a high-risk system (credit decisioning) ships. What is open is the article-by-article")
    w("*evidence*, not the mechanism.")
    w("")
    w("| Obligation | Existing control | Status |")
    w("|---|---|---|")
    live_high_risk = high_risk or ml_high_risk_live
    for art, control in OBLIGATIONS:
        status = "control exists; evidence pack open" if not live_high_risk else "APPLIES NOW — verify per system"
        w(f"| {art} | {control} | {status} |")
    w("")
    if live_high_risk:
        w("> ⚠ **A high-risk system is now declared and deployed** "
          f"({', '.join('`'+h+'`' for h in live_high_risk)}). "
          "Every obligation above APPLIES from 2026-08-02; this table must be reviewed per system, "
          "and ADR-0142's preconditions confirmed, before deploy.")
    else:
        w("> No high-risk system is declared today, so the obligations are pre-satisfied in")
        w("> substance and tracked here in advance. The first `HIGH-RISK` row that appears in the")
        w("> inventory above (via a new/changed charter) flips every status to APPLIES NOW.")
    w("")

    # --- LLM provider egress (Art. 10 / GDPR data governance) --------------------------------
    # Derived from `model_gateway_as_built` — the block that describes the RUNNING system (not
    # `model_gateway_target`, the undeployed decision). Prompt content that reaches a hosted
    # provider with no gateway and no sensitive-data routing leaves the platform trust boundary;
    # that is the most material open Art. 10 / GDPR exposure and must surface in this inventory,
    # not stay buried in a comment. The residency / DPA / data-licence specifics live in ADR-0175
    # (referenced, not restated here, so this section can never drift from that decision).
    mg = data.get("model_gateway_as_built") or {}
    if mg:
        providers = mg.get("providers") or {}
        hosted = [name for name, p in providers.items() if (p or {}).get("hosted")]
        w("## LLM provider egress (Art. 10 / GDPR data governance)")
        w("")
        w("The agents above call a large language model. This maps the **as-built** egress path")
        w("(`agents.yaml: model_gateway_as_built`), which governs where prompt content actually")
        w("goes today — distinct from `model_gateway_target`, the ADR-0031 D6 decision that is")
        w("not deployed.")
        w("")
        gateway = mg.get("gateway", "none")
        egress = mg.get("egress_enforced", "none")
        routed = mg.get("routed") or []
        not_routed = mg.get("not_routed") or []
        # The bearing column states what the value MEANS for the obligation, so it must track the
        # value. Hard-coding the gateway-absent prose survived the gateway being deployed once
        # already; a generated compliance doc that contradicts its own inventory row is the exact
        # failure this file exists to prevent.
        gateway_bearing = (
            "No single point to enforce residency, redaction, or an egress NetworkPolicy."
            if gateway == "none" else
            "A single in-cluster choke point exists — the place where residency, redaction and an "
            "egress NetworkPolicy CAN be enforced. Whether every caller is actually forced through "
            "it is the `Egress enforced` row, not this one."
        )
        egress_bearing = {
            "full": "Every LLM caller is network-forced through the gateway; the provider is "
                    "unreachable directly.",
            "partial": "The gateway is enforced for its own egress, but at least one caller's pod "
                       "can still reach a provider directly — the choke point is bypassable.",
        }.get(egress, "No egress policy forces callers through the gateway.")
        w("| Property | As-built value | AI Act / GDPR bearing |")
        w("|---|---|---|")
        w(f"| Gateway / egress choke point | `{gateway}` | {gateway_bearing} |")
        w(f"| Egress enforced | `{egress}` | {egress_bearing} |")
        if routed or not_routed:
            w(f"| Callers routed through the gateway | {', '.join('`'+r+'`' for r in routed) or 'none'} | "
              "Prompt content from these systems has a single auditable egress path. |")
            w(f"| Callers NOT routed | {', '.join('`'+r+'`' for r in not_routed) or 'none'} | "
              "Prompt content from these systems reaches a provider directly. |")
        w(f"| Hosted (external) provider(s) | {', '.join('`'+h+'`' for h in hosted) or 'none'} | "
          "Prompt content leaves the platform trust boundary — Art. 10 data governance applies. |")
        w(f"| Sensitive-data routing | `{mg.get('routing', 'none')}` | "
          "No routing separates sensitive from non-sensitive prompt data. |")
        w(f"| Budgets enforced at | `{mg.get('budgets_enforced_at', 'n/a')}` | "
          "Cost control only — not a data-governance control. |")
        w(f"| Fallback | `{mg.get('fallback', 'none')}` | "
          "Single provider; on failure the agent degrades to a deterministic path. |")
        w("")
        if hosted and gateway != "none" and egress != "full":
            w("> ⚠ **Partially closed gap (Art. 10 / GDPR).** A hosted external provider is in use")
            w("> behind an in-cluster gateway, so the provider credential and the egress path are")
            w("> centralised and auditable — but the gateway is not yet the *only* way out. Until")
            w("> `egress_enforced` reads `full`, a caller pod can still reach the provider directly")
            w("> and the choke point is a convention, not a control. Sensitive-data routing remains")
            w("> absent either way. Residency, DPA and the synthetic-data licence position are")
            w("> recorded in **ADR-0175**.")
            w("")
        # Closing the egress hole does NOT make a hosted provider unremarkable, and until this
        # branch existed reaching `full` emitted no warning at all — the residency / DPA /
        # synthetic-data-licence position and the ADR-0175 pointer simply vanished from the
        # document the moment the choke point was completed. That is the wrong direction for a
        # compliance artifact to move on good news: prompt content still leaves the EU, and the
        # only thing that changed is that it now provably leaves through one door.
        if hosted and gateway != "none" and egress == "full":
            w("> ⚠ **Enforced, not resolved (Art. 10 / GDPR).** Every caller is network-forced")
            w("> through the in-cluster gateway and the provider is unreachable directly, so the")
            w("> choke point is a control rather than a convention. The exposure it centralises is")
            w("> unchanged: prompt content still reaches a hosted external provider and still leaves")
            w("> the EU. Residency, DPA and the synthetic-data licence position are recorded in")
            w("> **ADR-0175**, and sensitive-data routing is reported separately above.")
            w("")
        if hosted and gateway == "none":
            w("> ⚠ **Open gap (Art. 10 / GDPR).** A hosted external provider is in use with no")
            w("> gateway and no sensitive-data routing, so prompt content egresses with no enforced")
            w("> residency or redaction control. This is the platform's most material AI-egress")
            w("> exposure and would block any high-risk (Annex III) deployment. Residency, DPA, and")
            w("> the synthetic-data licence position are recorded in **ADR-0175**; the shared egress")
            w("> seam that gives this path a single choke point landed in #2018, with the fleet")
            w("> repoint tracked as its follow-up. Until that lands, treat LLM egress as un-enforced.")
            w("")

    w("## Provenance")
    w("")
    src = AGENTS.read_bytes()
    w(f"- Source: `openbank-libs/governance/agents.yaml` "
      f"(sha256 `{hashlib.sha256(src).hexdigest()[:16]}…`, {len(agents)} charters)")
    if ml_systems:
        ml_src = ML_SYSTEMS.read_bytes()
        w(f"- Source: `openbank-libs/governance/ml-systems.yaml` "
          f"(sha256 `{hashlib.sha256(ml_src).hexdigest()[:16]}…`, {len(ml_systems)} non-agent systems)")
    w("- Related: ADR-0031 (agent governance), ADR-0084 (fraud scoring plane), ADR-0139/0140")
    w("  (ML decisioning platform), ADR-0141 (model registry), ADR-0142 (credit decisioning),")
    w("  ADR-0148 (this assurance layer).")
    w("")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(L))
    sys.stderr.write(f"wrote {OUT.relative_to(ROOT)} ({len(agents)} charters, "
                     f"{len(high_risk)} high-risk)\n")


if __name__ == "__main__":
    main()
