---
date: 2026-08-02
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, governance, compliance]
summary: "Continuous AI assurance extends ADR-0148 and ADR-0031 with an adversarial replay gate, conformity-as-code, AI-incident escalation, sensitive-data routing, and a phase-6 roadmap for the agent plane."
---

# ADR-0235 — Continuous AI assurance — adversarial gate, conformity-as-code, and beyond-phase-5 roadmap

## Context

ADR-0031 set the governing shape of the OpenBank agent plane: charters in
`openbank-libs/governance/agents.yaml`, deny-by-default MCP policy, human approval,
and AI-attributed audit. ADR-0148 then added the assurance layer around that plane:
the prompt registry under `openbank-libs/governance/prompts/`, the generated
`docs/compliance/eu-ai-act.md` mapping from `.github/scripts/gen-eu-ai-act.py`, and the
record/replay evals runner in `.github/scripts/run-evals.py`. ADR-0163 added a real
control-plane agent (`control-liveness-sentinel`) whose charter is already recorded in
`agents.yaml`, with prompt coverage in `prompts/registry.yaml` and an eval suite under
`openbank-libs/governance/evals/control-liveness-sentinel.yaml`.

That substrate is real, but it still stops one phase too early for the bank's stated
goal of auditably best-in-class AI governance. The current evals gate proves that a
registered prompt and model replay yesterday's recorded outputs, but it does not yet run
a dedicated adversarial prompt-injection and jailbreak suite per charter. The current EU
AI Act mapping is generated from `agents.yaml`, but a PR touching the agent plane can
still change a control without re-proving the Art. 9–15 obligation-to-control mapping in
the same review loop. The current incident framework (`docs/bcp/incident-response.md`)
accepts an AI agent as a declaring actor, and ADR-0216 already says serious AI incidents
must route through that path, but there is no explicit severity-classification and SLA
for agent-plane incidents. The current model-gateway reality in `agents.yaml` is also
honest: `model_gateway_as_built.routing: none`, hosted providers exist, and
`egress_enforced: partial`, so sensitive-data routing remains an open control rather than
a closed one.

Now is the point to bind those gaps into one decision. ADR-0031 D9 says the current
blast-radius model stops at phase 5. The admin UI governance snapshot currently renders
that posture from code (for example `openbank-admin-ui/src/app/api/iaops/governance/route.ts`
reports phase 2 of 5), and ADR-0148's generated mapping already treats the first
high-risk AI system as a future conformity event. Continuous assurance is therefore the
next architectural step: extend the already-shipped offline replay machinery,
re-validate conformity on every relevant PR, give AI incidents a named escalation path,
close the sensitive-data routing gap, and define what comes after phase 5 without
pretending it is already deployed.

## Decision

We will treat **continuous AI assurance** as a first-class governance layer for the agent
plane, extending ADR-0031's control model and ADR-0148's assurance machinery rather than
replacing either.

**D1 — Adversarial gate.** For every charter that is in scope for the ADR-0148 evals
registry (`openbank-libs/governance/evals/*.yaml`), CI will also replay a dedicated
**adversarial** suite covering prompt injection and jailbreak scenarios mapped to the
OWASP LLM Top 10. This is built on the **same offline record/replay runner** already
shipped in `.github/scripts/run-evals.py`: no live model in CI, no second evaluation
stack, and the same staleness rule that already blocks a prompt or model promotion when a
recording no longer matches the registered prompt hash. What changes is the charter's
required evidence set: a normal-behaviour suite alone is no longer sufficient for a
registered charter once the adversarial suite exists.

**D2 — Conformity-as-code.** Every PR touching the agent plane — at minimum
`openbank-libs/governance/agents.yaml`, `openbank-libs/governance/prompts/**`,
`openbank-libs/governance/evals/**`, `.github/scripts/run-evals.py`,
`.github/scripts/gen-eu-ai-act.py`, or the agent-plane policy/runtime wiring they depend
on — will re-validate the EU AI Act Art. 9–15 obligation-to-control mapping in CI by
extending `.github/scripts/gen-eu-ai-act.py` with a **check mode**. The release evidence
bundle defined by ADR-0029 gains a **conformity snapshot** beside the existing AI
attribution/evidence objects, so the conformity state reviewed on the PR is the same one
attached to a released artifact.

**D3 — Art. 73 serious-incident path.** The incident framework in
`docs/bcp/incident-response.md` remains the single declaration register, but agent-plane
incidents gain an explicit **AI-incident severity classification and reporting SLA** in
that runbook. `control-liveness-sentinel` is the candidate-incident raiser: when it finds
an agent-plane control whose liveness or assurance state has failed in a way that meets
the AI-incident threshold, it raises the candidate incident into the existing register
rather than inventing a parallel workflow. This extends ADR-0163's role from liveness
correlator to AI-incident trigger without granting it any direct remediation power.

