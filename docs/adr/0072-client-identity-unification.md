# 72. Client identity unification — one human, one party, deterministic dedup with a manual-verification fallback

Date: 2026-06-07
Status: Accepted (2026-06-16, under the ADR-0094 umbrella)
Author(s): OpenBank platform

## Context

ADR-0068 (onboarding cockpit) and ADR-0069 (onboarding journey) defined *how* an applicant
moves through the funnel and *who* creates the party and Keycloak user. Neither answers the
prior question: **is this applicant already a customer?** Today nothing prevents the same
physical person from becoming two parties.

`openbank-pid-service` is the identity golden record. It holds the data that *would* identify
a person — but the dedup is partial and the strongest key is unusable:

- **The Czech birth number (rodné číslo, RČ) is not deduplicated at all.**
  `parties.birth_number_encrypted` is encrypted with `pgcrypto` (non-deterministic), so it
  **cannot** carry a `UNIQUE` constraint and **cannot** be equality-searched. Two parties can
  hold the same RČ and the database is happy.
- **`party_external_ids UNIQUE (id_type, id_value)`** prevents a duplicate *per external
  identifier* — `existsByExternalId(BANKID_SUB, …)` blocks a second party for the same BankID
  subject. But it only fires when the applicant arrives through the *same* channel as last
  time. The same human via BankID today and a branch walk-in tomorrow becomes two parties.
- **`party-service` has only `UNIQUE(email)`** — a gate on an e-mail string, not on a person.
  E-mails are shared, changed, and reused; this is not an identity key.
- **There is no candidate-matching surface.** Indexes exist on `(family_name)` and
  `(birthdate)`, but no use case asks "who already looks like this applicant?" before
  creating a record.

The regulatory forcing function is **Customer Due Diligence / single customer view** (AML
Act 253/2008 Sb., CNB): a bank must be able to assert one identity per customer, aggregate a
customer's exposure, and run sanctions/PEP screening against a deduplicated population.
Duplicate parties break exposure aggregation, KYC re-use, and screening integrity. We are
about to open self-service onboarding (ADR-0069 Phase 2), which removes the operator who today
implicitly catches "didn't we already onboard this person?". The dedup must move into code
**before** that gate opens.

The hard cases are explicit:

1. **Same RČ presented twice.** The common case (returning customer) must unify silently. The
   pathological case — same RČ but divergent core attributes (data-entry error, identity
   theft, RČ reissue) — must **not** silently merge and must **not** silently create a
   duplicate; it goes to a human.
2. **No Czech RČ at all** (foreign nationals, residence-permit holders). There is no strong
   deterministic key. A near-match on `(family name, given name, birthplace, date of birth)`
   could be the same person — or genuine namesakes. We cannot auto-merge on a probabilistic
   match, but we also cannot ignore it. It goes to a human.

## Decision

**We will perform identity resolution in `pid-service` (the golden record) before any party
is created, using a three-tier match: a deterministic blind index for the Czech RČ, a
normalized candidate match for everyone else, and a manual-verification case — reusing the
existing four-eyes primitive and the ADR-0068 cockpit — whenever the match is ambiguous.**

pid-service stays the single authority. customer-edge and operator flows call resolution
first and act on its verdict; they never create a party that resolution has not cleared.

### 1. Deterministic key — Czech RČ via a blind index

We add a **keyed blind index** alongside the existing encrypted RČ. The plaintext stays in
`birth_number_encrypted` (non-deterministic, for display/regulatory disclosure); the index
gives equality lookup *and* a hard uniqueness backstop without storing the RČ in a reversible
or guessable form.

```
birth_number_index  =  HMAC-SHA256(pepper, normalize(RČ))     -- 64 hex chars
  normalize: strip the slash, trim; reject if it is not a structurally valid RČ
  pepper:    a service-held secret (Vault), NOT the column encryption key — rotating it
             requires a re-index migration, so it is versioned (index_key_version column)
```

- Stored as a new external-id kind `BIRTH_NUMBER` in `party_external_ids` (value = the index,
  never the plaintext) → it inherits the existing `UNIQUE (id_type, id_value)` constraint for
  free, and `findByExternalId(BIRTH_NUMBER, index)` becomes the dedup lookup. This is the
  **tier-1 deterministic match**.
- The `UNIQUE` constraint is the race-condition backstop: two concurrent onboardings with the
  same RČ — one wins the insert, the other catches the constraint violation and is routed to
  resolution (tier-3), never to a duplicate row.

### 2. RČ validation — a libs primitive (`com.openbank.libs.identity`)

No RČ validation exists in the repo today (the only mod-11 is the IBAN generator, unrelated).
We add a shared, framework-free validator:

```
RodneCislo.parse(raw): RodneCislo | Invalid
  - 9 digits (pre-1954) or 10 digits; optional slash before the last 4
  - month 1–12, +50 (female), +20/+70 (post-2003 exhaustion) — extract gender + birthdate
  - birthdate plausible and consistent with the Party.birthdate the applicant/BankID supplied
  - 10-digit form: mod-11 checksum (divisible by 11; the 1954–1985 "==10→0" historical quirk)
  - 9-digit form: no checksum (validate structure + date only)
```

