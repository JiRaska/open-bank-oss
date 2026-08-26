# AI agents for regulatory reporting — design proposal

Status: proposal, not an architectural decision. Any implementation requires an ADR, threat model,
model-risk review, and explicit data-classification approval.

## Boundary that must not move

FINREP/COREP values remain deterministic Kotlin mappings over the ledger's immutable `FROZEN /
LINES_V1` evidence. An AI model must never calculate or overwrite a cell, create or freeze an
accounting period, post a journal, approve a close, classify a data gap as resolved, or submit a
return. A model outage must have zero effect on report generation.

Every AI result is advisory and must cite immutable inputs: close content hash, reporting date,
template/cell coordinates, source account codes, lineage edges, and the versioned rule/taxonomy
material used. A result without resolvable citations is rejected before persistence.

## Proposed agents

1. **Evidence-readiness agent** — assembles a pre-render checklist from closed-period status,
   evidence state, balance verdicts, data-gap flags, lineage freshness and control outcomes. It
   explains exactly why a period is or is not reportable; it cannot waive a failed gate.
2. **Variance-investigation agent** — compares two already-rendered periods, ranks material cell
   movements and traces them back to account groups and source services. It produces hypotheses and
   investigation queries, never a replacement value.
3. **Taxonomy-change agent** — monitors versioned EBA/ČNB publications, proposes a machine-readable
   mapping diff and tests affected templates against frozen fixtures. A human regulatory owner must
   approve the source document, interpretation and code change.
4. **Review-pack copilot** — generates an operator narrative from deterministic facts: period/hash,
   completeness gates, movements, known gaps, controls and approvals. Each sentence carries evidence
   references; the operator edits and signs the final pack.

The first useful delivery should be agents 1 and 2. They use only internal structured evidence and
do not require autonomous web access. Taxonomy monitoring is valuable later but has a larger prompt-
injection, copyright, provenance and change-management surface.

## Runtime shape

```text
ledger FROZEN evidence ─┐
finrep rendered cells ──┼─> deterministic evidence snapshot + hash ─> agent read tools
lineage/control state ──┘                                      │
                                                               v
                                            structured findings with citations
                                                               │
                                                               v
                                             human review in Admin UI
```

- Give agents narrow read-only tools returning typed DTOs; never SQL, arbitrary HTTP, generic MCP,
  or write-capable service credentials.
- Snapshot all inputs before inference and bind findings to the snapshot hash. Re-running later is a
  new analysis, not a mutation of history.
- Persist prompt/model/tool versions, citations, token cost, latency and reviewer disposition. Do
  not persist unrestricted chain-of-thought.
- Redact or aggregate customer-level data before inference. The initial agents need GL/account-group
  facts, not natural-person PII.
- Treat retrieved documents as untrusted data. Taxonomy instructions cannot grant tools or alter
  system policy.
- Use maker/checker review for any finding promoted into a regulatory work item. Submission remains
  a separate deterministic workflow with its own authorization.

## Evaluation and release gates

Use a frozen golden set covering normal periods, material movements, unbalanced inputs, missing
evidence, legacy `HASH_ONLY`, explicit COREP gaps and adversarial retrieved text. Required gates:

- 100% citation validity and zero invented account/cell identifiers;
- 100% refusal to recommend export/submission when deterministic readiness fails;
- deterministic arithmetic independently recomputed outside the model;
- measured precision/recall for material variance ranking, split by template;
- no PII leakage and no tool call outside the allow-list;
- shadow mode first, then read-only operator preview; no autonomous actuation phase.

The stop condition for the first increment is an evidence-readiness panel whose every statement can
be clicked through to a deterministic source, plus a variance report that never changes report data.