**D4 — Sensitive-data routing.** Prompts carrying PII or money-path context will route to
**self-hosted models only** via the ADR-0031 gateway seam, with gateway policy enforcing
that routing and network egress making the rule real. The current `agents.yaml`
`model_gateway_as_built` block is explicit that this is **not yet true** (`routing: none`,
`egress_enforced: partial`); this decision closes that gap by making LiteLLM routing rules
plus egress enforcement the required control, while permitting cross-provider failover for
non-sensitive traffic only. Until that routing is deployed, the platform must continue to
state honestly that sensitive-data routing is open, not implied.

**D5 — Beyond-phase-5 roadmap.** ADR-0031 D9's five phases remain the current accepted
delivery model. This ADR appends a **phase 6+ continuous-assurance roadmap by reference**:
phase 6 is the point where adversarial replay, conformity snapshots, AI-incident
classification, and sensitive-data routing become the standing gate for the agent plane.
The admin UI governance snapshot will gain a curated rollout artifact at
`openbank-libs/governance/ai-rollout.yaml` **only after this ADR is Accepted**; this PR
does not create or edit that file, and it does not change the current phase count in the
running snapshot.

## Alternatives considered

- **Treat ADR-0148 as sufficient and keep adversarial evaluation as a manual exercise.**
  Rejected. The current runner, prompt registry, and generated mapping are real, but a
  manual jailbreak check is the same failure mode ADR-0148 was written to remove:
  assurance that depends on somebody remembering to run it.
- **Use a live-model red-team step in CI.** Rejected. `.github/scripts/run-evals.py`
  already states why the evals gate is record/replay: CI has no model credentials and a
  live model is non-deterministic. A second, live-only gate would undercut the existing
  assurance model instead of extending it.
- **Open a separate AI-incident register outside the existing incident framework.**
  Rejected. `docs/bcp/incident-response.md` is already the declaration source of truth,
  and ADR-0216 already routes serious AI incidents through that path. A second register
  would duplicate severity, SLA, and audit state for the same incident.
- **Declare sensitive-data routing already satisfied because a gateway exists.** Rejected.
  `agents.yaml` explicitly says the as-built state is `routing: none` and
  `egress_enforced: partial`. Calling the seam a closed control before routing and egress
  actually enforce it would repeat the exact false-control-claim defect ADR-0148 and
  ADR-0175 were written to prevent.

## Consequences

**Positive**
- The agent plane gets one continuous assurance chain: charter → prompt registry → normal
  replay → adversarial replay → conformity snapshot → release evidence.
- EU AI Act Article 9–15 evidence becomes reviewable on every relevant PR instead of only
  at release or audit time.
- The current gap between "gateway exists" and "sensitive-data routing is enforced"
  becomes an explicit delivery obligation, not an implied future intention.
- AI incidents join the existing incident register with a named severity model and SLA,
  which is more auditable than ad hoc escalation.

**Negative**
- More recordings and more generated evidence increase maintenance work for every prompt or
  model promotion on the agent plane.
- Adversarial suites can be shallow theatre if the OWASP mapping is nominal rather than tied
  to each charter's actual allowed tools and data scope; this ADR requires the gate, not the
  quality of a future weak suite.
- Sensitive-data routing remains a declared gap until self-hosted routing plus egress
  enforcement are actually deployed; this ADR does not itself close that implementation.

**Neutral**
- No service `src/main` code or release-axis version changes follow from this ADR alone.
- ADR-0031 D9 remains the accepted current-state phasing model until this ADR is itself
  accepted and its follow-up rollout artifact is added.

## Compliance impact

- PCI DSS: not applicable directly — agent-plane assurance only.
- DORA: Art. 9 and Art. 17 — continuous ICT-risk control validation and incident
  reconstruction gain explicit agent-plane evidence and escalation.
- GDPR: Art. 25 and Art. 32 — sensitive-data routing and fail-closed gateway policy are
  explicit controls on prompts carrying PII.
- PSD2: not applicable directly — the decision does not widen money-path autonomy.
- CNB: supports auditable automated-control governance and incident reporting posture for
  the agent plane.
- EU AI Act: Art. 9–15 obligation-to-control mapping is re-validated per PR; Art. 73
  serious-incident routing is bound to the existing incident framework.

## References

- ADR-0031 — AI agent governance and operations.
- ADR-0148 — AI assurance: prompt registry, evals gate, and EU AI Act mapping.
- ADR-0163 — control-liveness-sentinel AI agent.
- ADR-0216 — EU AI Act high-risk compliance for credit AI systems.
- ADR-0029 — versioning, release and governance as code (release evidence bundle).
- `openbank-libs/governance/agents.yaml`.
- `openbank-libs/governance/prompts/registry.yaml`.
- `openbank-libs/governance/evals/README.md`.
- `.github/scripts/run-evals.py`.
- `.github/scripts/gen-eu-ai-act.py`.
- `docs/bcp/incident-response.md`.
- `openbank-admin-ui/src/app/api/iaops/governance/route.ts`.