A cross-field rule: the RČ-derived birthdate and gender **must** match `CoreAttributes`. A
mismatch is not auto-rejected — it is a tier-3 manual case (the RČ may be mistyped).

### 3. No Czech RČ — normalized candidate match (tier-2)

For applicants with no RČ we compute a **normalized match key** and search existing parties.
This never auto-merges and never auto-creates blindly; a hit raises a manual case.

```
match_key = normalize(familyName) | normalize(givenName) | birthdate | normalize(birthplace)
  normalize: NFD, strip diacritics, lowercase, collapse whitespace
  candidate query uses the existing (family_name, birthdate) index, then refines in memory
```

- **Zero candidates** → no duplicate → onboarding proceeds, new party created.
- **One or more candidates** → resolution returns `NEEDS_MANUAL_VERIFICATION` with the masked
  candidate list; a four-eyes case is opened (§5). Possible outcomes: *same person* (link the
  new external id / identity document to the existing party) or *distinct namesakes* (operator
  explicitly allows the new party, recording the justification).

The match is deliberately **conservative and explainable** (exact normalized tuple), not a
probabilistic / ML scorer — see Alternatives.

### 4. The resolution use case and contract

A new inbound port + endpoint in pid-service, called by every party-creating path:

```
POST /api/v1/parties/resolve
  body: { partyType, givenName, familyName, birthdate, birthplace?,
          birthNumber?, bankIdSub?, nationalities[] }
  → 200 {
      decision: MATCH_EXISTING | NO_MATCH | NEEDS_MANUAL_VERIFICATION,
      partyId?:        <set iff MATCH_EXISTING>,
      caseId?:         <set iff NEEDS_MANUAL_VERIFICATION>,
      candidates?:     [{ partyId, nameMasked, birthYear }]   -- masked, for the cockpit only
    }
```

- The **plaintext RČ never leaves pid-service** and is never logged; callers pass it in over
  the M2M leg only when they hold it (BankID), and pid-service immediately reduces it to the
  blind index. `MATCH_EXISTING`/`NEEDS_MANUAL_VERIFICATION` responses to the *customer* surface
  carry no candidate detail — only the edge/cockpit M2M response does, masked.
- `createParty` is hardened to run resolution internally as well, so the invariant holds even
  if a caller forgets to pre-resolve: it can only ever produce a new party for a `NO_MATCH`.

### 5. Manual verification reuses four-eyes + the cockpit (no new queue)

A `NEEDS_MANUAL_VERIFICATION` outcome opens an `ApprovalRequest`
(`operation = identity.verify`, `resourceType = party-candidate`) via the existing
`com.openbank.libs.foureyes` primitive (ADR-0068 §4) and emits the approval event the
onboarding-service already projects. The cockpit (ADR-0068 §9) renders it in the existing
"awaiting approval" surface — we add an **identity-verification** action class, not a new
screen:

| Action | Min. role | Four-eyes | Notes |
|--------|-----------|:--:|-------|
| confirm **same person** → link to existing party | `COMPLIANCE` | ✅ | merges identity into the golden record |
| confirm **distinct** → allow new party | `COMPLIANCE` | ✅ | records namesake justification |
| RČ collision adjudication (same RČ, divergent attrs) | `COMPLIANCE` + `SUPERVISOR` | ✅ | possible identity fraud — highest rigour |

The actual mutation (link vs. allow) runs only on the checker's confirm, then pid-service's
normal `AuditEvent` + outbox fire — exactly the ADR-0068 model. No state moves out of
pid-service.

### 6. The onboarding flow gains a resolution leg

ADR-0069's `POST /onboarding/start` calls `POST /parties/resolve` *before* creating the party:

```
MATCH_EXISTING            → attach a new CUSTOMER relationship to the existing party
                            (no new identity row); continue the journey on that partyId
NO_MATCH                  → create the party as today
NEEDS_MANUAL_VERIFICATION → respond to the app with a neutral "we need to verify your
                            details" state (NO leak of why or who matched); case waits in
                            the cockpit; the app polls the funnel stage
```

