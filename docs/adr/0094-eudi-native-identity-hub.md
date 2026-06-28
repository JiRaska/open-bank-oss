# 94. EUDI-native identity hub — eIDAS 2.0 wallet onboarding, probabilistic record linkage, and durable orchestration

Date: 2026-06-15
Status: Accepted
Delivery-Status: Partial
Author(s): OpenBank platform

## Context

ADR-0072 made `pid-service` the identity golden record and defined a three-tier dedup
(deterministic RČ blind index → conservative normalized candidate match → four-eyes manual
case). ADR-0068/0069 defined the onboarding cockpit and journey. Those decisions answer
*"is this applicant already a customer?"* and *"how does the funnel move?"* — and they hold.
This ADR does **not** revisit them; it extends the identity stack along three vectors that are
now both regulatorily forced and commercially differentiating, and it keeps every ADR-0072
invariant (pid is the sole authority, plaintext RČ never leaves pid-service, probabilistic
matching is never an auto-merge authority, customer responses stay neutral, ambiguity goes to
four-eyes, new surfaces ship in OPA enforce mode, audit is hash-chained).

Three forces:

1. **eIDAS 2.0 is in force and the EUDI Wallet is arriving.** Regulation (EU) 2024/1183
   amended the eIDAS Regulation (910/2014); the European Digital Identity (EUDI) Wallet and
   its Architecture Reference Framework (ARF) define a **Person Identification Data (PID)**
   credential and **(Qualified) Electronic Attestation of Attributes ((Q)EAA)**, exchanged
   over **OpenID for Verifiable Credentials** — `OpenID4VP` (presentation) and `OpenID4VCI`
   (issuance) — in `SD-JWT VC` and ISO/IEC 18013-5 `mdoc` formats. Member States must offer
   wallets to citizens and **relying parties in regulated sectors (banking explicitly named)
   must accept them** within the rollout window. A bank that accepts an EUDI PID gets an
   eIDAS-High identity assertion with *selective disclosure* and no document-scan step — the
   single highest-assurance, lowest-friction onboarding path available. Our service is
   literally `pid-service` (Party Identification Data); the conceptual fit with EUDI PID is
   exact, and we are positioned to be an early acceptor in CZ.

2. **The no-RČ tier is structurally weak and self-service is about to open.** ADR-0072
   deliberately shipped a *conservative exact-normalized-tuple* match for applicants without a
   Czech RČ (foreign nationals, residence-permit holders) and explicitly deferred probabilistic
   linkage as a future *additional candidate source, never an auto-merge authority*. Exact
   tuples miss real duplicates (transliteration of non-Latin names, `Müller`/`Mueller`,
   swapped given/family order, date-of-birth typos, address drift) and over-fire on genuine
   namesakes. With ADR-0069 Phase 2 (self-service) removing the operator who implicitly catches
   "didn't we onboard this person?", the candidate-generation recall must improve **before**
   that gate opens — without ever auto-merging.

3. **Onboarding orchestration is a bespoke state machine carrying money-path side effects.**
   The journey spans long external waits (KYC provider callbacks, sanctions/PEP screening gate
   ADR-0032, four-eyes adjudication ADR-0068, Keycloak user + SCA enrolment) with
   compensations (a failed screening must unwind a provisionally created party/relationship).
   A hand-rolled state machine in `onboarding-service` makes retries, idempotency, timeouts,
   and compensation ad-hoc and hard to audit — exactly the properties DORA Art. 17 wants
   reconstructable.

We are also, separately, an attractive **issuer**: once a customer is verified and holds an
IBAN, the bank can attest facts the customer reuses elsewhere — proof-of-IBAN, age-over-18,
residency, account-holder status — pushed into their wallet as a (Q)EAA and consumed by third
parties (including our own PSD2 XS2A TPP flows, ADR-0090/0093).

## Decision

