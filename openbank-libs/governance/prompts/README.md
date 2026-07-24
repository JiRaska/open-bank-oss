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

## Current contents

Two real ops-agent prompts are registered as the first migration (the services still hold the
inline constant today; wiring them to load from here is the ADR-0148 code follow-up):

| File | Source (to be replaced by a registry load) |
|---|---|
| `devops-agent/diagnosis.v1.md` | `LlmDiagnosisAdapter.DIAGNOSIS_SYSTEM` |
| `devops-agent/remediation.v1.md` | `LlmDiagnosisAdapter.REMEDIATION_SYSTEM` |
| `control-liveness-sentinel/system.v1.md` | `LlmDiagnosisAdapter.SYSTEM_PROMPT` |

The remaining agents (agent-service, copilot-service, and the LiteLLM-stub ops-agents) register
their prompts as their real LLM wiring lands (ADR-0164–0168, ADR-0174 gateway).

## Validation

`.github/scripts/check-prompt-registry.py` guards this tree in CI (ADR-0148, **advisory** first per
ADR-0144). It fails on structural corruption — a prompt for a non-existent charter, a name that is
not `<name>.v<N>.md`, an empty file, or a re-used version — and emits an advisory `::warning` listing
charters not yet migrated. Run it locally: `python3 .github/scripts/check-prompt-registry.py`.