The customer-facing response **must not** reveal that an RČ/identity already exists (account
enumeration / identity leak — consistent with the project's "frame gaps as maturity, never
expose exploitable specifics" rule). It returns the same neutral pending state whether the
trigger was an RČ collision or a namesake match.

### 7. Authorization, audit, PII

- `/parties/resolve` and the verification actions ship `@Authorize` in **enforce** mode
  (greenfield, per ADR-0034); the verification actions get OPA rules keyed on role +
  four-eyes attribute + action.
- Every resolution decision and every verification action emits a hash-chained `AuditEvent`
  (ADR-0029) with `before`/`after` and the mandatory `reason`; AI-actor attribution per
  ADR-0031. The RČ and the blind index are **never** in audit payloads — only `partyId`,
  `decision`, and `index_key_version`.
- Candidate lists are PII: masked by role via `PiiMask`; only `COMPLIANCE` sees unmasked
  names in the adjudication drawer.

### Delivery order

ADR (this) → libs `identity` (RČ validator + blind-index helper) → pid-service migration
(`birth_number_index` external-id kind + backfill of existing rows) + `resolve` use case &
endpoint + `createParty` hardening → wire four-eyes `identity.verify` into pid-service + OPA
enforce → customer-edge `/onboarding/start` resolution leg → onboarding-service projection of
the identity-verification queue → admin-UI cockpit action class. Each is its own PR with its
own version bump, OpenAPI + contract test where applicable, and a threat model (pid-service is
identity-critical → money-path review rigour).

## Alternatives considered

- **UNIQUE constraint directly on the encrypted RČ column** — simplest in principle. Rejected:
  `pgcrypto` encryption is non-deterministic (different ciphertext each call), so a `UNIQUE`
  on the ciphertext is meaningless and equality search is impossible. Switching to
  deterministic encryption to enable it would leak equality *and* be reversible with the
  column key — strictly worse than a separate one-way blind index.
- **Probabilistic / ML record linkage** (fuzzy scorer, Levenshtein, Fellegi–Sunter weights)
  for the no-RČ tier. Rejected as the v1 mechanism: it is unexplainable to an auditor, hard to
  threshold safely, and over-engineered for current volumes. The conservative exact-normalized
  tuple is transparent and tunable; probabilistic scoring can be layered in later as an
  *additional candidate source*, never as an auto-merge authority.
- **Deduplicate in party-service** (where the customer-facing record and `UNIQUE(email)`
  already live). Rejected: party-service deliberately holds **no** RČ (it is encrypted only in
  pid-service and must not be copied as plaintext, per the search contract ADR-0055). Identity
  resolution belongs with the identity golden record, not the transactional projection.
- **BankID-only identity, no manual tier** — rely entirely on BANKID_SUB / ROB_AIFO and refuse
  anyone without it. Rejected: excludes foreign nationals and branch onboarding, which the bank
  must serve; and it still does not dedup the same person arriving via two different strong
  identifiers.
- **Synchronous hard-fail on any duplicate** instead of a manual queue. Rejected: a hard 409
  to the customer both leaks that an identity exists and strands legitimate namesakes and
  data-entry errors with no resolution path. The four-eyes case is the correct banking control.

## Consequences

**Positive**
- One human = one party becomes a code-enforced invariant, not an operator habit — required
  before self-service onboarding (ADR-0069 Phase 2) opens.
- Deterministic RČ dedup with a DB `UNIQUE` backstop that is also race-safe, while the RČ stays
  one-way (blind index) — stronger privacy posture than storing a searchable RČ.
- No new operational surface for the manual path: it rides the existing four-eyes primitive and
  ADR-0068 cockpit.
- Single customer view for AML exposure aggregation and screening integrity.

**Negative**
- A blind index requires a **pepper** with a rotation story (re-index migration on rotation),
  and a backfill of existing pid rows that have an RČ but no index.
- Identity resolution adds a synchronous hop to the onboarding start path (one indexed lookup;
  negligible, but it is now on the critical path).
- The conservative exact-tuple match will produce some false "needs verification" cases for
  genuine namesakes — operator load, accepted as the safe direction to err.

**Neutral**
- pid-service was already identity-critical; this confirms its money-path review rigour
  (2 approvals + threat model) for these PRs.
- The libs `identity` module (RČ validator) is reusable wherever an RČ is accepted or displayed.

## Compliance impact

- PCI DSS: not applicable (no cardholder data in identity resolution).
- DORA:    Art. 17 — every resolution decision and verification action is reconstructable from
           the hash-chained audit trail; the blind index is rebuildable from source on pepper
           rotation.
- GDPR:    Art. 5(1)(c) data minimization — the RČ is stored one-way (blind index) for matching
           rather than as a searchable plaintext; Art. 5(1)(d) accuracy — the manual tier
           prevents erroneous merges; candidate PII is role-masked.
- PSD2:    not applicable at the identity-resolution layer (SCA is ADR-0021/0066).
- CNB:     supports the AML/CDD single-customer-view obligation (Act 253/2008 Sb.) — a
           deduplicated customer population is the precondition for correct exposure
           aggregation and sanctions/PEP screening; ambiguous identity cannot be resolved
           without a recorded compliance decision.

## References

- ADR-0029 — Governance as code (hash-chained audit).
- ADR-0030 — Threat models / money-path review rigour.
- ADR-0031 — AI agent governance (AI-attributed audit).
- ADR-0034 — Unified OPA authz (new endpoints ship in enforce mode).
- ADR-0048 — Two version axes (release vs. API contract).
- ADR-0055 — Cross-service search contract (RČ is deliberately not searchable in party-service).
- ADR-0065 / 0066 / 0069 — Customer edge + realm, passwordless onboarding, onboarding journey.
- ADR-0068 — Onboarding operations cockpit (four-eyes primitive, approval queue, cockpit).
</content>
</invoke>