**We will make `pid-service` an EUDI-native identity hub: it accepts EUDI Wallet PID over
OpenID4VP as a first-class, eIDAS-High identity source; it issues bank-held attestations
((Q)EAA) over OpenID4VCI in SD-JWT VC; it gains a probabilistic record-linkage tier (Splink)
that feeds the existing four-eyes queue as an additional candidate source only; and onboarding
orchestration moves to a durable workflow engine (Temporal). Keycloak, OPA, Vault/OpenBao,
Kafka/outbox and the hash-chained audit chain remain the platform substrate.**

`pid-service` stays the single identity authority (ADR-0072). The new sources feed *into*
resolution; they never bypass it. No probabilistic signal and no wallet presentation ever
auto-merges two parties — the strongest they can do is open a four-eyes case or, for a verified
deterministic key (EUDI PID unique identifier, BankID sub, RČ blind index), match tier-1.

### 1. Identity ingestion graded by Level of Assurance (LoA)

Every identity source is tagged with an eIDAS LoA and recorded on the party with its evidence:

| Source | Protocol / format | eIDAS LoA | Deterministic key it yields |
|--------|-------------------|-----------|------------------------------|
| EUDI Wallet PID | OpenID4VP · SD-JWT VC / mdoc | High | PID unique identifier (`EUDI_PID_SUB`) + RČ if disclosed |
| BankID / SONIA | OIDC (Keycloak broker) | High | `BANKID_SUB` (+ RČ) |
| mojeID · eObčanka (NIA) | OIDC / SAML via NIA | Substantial–High | `ROB_AIFO` |
| Document + liveness | in-house / KYC provider | Low–Substantial | none (→ tier-2/3) |

A `LevelOfAssurance` (`LOW|SUBSTANTIAL|HIGH`) and a `verification_evidence` reference are
written on the party identity row. **High-LoA deterministic sources skip the document-scan and
the manual tier**: an EUDI PID or BankID assertion *is* the verification. Lower-LoA sources
continue through tier-2/3 exactly as ADR-0072 specifies. LoA is itself an authorization input
(§5): money-path actions can require a minimum LoA.

### 2. EUDI Wallet as a Relying Party (OpenID4VP)

A new inbound flow in `pid-service` (fronted by `customer-edge`, ADR-0065) requests a PID
presentation:

```
POST /api/v1/identity/eudi/presentation-request
  → 200 { requestUri, transactionId }          // OpenID4VP authorization request (signed)
   ... wallet presents over same-device or cross-device (QR) ...
POST /api/v1/identity/eudi/presentation         // VP token callback
  body: { transactionId, vpToken, presentationSubmission }
  → 200 { decision: MATCH_EXISTING | NO_MATCH | NEEDS_MANUAL_VERIFICATION, partyId?, caseId? }
```

- **Verification**: validate the SD-JWT VC / mdoc signature against the issuer; confirm the
  issuer is on the trusted **List of Trusted Lists (LoTL)** for PID providers (eIDAS trust
  framework); verify the holder-binding key proof (the wallet proves possession); check the
  `nonce`/`aud` to bind the presentation to *this* request (anti-replay).
- **Selective disclosure & data minimization**: we request only the attributes resolution
  needs — `given_name`, `family_name`, `birthdate`, `birth_place`, and (where present) the
  national identifier. The set requested is governed by an OPA policy keyed on purpose (§5).
- **Resolution**: the disclosed PID unique identifier is a **tier-1 deterministic key**
  (`EUDI_PID_SUB` external id, same `UNIQUE(id_type, id_value)` backstop as ADR-0072 §1). If a
  Czech RČ is disclosed, it is reduced to the blind index immediately and never stored or
  logged in plaintext (ADR-0072 invariant). The output is the *same* resolution verdict
  contract as ADR-0072 §4 — wallet onboarding is just a new, highest-assurance front door to
  the existing resolver.

### 3. (Q)EAA issuance into the wallet (OpenID4VCI)

