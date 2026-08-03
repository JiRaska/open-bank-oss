<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — governance-auditor

STRIDE/DFD threat model for the ADR-0031 Phase-4 expansion of the `governance-auditor` control-plane
agent into **money-path READ-ONLY scope**. This is an agent-side threat model required by ADR-0030 for
money-path-adjacent scope expansion.

- **Status:** Draft (Phase-4 expansion slice)
- **Last reviewed:** 2026-08-02
- **Owner:** governance CODEOWNERS
- **Related ADRs:** ADR-0030 (threat-model requirement), ADR-0031 (agent governance / D9 phase 4),
  ADR-0164 (governance-auditor)

## 1. Scope & protected assets

`governance-auditor` stays a **control-plane, proposal-only** agent. This change widens only what it may
**read** for money-path governance audit purposes:

1. **Governance metadata** — `rules.yaml`, PR review state, labels, linked-issue evidence, threat-model
   presence, merge verification metadata.
2. **Money-path config / policy metadata** — rollout annotations, policy-bundle checksums, and similar
   config facts for services named by `rules.yaml: money_path_services`.
3. **Audit metadata** — masked, non-payload audit evidence about approvals / governance checks.

Out of scope by design:

- raw transaction payloads
- account balances / journal lines / payment instruction bodies
- write, execute, merge, approve, or re-run tools

## 2. Data-flow diagram (textual)

```text
 [GitHub webhook / daily sweep]
              |
              v
   governance-auditor workflow
              |
              +--> GitHub PR metadata (reviews, labels, body, merge verification)
              |
              +--> read.governance (rules.yaml, config/policy metadata)
              |
              +--> masked audit metadata for money-path governance evidence
              |
              v
        LLM reasoning / policy gate
              |
              v
    proposal artifact only (draft.ticket / rare mechanical PR)
```

Trust boundaries crossed:

1. external GitHub metadata into the agent
2. repo / governance metadata into the reasoning loop
3. masked audit metadata into the reasoning loop
4. proposal output back into the human approval queue

No boundary in this flow authorizes a money-path write.

## 3. STRIDE analysis

| # | Element | Threat | Mitigation | Residual |
|---|---------|--------|------------|----------|
| S1 | Agent identity | **Spoofing** — a caller asserts `governance-auditor` to gain money-path-adjacent read scope | ADR-0031 D3 identity binding + deny-by-default OPA charter lookup; agent kill switch; audit on rejected assertions | Low once bound; still depends on the shared agent-identity control plane being healthy |
| I1 | Prompt/output path | **Information disclosure** — the model exfiltrates money-path metadata through its output | Scope limited to governance/config/audit metadata only; `pii: masked`; no raw transaction payloads in scope; proposal-only output; AI-attributed audit captures prompt/tool/policy decision | Masked metadata can still reveal operational cadence / service names / control posture |
| E1 | MCP tool dispatch | **Confused deputy** — prompt injection coerces a permitted tool into a money-path side effect | No write/act tools in the charter; `tools.deny` blocks write/execute tiers; proposal queue requires human disposition; OPA remains deny-by-default | A human could still approve a bad proposal; HITL reduces but does not eliminate judgment error |
| T1 | Charter evolution | **Scope creep / tampering** — future edits silently widen the charter beyond read-only | `agents.yaml` is reviewed in PR; money-path expansion requires this threat model + 2 approvals per ADR-0031/ADR-0030; OPA bundle regeneration + checksum roll keep deployed policy tied to reviewed source | Review failure is still possible; this is a governance, not cryptographic, control |
| T2 | Upstream metadata | **Tampering** — hostile PR text / labels / comments poison the audit reasoning | Agent reads evidence but still only emits a proposal; findings are reviewed by a human; audit trail preserves source inputs for re-check | False positives / noisy tickets remain possible |
| R1 | Finding disposition | **Repudiation** — nobody can prove why the agent raised or suppressed a governance finding | AI-attributed audit (`model_id`, `prompt_hash`, `tool_calls`, `policy_decision`) + recorded human approval/rejection reason | Best-effort admin-bypass detection remains a known ADR-0164 gap |
| D1 | Read scope | **Denial of service / cost blow-up** — repeated sweeps over money-path metadata create noise or cost | `tokens_per_run`, `runs_per_day`, daily sweep + webhook trigger, kill switch | Burst PR activity can still produce clustered findings, but not a money-path side effect |
| E2 | Human workflow | **Elevation of privilege** — the agent gains effective power by self-disposing its own proposal | `requires_human`, `approver_must_differ_from: author`, proposal-only semantics, no merge/approve tools | Human approver compromise is outside the agent charter boundary |

## 4. Key invariants

- `governance-auditor` remains **read-only** for money-path scope.
- It may read only **governance, config, and masked audit metadata** for `rules.yaml: money_path_services`.
- It never reads raw transaction payloads, payment bodies, or unmasked customer data.
- It never gains direct write / execute / merge / approve tools.
- Every downstream effect remains a **human-disposed proposal**.

## 5. Residual risk

The material residual risk is **metadata exfiltration rather than money movement**: masked audit/config
facts can still expose control posture, timing, service topology, or reviewer identities. Hosted-model
egress and prompt-injection risk therefore still matter even though the agent cannot act on money.
This expansion is acceptable only because the scope is metadata-only, deny-by-default, fully audited,
and kill-switchable.

## 6. Change log

- **2026-08-02** — Added the threat model for ADR-0031 D9 phase-4 expansion of `governance-auditor`
  into money-path READ-ONLY governance/config/audit metadata.
