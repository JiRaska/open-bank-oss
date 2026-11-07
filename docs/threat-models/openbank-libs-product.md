<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-libs product IR (`com.openbank.libs.product`)

- **Date:** 2026-10-23 (phase 2 update; initial 2026-10-22)
- **Status:** Lightweight STRIDE (ADR-0030 D2), required by ADR-0352 §8. A shared library, not a service: it has no endpoint, no credential and no datastore of its own. It is **money-path by consequence** — every consumer that adopts it (billing, interest, account; ADR-0352 phase 3) posts what it returns.
- **Purpose:** Parse, validate, hash and execute a product revision's behaviour (IR v1, deposit products) as a pure function from (revision, state, event, clock) to a list of instructions.

## 1. Scope & purpose

The package holds the IR v1 model (`ProductRevisionIr`), the strict parser and validator
(`ProductIrParser`, `IrJson`), the canonical form and `revisionHash` (`ProductIrCanonical`), the
predicate evaluator (`PredicateEvaluator`) and the interpreter (`ProductInterpreter`). Interest and
penalty arithmetic is delegated through the `ProductArithmetic` port to `FinanceProductArithmetic`
in `openbank-libs-finance`. Phase 2 adds the signing path (`com.openbank.libs.productrevision`:
`IrSignerPort` with an OpenBao Transit `sign`/`verify` adapter and a dev/test HMAC adapter, wired
by product-catalog's `publish-ir`) and the pinned-revision loader (`PinnedRevisionLoader`, local
immutable cache keyed by the pinned `RevisionIdentity`, signature verified against the pin before a revision is returned). No
consumer evaluates a revision yet (phase 3).

## 2. Data flow (DFD)

```
[openbank-products (git, PR review)] --IR JSON--> [product-catalog publish] --sign RevisionIdentity envelope (phase 2)-->
        [signed revision copy, keyed by pin] --verify against pin--> [consumer]
                                                                      |
            (revision, state snapshot, event, clock) ------------------> [ProductInterpreter]  (pure)
                                                                      |
                                       List<Instruction> <------------+
            consumer: assert Post balanced per currency --> ledger posting / reject / schedule / notify
                                        \--(phase 3)--> shadow comparator (async, off the posting path)
```

- **External entities:** the author of an IR revision (human through a PR; an AI agent may only propose, ADR-0259), the publish path, the consumers.
- **Trust boundaries:** IR text into `ProductIrParser` (untrusted until validated and its signature verified); the revision identity envelope `(productCode, revisionId, revisionNumber, irVersion, revisionHash)` into the signature (phase 2); instructions out of the interpreter into a consumer's ledger call; shadow comparator output into metrics (phase 3).
- **Assets:** the correctness of every posted amount under a product; the signing key; the integrity of the hash boundary; customer state snapshots passed into the interpreter.

## 3. Authn/Authz