`pid-service` (or a thin `identity-credential` adapter it owns) acts as an **attestation
issuer**. After a party is verified and provisioned, the customer can pull attestations into
their EUDI Wallet:

```
GET  /api/v1/identity/eudi/credential-offer?type=proof-of-iban   → OpenID4VCI credential offer
POST /api/v1/identity/eudi/credential                            → SD-JWT VC (holder-bound)
```

- **Formats**: `SD-JWT VC` (selective-disclosure JWT) as the default; ISO `mdoc` where a
  verifier requires it.
- **Catalogue (initial)**: `proof-of-iban` (account-holder + IBAN), `age-over-18`,
  `residency`, `account-status`. Each is a typed, versioned credential schema.
- **Keys & revocation**: issuer signing keys live in Vault/OpenBao, KMS/HSM-backed; for the
  *qualified* tier (QEAA) the key sits behind a QTSP-grade signing path (out of scope for
  Phase 1 — we ship the **non-qualified EAA** first, then qualify). Revocation uses the **OAuth
  Token Status List** (status-list credential), polled by verifiers.
- **EAA vs QEAA is a deliberate phase boundary**: non-qualified EAA needs no QTSP and delivers
  most of the reuse value (e.g. proof-of-IBAN for our own TPP flows); QEAA is a later,
  separately-governed increment.

### 4. Probabilistic record linkage as an additional candidate source (tier-2′)

We add a probabilistic linkage tier that **augments** ADR-0072's exact-tuple match. It is an
*additional candidate generator*, subject to the same hard rule: **it never auto-merges and
never auto-creates; a positive only ever opens a four-eyes case** (ADR-0072 §5).

- **Engine**: `Splink` (OSS, Fellegi–Sunter probabilistic record linkage), deployed in-cluster
  as a stateless `identity-match-service` scoring sidecar (fits ADR-0027; no managed
  dependency). It scores a candidate against the existing party population on
  `family_name`, `given_name`, `birthdate`, `birth_place`, and normalized address, returning a
  **match weight + per-field contribution** (explainable to an auditor — the reason ADR-0072
  rejected ML *as an auto-merge authority*, not as a candidate source).
- **Thresholds → routing**:
  - weight `< low` → treated as no candidate (proceed to create, as today);
  - weight in the **gray zone** `[low, high]` → `NEEDS_MANUAL_VERIFICATION`, candidate list
    (masked) + the per-field explanation attached to the four-eyes case;
  - weight `> high` with **no** conflicting deterministic key → still
    `NEEDS_MANUAL_VERIFICATION` (never auto-merge), but flagged "high-confidence" for triage.
- **Privacy posture (phase boundary)**: the v1 scorer runs on normalized attributes inside the
  trust boundary. A later increment can adopt **Bloom-filter privacy-preserving record linkage
  (PPRL)** so the linkage features are tokenized rather than plaintext — GDPR data-minimization
  by construction. Deferred, not required for go-live.
- The exact-normalized-tuple match (ADR-0072 §3) **stays** as the high-precision tier; Splink
  raises *recall* on the cases it misses. Both feed the same queue.

### 5. Authorization, assurance, audit, PII

- All new endpoints (`/identity/eudi/*`, the match-service call) ship `@Authorize` in
  **enforce** mode (ADR-0034). OPA gains: (a) **purpose-binding** policy governing *which* PID
  attributes may be requested/disclosed for *which* onboarding purpose (selective disclosure
  enforced as policy, not code), and (b) a **minimum-LoA** input so money-path verbs (ADR-0032
  four-eyes set) can demand `HIGH`.
- Every presentation verification, issuance, and match decision emits a hash-chained
  `AuditEvent` (ADR-0029) with AI-actor attribution (ADR-0031). The RČ blind index, the
  plaintext RČ, the VP token, and the full disclosed attribute set are **never** in audit
  payloads — only `partyId`, `decision`, `loa`, `source`, `index_key_version`, and the match
  weight (not the underlying PII).
