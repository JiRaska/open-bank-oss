<!-- SPDX-License-Identifier: Apache-2.0 -->
# Prompt registry (ADR-0148)

Every system prompt an agent or the copilot uses is a **versioned file** here:

```
openbank-libs/governance/prompts/<agent-id>/<name>.<version>.md
```

- `<agent-id>` matches an `id:` in [`agents.yaml`](../agents.yaml).
- `<name>` is the prompt's role (e.g. `diagnosis`, `remediation`, `system`).
- The **`prompt_hash`** carried in every AI-attributed `AuditEvent` (ADR-0031 D5) is the
  SHA-256 of the prompt content — the same hash the `ModelGateway` computes over
  `role:content`. This registry is what makes that hash *resolvable*: given a hash from the
  audit chain, you can find the exact prompt text that produced a past agent decision.

## Why in-repo, not a SaaS prompt store

A prompt controlling a money-path-adjacent tool-call belongs inside the same git history and
tamper-evident audit chain (ADR-0086) as the code it governs — not in a third-party system
outside it. Non-money-path agents may still use an observability tool for tracing; the
registry is specifically the immutable versioned *content*, which is exactly what an EU AI
Act Art. 12 record-keeping obligation and an Art. 13 transparency obligation need.

## Rules

- **A prompt goes live only if it is registered here.** The `check-prompt-registry` guard
  (advisory first, then enforced per ADR-0144) rejects a deploy whose code embeds a system
  prompt whose content hash is not present in this tree.
- **Never edit a registered version in place** — a prompt is immutable once shipped, exactly
  like a Flyway migration. A changed prompt is a new `<name>.<version>.md`.
- The prompt text here must match, byte-for-byte, the string the service sends. The migration
  path (ADR-0148 delivery) is: the service loads its system prompt *from* this file rather
  than from an inline `const val`, so the two can never diverge.

## Coverage — every charter makes an explicit claim

[`registry.yaml`](registry.yaml) records, for **all 15** charters in `agents.yaml`, one of four
statuses. It exists because "no directory here" used to mean three completely different things at
once, all rendered as the same warning line: an identity-only principal that can never have a
prompt, a charter whose prompt lives inside a third-party image, and a charter whose LLM wiring is
simply unbuilt. **Absent and not-applicable must be distinguishable**, or the coverage number is
noise and the EU AI Act Art. 12 record-keeping claim cannot be checked.

| status | meaning | backlog? |
|---|---|---|
| `registered` | the live system prompt is in this tree, listed in `prompts:` | no |
| `pending` | will send a prompt from this repo; the LLM wiring is a stub | **yes** |
| `external` | a real model runs, but the prompt is not authored here | no — a disclosed boundary |
| `not-applicable` | the charter never causes a model call | no |

Today: **5 registered** (compliance-officer, ui-assistant, customer-copilot, devops-agent,
control-liveness-sentinel), **6 pending** (the stub ops-agents, ADR-0164–0168 + ADR-0112 P4),
**2 external** (rca-investigator → HolmesGPT's own image; ledger-domain-engineer → an operator's
coding-agent session), **2 not-applicable** (mcp-anonymous, ap2-anonymous — identity-only
principals).

| File | Source | Loaded from here? |
|---|---|---|
| `compliance-officer/oversight.v1.md` | `OversightService.systemPrompt()` | no — inline constant |
| `ui-assistant/system.v1.md` | `AgentChatService.systemPrompt()` | no — inline constant |
| `customer-copilot/system.v1.md` | `CopilotChatService.systemPrompt()` | no — inline constant |
| `devops-agent/diagnosis.v1.md` | `LlmDiagnosisAdapter.DIAGNOSIS_SYSTEM` | yes (#2240) |
| `devops-agent/remediation.v1.md` | `LlmDiagnosisAdapter.REMEDIATION_SYSTEM` | yes (#2240) |
| `control-liveness-sentinel/system.v1.md` | `LlmDiagnosisAdapter.SYSTEM_PROMPT` | superseded by v2 |
| `control-liveness-sentinel/system.v2.md` | `LlmDiagnosisAdapter.SYSTEM_PROMPT` | yes (#2321) |

The remaining three services still hold the inline constant; wiring them to load from here is the
ADR-0148 code follow-up.

A superseded file stays in the tree and in `registry.yaml`: a shipped prompt is immutable, and an
AuditEvent written while it was live carries its `prompt_hash` — delisting the file makes that hash
unresolvable, which defeats the record-keeping property the registry exists for.

### Templated prompts

The first three are built by a Kotlin `buildString` with runtime-substituted segments, so they are
registered as the **template**: `{{var}}` marks each substitution (declared in `registry.yaml` under
`placeholders:`) and trailing whitespace is normalised away. "Byte-for-byte" therefore means *after
substitution and trailing-space normalisation* — written down here so the parity check that
graduates this guard is built against the right rule instead of failing every templated prompt.

## Validation

`.github/scripts/check-prompt-registry.py` guards this tree in CI (ADR-0148, **advisory** first per
ADR-0144). It fails on structural corruption — a prompt for a non-existent charter, a name that is
not `<name>.v<N>.md`, an empty file, or a re-used version — and, since #1918, on coverage-manifest
integrity: a charter with no `registry.yaml` entry, an unknown status, an unexplained exemption, a
`registered` charter whose declared file is missing, a prompt file no entry lists, or a prompt
directory belonging to a charter that claims it has none. Only `pending` charters produce an
advisory `::warning`. Run it locally: `python3 .github/scripts/check-prompt-registry.py`.