- The library performs no authentication: it is called in-process by a service that has already authenticated its own caller.
- **The hash boundary is an identity envelope, not a bare hash.** The publish-path signature covers `RevisionIdentity.signingInput()`: canonical JSON (`CanonicalJson`, RFC 8785 key order) of `{domain: "openbank.product-revision-signature.v1", irVersion, productCode, revisionHash, revisionId, revisionNumber}`. `revisionHash` alone covers the IR (and so `productCode`/`irVersion`) but not which catalog revision it is: two revisions with identical behaviour share one hash, and a hash-only signature for product A or revision 1 verified wherever A's hash or revision 1's bytes were presented. A consumer pins all five fields; `PinnedRevisionLoader.load(pin)` verifies the signature against the PIN's envelope (never the copy's claim), and before that requires the copy's claimed identity and the parsed IR's `productCode`/`irVersion` to equal the pin — a substitution is `identity_mismatch`, fail closed. The domain tag means a signature over any other document, including the pre-envelope bare-hash form, can never verify. No compatibility path: no environment ever ran `mode` other than `disabled`, so no bare-hash signature exists outside tests.
- **Publication authority is not here.** Whoever can produce a valid signature over a revision identity changes what every account under that product does (ADR-0352 §4). The signing capability is granted to the publish path only — never to a developer, an agent or a service account — and publication needs both PR review and Studio role approval (ADR-0352 §7). Phase 1 ships no signing code.
- Purity is enforced, not documented: `ProductIrPurityRule` (detekt, gate `product-ir-purity`, enforced) forbids I/O, ambient clock, logging and metrics under the package. The signing and loading code lives in a separate package (`com.openbank.libs.productrevision`, openbank-libs-runtime) precisely because it does I/O and emits metrics.
- **Signing key custody (phase 2).** The Transit key is dedicated (`product-ir-publish` by default) and asymmetric; product-catalog's OpenBao role is granted `update` on `transit/sign/<key>` only, a consumer's role `update` on `transit/verify/<key>` only, and nobody `create` on `transit/keys` (a mistyped key name would otherwise upsert a fresh key and sign under it). The HMAC adapter is a shared secret and therefore proves nothing about WHO signed. The default mode is `disabled` (publish-ir answers 409, nothing is signed); `hmac` is set only by the `%dev`/`%test` profiles, its secret has no default anywhere else, and `IrSigningModeGuard` (`@Startup`) REFUSES TO BOOT a production launch configured with it. A deployment sets `mode=openbao` once the Transit key and role exist.
- **Publishing an IR-bearing revision is its own four-eyes action**, `catalog.publish-ir` (`rules.yaml: four_eyes.actions`, `rest.rego catalog-publish-ir`: HUMAN + operator/admin, never a service account), distinct from `catalog.publish`, and the plain publish refuses a revision that carries IR (409) so no IR revision is ever published unsigned. Because product-catalog runs `AUTHZ_ENFORCE=false`, the rego rule is advisory there; the binding control is in the service, which refuses `publish-ir` to any identity `ServiceAccountIdentity.isHumanStaff` does not positively classify as interactive staff: a verified JWT from an interactive client, with a subject and a non-service-account username (fails closed). `IrPublishContractTest` holds a service account with operator+admin, and an identity carrying none of that evidence, to 403.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | An unsigned or self-signed revision is presented to a consumer as published | `PinnedRevisionLoader` verifies the detached signature over the pinned identity envelope through `IrSignatureVerifierPort` before returning a revision and refuses a missing copy, an invalid document, a copy whose canonical hash is not the hash it was filed under, and a mis-signed copy — each as its own `openbank_product_revision_load_total{outcome}` value, never shared with success (`PinnedRevisionLoaderTest`). A verifier that cannot answer (OpenBao down) is `verifier_unavailable`, not a pass |
| **S**poofing | A forged signature under a different key id or algorithm is presented with a valid-looking hash | Both adapters refuse a signature whose `keyId` or `algorithm` is not theirs before any cryptographic check; the Transit adapter never calls OpenBao for a malformed signature string (`OpenBaoTransitIrSignerTest`) |
| **S**poofing | A valid signature for product A, or for another revision id or number of the same IR, is served where product B's (or revision N's) pin is expected | The signature binds `(productCode, revisionId, revisionNumber, irVersion, revisionHash)` and is verified against the consumer's pin; a copy naming another identity, or an IR whose `productCode`/`irVersion` differ from the pin, is `identity_mismatch`; a copy relabelled to the pin with the other revision's signature is `signature_invalid` (`PinnedRevisionLoaderTest`: product A under B's pin, revisionId swap, revisionNumber swap, IR-version swap, bare-hash signature) |
| **T**ampering | The signed copy's bytes are edited after signing (a rate changed in the local copy) | The loader re-derives the canonical hash of the parsed copy and requires it to equal the requested hash before verifying the signature; an edited copy is `hash_mismatch` whatever its signature says |
| **T**ampering / **E**oP | An IR-bearing revision is published through the plain `catalog.publish`, bypassing the signature | `GenericCatalogService.publish` refuses a revision with `behaviour` (409); only `publishIr` signs and persists, in the same transaction, into `catalog_revision_ir_signatures`, whose rows are immutable by trigger (V16) — `IrPublishContractTest` holds all three |
| **R**epudiation | A signature exists but nobody can say which key version produced it, so rotation (ADR-0172) orphans older revisions | Transit's own `vault:v<N>:` signature string is stored verbatim with `keyId`, `algorithm` and `signedAt`, so a rotated key keeps verifying older revisions by their version |
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
- **The HMAC adapter is a dev/test shape, not a weaker production option.** Anyone who can verify can also sign. It exists so the publish path works with no OpenBao on a laptop and in CI; a deployment that leaves `mode=hmac` has a boot-time warning and no signing authority worth the name. The gitops env must set `mode=openbao` before any consumer pins a revision (phase 3 precondition).
- **No consumer loads a revision yet.** `PinnedRevisionLoader` ships unwired (phase 2); each phase-3 consumer supplies its own `SignedRevisionSource` (a local table or file set, never a live catalog call) and a verify-only Transit identity. Adopting the interpreter in a consumer without the loader is a violation of ADR-0352 §4, not a gap this document accepts.
- **The canonical hash depends on `CanonicalJson` staying stable.** A change to its key order or string escaping changes every `revisionHash`; it is shared with the audit chain, whose own tests pin it.

## 6. Change log

- **2026-10-24** — Revision identity binding: the signature covers a domain-tagged canonical envelope of `(productCode, revisionId, revisionNumber, irVersion, revisionHash)` instead of the bare hash; the loader loads by full pin and fails closed with `identity_mismatch`. Row added: cross-product / cross-revision signature substitution.
- **2026-10-23** — Phase 2: signing in product-catalog `publish-ir` (`IrSignerPort`, OpenBao Transit + dev HMAC adapters, `catalog_revision_ir_signatures`, four-eyes action `catalog.publish-ir`), `PinnedRevisionLoader` in openbank-libs-runtime, `openbank-products` (DSL, three reference products, goldens, `obp`); gates `product-ir-determinism` and `product-ir-run-limits` flipped to enforced, `product-ir-authoring-parity` added (advisory). Rows added: signing-key custody, forged key id, edited copy, unsigned-publish bypass, key-version repudiation.
- **2026-10-22** — Initial model for ADR-0352 phase 1: IR v1 parser, canonical hash, pure interpreter, finance primitives, golden runner; gates `product-ir-determinism` (advisory), `product-ir-run-limits` (advisory), `product-ir-purity` (enforced).
