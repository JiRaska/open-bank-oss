---
date: 2026-11-14
decision-status: proposed
delivery-status: partial
followup: "#16163 — evidence age (test/attestation kinds, max_evidence_age) and the read-only MCP/AI readers remain"
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [compliance, admin-ui, audit, regulatory-reporting]
summary: "/docs/compliance stops being a hand-kept list: it renders one derived trace obligation → control → evidence with freshness, status computed by code; AI and MCP may read and explain the trace with citations, never set a status."
---

# ADR-0379 — Compliance posture is derived from one obligation-to-control-to-evidence trace; AI explains it, never decides it

## Context

ADR-0367 decided that regulatory *returns* start from the law: `openbank-libs/governance/regulatory-obligations.yaml`
holds 70 sourced obligations, each mapped onto the reports that cover it, enforced by gate
`regulatory-obligations-register`. ADR-0029 and ADR-0071 decided that governance surfaces are derived
data, and ADR-0253 that a control must be able to show it carried work. None of them decides what the
admin-ui *compliance posture* page is, and today three surfaces answer overlapping halves of that question
with nothing joining them:

- `/docs/compliance` renders a hand-written `COMPLIANCE_AREAS` literal of 50 requirements in seven areas
  (PSD2, AML, KYC, GDPR, EBA ICT, ČNB, FATCA/CRS). After #2370 found it wrong in both directions and #2667
  corrected it by hand, every row now reads `unknown` with the same note. The page is honest and carries
  no information: a compliance officer, another department or an auditor learns nothing from it.
- `/docs/control-tower` renders `openbank-libs/governance/compliance-controls.yaml` (25 controls, mapped
  to frameworks, with an authored status and an optional derived signal).
- `/regulatory/obligations` renders the ADR-0367 register.

The obligation register says *what the law requires*, the control catalogue says *what the platform does*,
and the CI gate estate and test evidence say *whether it demonstrably did it*. No artefact links the three.
So an obligation cannot point at the control that satisfies it, a control cannot say how fresh its evidence
is, and an auditor cannot follow one chain from a legal citation to a run. The enforced gate
`compliance-matrix` (`check-compliance-matrix.py`) parses the hand-written literal. It keeps that literal from
lying about evidence, and in doing so it also keeps the literal in place.

The question of adding AI or MCP to the page is open. ADR-0031, ADR-0224 and ADR-0225 already provide a
governed MCP channel (OPA-gated, OBO for staff, policy-filtered `tools/list`), and ADR-0367 already sets the
pattern for regulatory AI: it explains and drafts, it never files.

## Decision

We will make compliance posture a **derived trace**, and `/docs/compliance` its single reader-facing surface.

1. **One join, three authored sources, nothing new hand-kept.** Each obligation in
   `regulatory-obligations.yaml` gains an optional `controls: [<control-id>]` list, and each control in
   `compliance-controls.yaml` gains `evidence_refs: [{kind, ref}]` (the existing `evidence` field stays the prose
   description), where `kind` ∈ `gate | workflow | test | adr | path` and `ref` must resolve: a `gates.yaml` id, a file
   under `.github/workflows/`, a test file under a test source set, an ADR number, or an existing repo path.
   An `attestation` kind waits until some source records when an attestation was last verified. No new generated file is added: the admin-ui joins three
   artefacts the image already bakes (`compliance-controls.yaml`, `regulatory-obligations.json`,
   `gate-catalog.json`) in one pure function (`src/lib/compliance/trace.ts`), so the page, the export and any MCP
   tool compute the same verdict. The hand-written `COMPLIANCE_AREAS` literal is deleted.
   Its rows either become obligations in the register, carrying their legal source, or they are dropped. A
   field name on a customer record is not an obligation.

2. **Status is computed, never authored and never AI-produced.** Every row shows the *declared* status (what the
   YAML says) next to the *strength* of its evidence: a blocking gate, a CI run (an advisory gate or a workflow),
   documentation only (an ADR or a path), or none. A positive claim (`enforced`, `partial`) without at least a CI
   run behind it renders as `unverified`, even when the YAML says `enforced`. Weak claims (`audit`, `planned`) stand
   as declared, because they already describe themselves honestly. Phase 2 adds evidence *age*: a control whose
   newest successful run is older than its declared `max_evidence_age_days` will also render `unverified`, and so
   will one whose age cannot be read, because an unobservable age is not a fresh one. This is
   ADR-0253 applied to the page itself.

3. **Three views over the same data, not three datasets.**
   - *Compliance:* gaps first (obligations with no control, `missing` or `bff-only` coverage, `unverified`
     cadence), each with its owner and next deadline.
   - *Other departments:* filter by business domain (ADR-0364 taxonomy) or service, with a plain-language line
     for each control.
   - *Audit:* the full chain from legal source to evidence ref, an as-of snapshot pinned to the image's commit SHA,
     and an export (print/PDF plus the raw JSON) that can serve as an evidence-pack exhibit.
   `/docs/control-tower` and `/regulatory/obligations` remain the detail pages, and the hub links into them.

4. **AI and MCP are readers of the trace, never authors of it.** We expose read-only MCP tools over
   the same trace (`/api/compliance/trace`): `compliance.list_obligations`, `compliance.get_trace`, `compliance.list_gaps`,
   `compliance.get_evidence`. They follow the existing ADR-0034/0224/0225 authorization path, so `tools/list`
   is policy-filtered and staff act under their own roles via OBO. An in-page assistant may answer questions
   such as "what changed since the last snapshot" or "explain this gap to the business". It may do so only
   when the answer cites trace row ids, and an answer that cites no row is not rendered. No tool can write a
   status, an obligation or a control. Those change only through a reviewed PR to the YAML.

