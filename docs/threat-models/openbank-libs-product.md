<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-libs product IR (`com.openbank.libs.product`)

- **Date:** 2026-10-22
- **Status:** Lightweight STRIDE (ADR-0030 D2), required by ADR-0352 §8. A shared library, not a service: it has no endpoint, no credential and no datastore of its own. It is **money-path by consequence** — every consumer that adopts it (billing, interest, account; ADR-0352 phase 3) posts what it returns.
- **Purpose:** Parse, validate, hash and execute a product revision's behaviour (IR v1, deposit products) as a pure function from (revision, state, event, clock) to a list of instructions.

## 1. Scope & purpose

The package holds the IR v1 model (`ProductRevisionIr`), the strict parser and validator
(`ProductIrParser`, `IrJson`), the canonical form and `revisionHash` (`ProductIrCanonical`), the
predicate evaluator (`PredicateEvaluator`) and the interpreter (`ProductInterpreter`). Interest and
penalty arithmetic is delegated through the `ProductArithmetic` port to `FinanceProductArithmetic`
in `openbank-libs-finance`. Phase 1 ships the library and its gates only: no consumer evaluates a
revision yet, and the signing and the pinned-revision loader are phase 2 (ADR-0352 §4, §5).

## 2. Data flow (DFD)

```
[openbank-products (git, PR review)] --IR JSON--> [product-catalog publish] --sign revisionHash (phase 2)-->
        [signed revision copy, keyed by revisionHash] --verify--> [consumer]
                                                                      |
            (revision, state snapshot, event, clock) ------------------> [ProductInterpreter]  (pure)
                                                                      |
                                       List<Instruction> <------------+
            consumer: assert Post balanced per currency --> ledger posting / reject / schedule / notify
                                        \--(phase 3)--> shadow comparator (async, off the posting path)
```

- **External entities:** the author of an IR revision (human through a PR; an AI agent may only propose, ADR-0259), the publish path, the consumers.
- **Trust boundaries:** IR text into `ProductIrParser` (untrusted until validated and its signature verified); `revisionHash` into the signature (phase 2); instructions out of the interpreter into a consumer's ledger call; shadow comparator output into metrics (phase 3).
- **Assets:** the correctness of every posted amount under a product; the signing key; the integrity of the hash boundary; customer state snapshots passed into the interpreter.

## 3. Authn/Authz

- The library performs no authentication: it is called in-process by a service that has already authenticated its own caller.
- **Publication authority is not here.** Whoever can produce a valid signature over a `revisionHash` changes what every account under that product does (ADR-0352 §4). The signing capability is granted to the publish path only — never to a developer, an agent or a service account — and publication needs both PR review and Studio role approval (ADR-0352 §7). Phase 1 ships no signing code.
- Purity is enforced, not documented: `ProductIrPurityRule` (detekt, gate `product-ir-purity`, enforced) forbids I/O, ambient clock, logging and metrics under the package.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | An unsigned or self-signed revision is presented to a consumer as published | Phase 2: the loader verifies a detached signature over `revisionHash` before evaluation and refuses an unsigned or mis-signed revision (ADR-0352 §4). Phase 1: no consumer loads a revision, so there is no path to spoof |
| **T**ampering | Two serialisations of one revision hash differently, so an approved hash and the executed bytes diverge (e.g. `0.025` vs `0.0250`, reordered keys) | `ProductIrCanonical` hashes the canonical form: keys in RFC 8785 order via `CanonicalJson`, no whitespace, every decimal a fixed-scale string; `ProductIrCanonicalTest` proves both authorings hash identically and a changed `ruleId` does not; a decimal more precise than its canonical scale is rejected by the parser rather than rounded into the hash |
| **T**ampering | Signing-key compromise: an attacker signs a revision of their choosing | Key held in OpenBao Transit, sign capability on the publish path only, rotation per ADR-0172 (phase 2). Residual: a compromised key is a full product compromise by design; see §5 |
| **T**ampering / **D**oS | A malicious IR **within** schema limits: an author fills every limit to make evaluation slow or fan out | Total by construction: no loops, no recursion; a same-cycle `Schedule` may not target its own hook and the hook graph must be acyclic (`ProductIrParser`); tiers ≤ 16, fees ≤ 32, rules per hook ≤ 32, predicate depth ≤ 8 (schema + parser + gate `product-ir-run-limits`); at runtime more than 64 instructions throws `ProductEvaluationException` instead of returning; p99 < 2 ms per evaluation is measured by the product interpreter JMH benchmark in openbank-libs-benchmarks |
| **T**ampering | An outflow labelled with a kind other than WITHDRAWAL avoids the early-withdrawal penalty, or a later `prePosting` rule rejects a posting whose penalty an earlier rule already emitted | The penalty applies to any DEBIT posting before maturity, whatever its kind; a rejected evaluation returns only its `Reject` instructions, so no earlier `Post` survives (`ProductInterpreterTest`) |
| **T**ampering | A valid revision with wrong economics (a rate, a waiver, an unbalanced booking) | `Instruction.Post.isBalancedPerCurrency()` is checked by the interpreter before it returns and again by the consumer before the ledger call (ADR-0352 §8); wrong-but-balanced economics is what the mandatory behavioural simulation and the business approval exist to catch (phases 4–5) — not a control this library can provide |
| **R**epudiation | A posting cannot be traced to the rule and revision that caused it | Every instruction carries `ruleId`, `revisionHash` and `irVersion`; `ruleId` is an explicit identifier unique within the revision, never an index, so inserting a rule cannot rename the audit trail |
| **I**nfo disclosure | The shadow comparator as an information channel: per-rule divergence metrics or logs leak account-level amounts | Phase 3 design constraint: the comparator runs asynchronously off the posting transaction and emits counters keyed by product and `ruleId` only (`openbank_product_shadow_compared_total`, `openbank_product_shadow_diverged_total`), never by account or amount (ADR-0077/0079 cardinality rules); divergence detail stays in the service's own store |
| **I**nfo disclosure | The interpreter logs or exports the state snapshot it is given | The package has no logger and no metrics (enforced by `ProductIrPurityRule`); a consumer decides what to record |
| **E**oP | An AI agent publishes executable behaviour | ADR-0259 boundary kept: agents propose IR as a PR and never sign, approve or publish; `product.publish` does not exist as an MCP tool (ADR-0352 §12) |

## 5. Residual risks / assumptions

- **The signing key is a security boundary by design.** A valid signature is authority over every account under a product. Mitigation is custody (Transit, publish-path-only capability, rotation), not code in this library.
- **Valid-but-wrong economics** pass every check here; only simulation and two-party approval stand in front of them.
- **Phase 1 has no signature verification.** Nothing loads a revision outside tests until phase 2's loader exists; adopting the interpreter in a consumer before that loader is a violation of ADR-0352 §4, not a gap this document accepts.
- **The canonical hash depends on `CanonicalJson` staying stable.** A change to its key order or string escaping changes every `revisionHash`; it is shared with the audit chain, whose own tests pin it.

## 6. Change log

- **2026-10-22** — Initial model for ADR-0352 phase 1: IR v1 parser, canonical hash, pure interpreter, finance primitives, golden runner; gates `product-ir-determinism` (advisory), `product-ir-run-limits` (advisory), `product-ir-purity` (enforced).