- Candidate lists and disclosed attributes are PII: role-masked via `PiiMask`; only
  `COMPLIANCE` sees unmasked detail in the adjudication drawer (ADR-0072 §7).

### 6. Durable onboarding orchestration (Temporal)

`onboarding-service`'s bespoke state machine is replaced by a **Temporal** workflow
(OSS durable execution, deployed in-cluster). The onboarding journey becomes a workflow-as-code
in Kotlin; each external step (resolve, KYC, sanctions/PEP gate, party+relationship create,
Keycloak user + SCA enrol, credential offer) is a Temporal *activity* with first-class retry,
timeout, idempotency, and **compensation** (a failed screening deterministically unwinds a
provisionally created party). Services keep emitting domain events via the outbox → Kafka;
**Temporal orchestrates, services own their data** — it is not a second source of truth and
does not replace the outbox. This directly satisfies DORA Art. 17 reconstructability: the
workflow history *is* the audit of the process.

Temporal is **the one genuinely new platform component**; §"Delivery order" sequences it after
the higher-ROI, lower-risk identity work so it can be evaluated on its own merits, and ADR-0072's
existing state machine remains valid until the cutover lands.

### Delivery order

ADR (this) → ratify & re-status ADR-0072 (Proposed → Accepted, its mechanics are unchanged and
now have an umbrella) → **deploy `pid-service`** (GitOps manifest + ADR-0072 backfill, issue
#699) — *nothing here is real until the golden record runs in-cluster* → `identity-match-service`
(Splink) wired into resolution as tier-2′ → OpenID4VP Relying Party flow (eIDAS-High onboarding)
→ Temporal onboarding orchestration cutover → OpenID4VCI EAA issuance (`proof-of-iban` first) →
QEAA qualification + PPRL hardening (later). Each is its own PR with its own version bump,
OpenAPI + contract test where applicable, and a **threat model** — pid-service is
identity-critical, so every PR carries money-path review rigour (2 approvals + ADR-0030 threat
model).

## Alternatives considered

- **Stay pre-EUDI; document-scan + BankID only.** Pros: no new protocols. Rejected: eIDAS 2.0
  obliges regulated relying parties to accept the wallet within the rollout window, and we
  forgo the highest-assurance/lowest-friction onboarding path and the issuer position — a
  strategic miss for a bank whose service is already named `pid`.
- **Build OpenID4VC plumbing in-house from scratch.** Rejected for the verification/crypto core:
  we adopt standards (OpenID4VP/VCI, SD-JWT VC, mdoc, Token Status List) and a vetted library,
  not a bespoke VP validator — getting holder-binding, trust-list and selective-disclosure
  verification wrong is a security defect, not a feature.
- **Probabilistic auto-merge above a high threshold.** Rejected — violates the ADR-0072
  invariant: probabilistic linkage is unexplainable enough that an *automatic* identity merge
  on a score is an unacceptable banking control. It may only *raise a case*.
- **Keep the bespoke onboarding state machine.** Pros: no new platform. Rejected for the target
  state: ad-hoc retry/compensation/timeout is hard to make DORA-reconstructable; Temporal makes
  the process history a first-class audit artifact. (Mitigated by sequencing Temporal last and
  keeping the state machine valid until cutover.)
- **Camunda 8 / Zeebe (BPMN) instead of Temporal.** Reasonable; rejected as the primary pick
  because workflow-as-code in Kotlin fits the hexagonal, test-first codebase better than a BPMN
  model + external engine, and we already express async flows as code (BPMN-as-YAML is
  documentation, not execution). Revisit if a non-engineer authoring need emerges.
- **A managed identity/IDV SaaS (e.g. a cloud KYC/wallet platform).** Rejected: violates
  ADR-0027 (cloud-agnostic, in-cluster OSS substrate) and puts the identity golden record and
  PID handling outside our trust boundary.

## Consequences

**Positive**
- Highest-assurance, lowest-friction onboarding (EUDI PID = eIDAS-High, no doc-scan, selective
  disclosure) and an issuer position that compounds with PSD2/XS2A (ADR-0090/0093).
- Higher duplicate-detection recall (Splink) without ever weakening the no-auto-merge control —
  recall up, precision decision still human.
- Onboarding becomes a durable, reconstructable workflow (DORA Art. 17) with real compensation.
- Standards-aligned (eIDAS 2.0 / ARF, OpenID4VC, SD-JWT VC, mdoc) — interoperable with every
  conformant EU wallet, not a proprietary scheme.

**Negative**
- Real new surface area: OpenID4VC verification/issuance crypto, a trust-list integration, a
  Splink match-service, and Temporal as a new platform component — each its own
  threat-modelled, money-path-rigour PR.
- QEAA (qualified) needs a QTSP-grade signing path; we ship non-qualified EAA first and qualify
  later — a deliberately staged capability gap.
- Temporal adds an operational component (its own cluster + persistence) to run and back up.

**Neutral**
- pid-service was already identity-critical/money-path rigour; this confirms it for the new PRs.
- ADR-0072's resolver contract is unchanged — wallet and Splink are new *inputs* to it, so the
  customer-facing neutrality and four-eyes model carry over verbatim.

## Compliance impact

- PCI DSS: not applicable (no cardholder data in identity resolution/issuance).
- DORA:    Art. 17 — Temporal workflow history + hash-chained audit make the onboarding process
           and every identity decision reconstructable; issuer keys and trust-list state are
           rebuildable from source/Vault.
- GDPR:    Art. 5(1)(c) data minimization — selective disclosure (request only needed PID
           attributes, OPA-governed) and the deferred Bloom-filter PPRL option; Art. 5(1)(d)
           accuracy — probabilistic linkage only *raises* a human case, never auto-merges; PII
           role-masked; RČ stays one-way (blind index, ADR-0072).
- PSD2:    SCA remains ADR-0021/0066; issued (Q)EAA (proof-of-IBAN, account-status) can back
           XS2A consent/identity flows (ADR-0090/0093) — an enabler, not a new obligation.
- CNB / AML: eIDAS-High EUDI PID and BankID strengthen Customer Due Diligence (Act 253/2008
           Sb.) with the highest identity assurance; higher-recall linkage improves the
           single-customer-view precondition for exposure aggregation and sanctions/PEP
           screening, while the four-eyes tier keeps the merge decision a recorded compliance
           act.

## References

- ADR-0027 — Cloud-agnostic, in-cluster OSS substrate (Splink + Temporal deployed in-cluster).
- ADR-0029 — Governance as code (hash-chained audit).
- ADR-0030 — Threat models / money-path review rigour.
- ADR-0031 — AI agent governance (AI-attributed audit).
- ADR-0032 — Synchronous sanctions/AML screening gate (an onboarding workflow activity).
- ADR-0034 — Unified OPA authz (new endpoints in enforce mode; purpose-binding + min-LoA).
- ADR-0048 — Two version axes (release vs. API contract).
- ADR-0055 — Cross-service search contract (RČ deliberately not searchable in party-service).
- ADR-0065 / 0066 / 0069 — Customer edge + realm, passwordless onboarding, onboarding journey.
- ADR-0068 — Onboarding operations cockpit (four-eyes primitive, approval queue, cockpit).
- ADR-0072 — Client identity unification (the golden record + resolver this extends; its
  invariants are preserved verbatim).
- ADR-0090 / 0093 — PSD2 XS2A (Berlin Group + ČOBS) and the public developer portal — consumers
  of issued (Q)EAA.
- eIDAS 2.0 — Regulation (EU) 2024/1183; EUDI Wallet Architecture Reference Framework (ARF).
- OpenID4VP / OpenID4VCI; IETF SD-JWT VC; ISO/IEC 18013-5 (mdoc); OAuth Token Status List.