## Alternatives considered

- **Keep the editorial page, protected by `compliance-matrix` (status quo).** Cheapest, and honest after #2667.
  Rejected: the honesty was bought by emptying the page (every row `unknown`), the literal duplicates topics the
  register already sources from law, and a gate that keeps a hand-kept list truthful is permanent maintenance on
  the wrong artefact.
- **Delete `/docs/compliance` and point readers at control-tower plus obligations.** Removes the duplicate at no
  cost. Rejected: no page would answer the auditor's question, which requires the join. Two lists that each
  omit the other half do not add up to a trace.
- **Adopt an external GRC tool and sync into it.** Mature workflow and familiar to auditors. Rejected for now:
  it creates a second source of truth outside the reviewed repo (contrary to ADR-0029) and puts governance data
  with a third party (ADR-0174 register entry, exit plan). It also still needs the same evidence join to be
  computed here first. The JSON export in decision 3 keeps this option open later.
- **Let an AI agent assess and assign compliance status.** It would fill the 50 `unknown` rows quickly.
  Rejected: an auditor cannot accept a status no deterministic rule reproduces, and it contradicts the ADR-0367
  stance on regulatory AI.

## Consequences

**Positive**
- An obligation without a control, or a control with stale evidence, becomes a visible row with an owner rather
  than an absence.
- Compliance, other departments and audit see the same numbers. The export is reproducible from a commit SHA.
- MCP reuses the existing governed channel, so it adds no new authorization surface.

**Negative**
- Two governance YAMLs gain fields that must be kept true, and an `evidence` ref that stops resolving fails CI.
- Freshness requires a source of evidence-run timestamps. Gate runs are collected already (ADR-0255 snapshot), but
  test and attestation timestamps are not. Until they are, those evidence kinds render `age: unknown` and the
  verdict stays `unverified`.
- Retiring `compliance-matrix` must happen in the same PR that deletes the literal, or the enforced gate fails.

**Neutral**
- The ADR-0367 register remains the source for returns, and this ADR only adds the `controls:` edge.

### Enforcement

New gate `compliance-trace` (`.github/scripts/check-compliance-trace.py`, with `--self-test`):
- every control declares at least one `evidence_refs` entry, and every ref resolves to an existing artefact;
- every `controls:` id on an obligation resolves to the catalogue;
- `/docs/compliance/page.tsx` contains no requirement literal;
- both YAML files load with duplicate keys rejected.
An obligation with no control is not a CI failure. Most reporting duties have no security control to point at,
so the hub shows them as gaps instead. The gate lands `enforced` directly: every rule is mechanical and green on
the tree that introduces it, so an advisory phase would only make a broken edge mergeable. `compliance-matrix` and
`compliance-page-evidence`, which both read `COMPLIANCE_AREAS`, are retired in the same PR. The MCP tools need
no new gate: the existing OPA bundle and the `tools/list` filter cover them, and their charter entries go through the
`agents.yaml` derived tails.

### Delivery check

```bash
grep -c 'COMPLIANCE_AREAS' openbank-admin-ui/src/app/docs/compliance/page.tsx      # 0
python3 -c "import yaml;g=yaml.safe_load(open('.github/gates/gates.yaml'));print([x['mode'] for x in g['gates'] if x['id']=='compliance-trace'])"   # ['enforced']
grep -c '^  - id: compliance-matrix$' .github/gates/gates.yaml                    # 0
python3 .github/scripts/check-compliance-trace.py --enforce; echo $?             # 0
```
Runtime check: `tools/list` on the MCP endpoint, called as a compliance-role principal, includes
`compliance.get_trace`, and the same call as an anonymous principal does not.

### Phases

1. Add the trace fields, the join and the gate, and rebuild `/docs/compliance` as the derived hub with the three
   views and the print/JSON export. Drop the literal and retire `compliance-matrix` and `compliance-page-evidence`.
2. Add the `test` evidence kind and `max_evidence_age_days` per control, aged by the newest passing gate run that
   the gate-health collector records (`lastOk`). Give `sanctions-screening` and `fraud-monitoring` machine evidence.
3. Add the MCP read tools and the cited-answer assistant, through a charter change in `agents.yaml`.

## Compliance impact

- PCI DSS: not applicable. No cardholder-data flow changes, and the page reads governance YAML only.
- DORA: supports the ICT risk-management evidence the platform already maps (ADR-0134) by making control evidence
  and its age visible. It adds no new control.
- GDPR: not applicable to data processing. The trace contains no personal data, only references to controls and
  artefacts.
- PSD2: not applicable. It changes no payment or access-to-account behaviour, although PSD2 obligations appear in the trace.
- CNB: improves how supervisory obligations are presented to an auditor (ADR-0367 register). It changes no filing.

## References

- ADR-0029, ADR-0071: governance as code, derived manifests
- ADR-0253: evidence of effect
- ADR-0255: CI gate observability snapshot (the source of gate-run freshness)
- ADR-0367: regulatory reporting as code (the obligation register)
- ADR-0031, ADR-0034, ADR-0224, ADR-0225: governed agents, OPA-gated and policy-filtered MCP
- ADR-0144: gate graduation deadlines
- #2370, #2667, #3240: the hand-kept compliance literal and its gate
