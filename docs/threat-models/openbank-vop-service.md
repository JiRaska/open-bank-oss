<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-vop-service

STRIDE/DFD threat model for Verification of Payee, per ADR-0030 D2. Money-path service — VoP sits
on the pre-execution path of every euro credit transfer (ADR-0171, Reg. (EU) 2024/886 Art. 5c).

- **Status:** Draft (first pass, written alongside the service)
- **Last reviewed:** 2026-07-16
- **Owner:** payments CODEOWNERS
- **Related ADRs:** ADR-0171 (VoP), ADR-0002 (hexagonal), ADR-0032 (the neighbouring sanctions
  gate, deliberately fail-*closed* where VoP is fail-*open*), ADR-0118 (PII classification and
  retention), ADR-0030 (money-path threat models), ADR-0100 (injected Clock)

## 1. Scope & assets

VoP answers one question — "is this payee name the name held on this IBAN?" — with
`match` / `close_match` / `no_match` / `no_data`. It never moves money and never blocks a payment;
it informs the payer, who decides.

**The defining risk is the inverse of most money-path services.** VoP's danger is not that it
approves something it should refuse. It is that **VoP is, by construction, an oracle over
account-holder names.** Anyone who can call it can ask "does the name X hold the IBAN Y?" and get a
truthful answer. That is precisely the function the regulation mandates, and precisely what an
attacker enumerating customers would want. Every control below exists to keep the legitimate
function while denying the enumeration.

Assets protected, in priority order:

1. **Account-holder names** (`parties.legal_name` / `trading_name`, reached via account-service).
   The asset VoP is built to leak *in a controlled way*. Uncontrolled, it identifies who banks
   here, which is both a GDPR breach and a precursor to targeted social engineering.
2. **The existence signal** — whether a given IBAN is ours at all. Weaker than a name, still an
   enumeration primitive.
3. **Verification integrity** — a tampered or spoofed `match` response tells a payer their
   misdirected payment is safe. This is the fraud-enabling failure direction and the one the IPR
   liability regime cares about.
4. **The evidence record** (`vop_verification`) — proof the control ran and what it answered.
   Needed when a payer disputes a payment they were or were not warned about.

## 2. Data-flow diagram (textual)

```
payer (admin UI / rail)
  │  POST /api/v1/vop/verify { creditorIban, creditorName }   [OIDC bearer; OPA vop.verify]
  ▼
openbank-vop-service ── VopNameMatchPolicy (pure domain: normalise → band)
  │                          ▲
  │  domestic IBAN?          │ holder name
  ├── yes ──► account-service GET /accounts/iban/{iban}  ──► partyId    [M2M token]
  │           party-service   GET /parties/{partyId}      ──► legalName  [M2M token]
  │
  └── no  ──► VopSchemeRoutingPort ──► (no EPC link) ──► NO_DATA / NO_SCHEME_CONNECTIVITY
  │
  ▼
vop_verification (Postgres)   ── hashes only: sha256(iban), sha256(name), outcome, requester, ts
```

Trust boundaries crossed: payer → vop-service (authenticated, policy-gated); vop-service →
account-service/party-service (M2M, ROLE_SERVICE); vop-service → its own Postgres.

## 3. STRIDE

### Spoofing
- **A caller impersonating a payment rail to bulk-query names.** Mitigated: OIDC bearer required,
  `@RolesAllowed` + OPA `vop.verify`, no anonymous access. `requestedBy` on every evidence row is
  `principal.name` (preferred_username), the same identity format `AuthorizeInterceptor` resolves,
  so attribution cannot drift between the OPA decision and the record.
- **Residual:** any authenticated principal with a read role may call VoP. That is intended — a
  payer must be able to check a payee — but it means the enumeration control is rate limiting, not
  authentication. See Elevation.

### Tampering
- **A forged `match` response convincing a payer a misdirected payment is safe.** Mitigated
  in-transit by the mesh/TLS; the response is computed server-side from party-service's
  authoritative name, and vop-service holds no second copy of a name it could serve stale.
- **`CLOSE_MATCH` thresholds tampered via config.** `max-edit-distance` is env-driven. A raised
  value silently widens what counts as "close", turning genuine mismatches into reassuring amber
  warnings. Mitigated: the value is in the service's gitops manifest under review, not runtime
  mutable. **Residual — a config-only change with a real fraud consequence and no four-eyes gate.**
  Consider adding `vop.flip` to `four_eyes.verbs` if the threshold ever becomes operator-tunable.

### Repudiation
- **A payer denies having been warned.** Mitigated: `vop_verification` records the outcome and
  timestamp per request, keyed by hashed inputs the claimant can reproduce.
- **Residual:** recording is deliberately best-effort — a DB outage loses the evidence row rather
  than failing the payer's verification (ADR-0171 §3 fail-open). An attacker who could induce a
  targeted DB outage could blind the record. Accepted: the alternative (failing VoP when logging
  fails) inverts the fail-open decision through the back door. The failure logs at ERROR.

### Information disclosure — **the primary threat**
- **Name enumeration: guess an IBAN, learn who holds it.** Mitigated by the response asymmetry
  (ADR-0171 §6), enforced in `VopVerification`'s init block, not left to callers:
  - `no_match` returns the outcome **only** — a wrong guess teaches the attacker nothing but that
    they were wrong.
  - `close_match` returns the real name — but only to someone who already *nearly* knew it, which
    is the case the regulation requires us to let the payer correct.
  - `match` returns no name: the payer supplied it.
- **The `no_data` collapse.** "Not our account" and "our account, no name held" both return
  `ACCOUNT_NOT_FOUND`, and an unknown IBAN is a **200 + `no_data`**, never a 404 — deliberately, so
  the status code cannot be used to enumerate which IBANs are ours.
- **PII in URLs/logs.** `/verify` is a POST despite being a read: the IBAN and payee name must
  never reach a URL, access log, or referer header.
- **PII at rest.** The evidence table stores `sha256(iban)` and `sha256(name)`, never plaintext
  (GDPR Art. 5(1)(c)). Retention 13 months, not the 7-year accounting default — these are not
  accounting records.
- **Over-fetch from party-service.** `PartySummary` mirrors only `legalName`/`tradingName`; VoP
  never pulls birth numbers, addresses, or contact data it has no use for. Mirrors the scope note
  on party-service's `V7__party_name_search_trgm.sql`.
- **Residual, and it is real:** a `close_match` is still a name disclosure to a near-guesser. An
  attacker with a partial name ("J. Novák" + an IBAN) can convert it into the full name. This is
  inherent to the scheme — the regulation requires the payer be able to correct a near-miss — and
  is why the rate limit (§4.1) is load-bearing rather than hygiene: it does not remove this
  disclosure, it bounds how many times an attacker can attempt it.

### Denial of service
- **VoP outage stalls the payment path.** Mitigated by design: VoP fails **open**. A lookup failure
  yields `no_data` + a warning, never a hold. `@CircuitBreaker`/`@Retry`/`@Timeout` (3 s) on the
  lookup adapter bound the blast radius; the two-hop lookup is the latency risk.
- **VoP used to DoS account-service/party-service.** Every verify is two downstream calls, so VoP
  amplifies 1 request into 2 on services that are themselves money-path. Bounded by the same
  per-requester rate limit that stops enumeration (§4.1) — the two threats share one control.
  **Residual:** the limit is per *principal*, so N compromised or colluding principals still
  amplify N-fold; there is no global cap. The circuit breaker on the lookup adapter is the
  backstop that stops VoP hammering a downstream that is already failing.

### Elevation of privilege
- **Owner-scoping bypass.** VoP deliberately does *not* send `X-Customer-Party-Id` to
  account-service: a payer checking a payee is legitimately not the account's owner. This means VoP
  holds a genuinely broader read than any customer session — it can resolve *any* domestic IBAN to
  a name. That privilege is contained by (a) VoP returning a *band*, never the raw account record,
  and (b) the disclosure asymmetry above. **Do not "fix" the missing owner header: it would break
  the regulation's purpose.**
- **Action naming.** `@Authorize(action = "vop.verify")` — the prefix matches the module name
  `openbank-vop-service`, so `money_path_scopes` in the base `rest.rego` derives "vop" and actually
  matches. Contrast `openbank-sepa-instant`, whose `sctInstPayment` prefix silently never fires the
  four-eyes rule (documented in `gen-sepa-instant-opa-bundle.sh`). Naming this `vop.create` or
  `payeeVerification.*` would reintroduce that class of bug.
- **The shared M2M identity reached `vop.verify` through the OPERATOR branch** (GHSA-58jq-9hq3-66jr,
  #4228). `operator-vop-verify` was role-only, and `service-account-openbank-services` — the identity
  nearly every backend service authenticates as — carries `ROLE_OPERATOR` and is classified `HUMAN`
  by `AuthorizeInterceptor` (there is no `SERVICE` principal type), so it matched. Measured with
  `opa eval` against `vop-opa-bundle.yaml`, the shared account resolved **both** reasons
  (`["m2m-vop-verify", "operator-vop-verify"]`). Mitigated: `operator-vop-verify` now carries
  `not startswith(input.principal.id, "service-account-")`.
  Two things this does and does not change. It **strands no caller** — the payment rails were always
  meant to arrive through the identity-pinned `m2m-vop-verify`, which is untouched — so unlike the
  sibling debt entries in `check-operator-write-naming.py` this one needed no caller audit; the
  legitimate M2M caller had already named itself. And it does **not** narrow the name oracle: the
  rails still resolve any domestic IBAN, so per §4 item 1 the rate limit remains the control that
  bounds enumeration, not authorization. What it removes is every *other* service-account's
  incidental reach — a bare `ROLE_OPERATOR` holder that is not a payment rail no longer matches
  anything here. Covered by `vop_rest_ext_test.rego`; the extension moved out of the generator
  heredoc into a standalone `.rego` in the same change, since `opa-policy.yml` discovers suites by
  the `*_rest_ext.rego` / `*_rest_ext_test.rego` file pair and a heredoc has nothing to pair with —
  which is why this rule had never been covered by a test.

## 4. Open items / accepted risk

| # | Item | Status |
|---|------|--------|
| 1 | **Per-requester rate limit** — the single most important control in this document. `VopRateLimitFilter` + `VopRateLimiter`: fixed 60s window keyed on `principal.name`, 60 req/min default, Valkey-backed so the window is shared across replicas (an in-process counter would give an attacker `limit × replicas` and reset on every pod roll). **Fails closed** — if Valkey is unreachable we cannot prove a caller is under the limit, so we 429. That does not contradict VoP failing open: a 429 makes the caller render `no_data`, so the payment still flows with a warning. | **Shipped.** Still no WAF / edge rate limiting at the platform level (audit §4.3), so this is an application-layer control only: it bounds an authenticated caller, not a volumetric attack on the endpoint. |
| 2 | Per-requester anomaly detection (a principal whose `no_match` rate spikes is enumerating, not paying) — the `ix_vop_verification_verified_at` index exists to support it; the detector does not. | Open |
| 3 | Requester side is a seam, not a capability: external IBANs always `no_data`. Not a security gap; a delivery gap (ADR-0171 §4). | Accepted |
| 4 | Retention enforcement (13 months) has no scheduler yet — follow the ADR-0118 `*RetentionScheduler` pattern. | Open |
| 5 | `CLOSE_MATCH` thresholds are unvalidated guesses. Too loose reassures fraud victims; too tight trains payers to click through warnings. Tune from outcome metrics. | Open |
| 6 | Fraud reimbursement / liability shift (IPR Art. 5d) is out of scope (ADR-0171 §8). VoP produces the evidence a claims process would need, but no claims process exists. | Accepted, tracked |
| 7 | **Both §2 lookup hops were dialling the wrong port, so no deployed VoP has ever verified a payee.** The two ports were transposed in `application.yaml` from the start — account-service on 8101 (ledger-service's port), party-service on 8100 (account-service's) — and the gitops env inherited the swap while additionally naming namespace `parties`, which has never existed (it is `party`). No layer was correct, so there was no fallback. Ports corrected to 8100 / 8111 and the namespace to `party` (#3966). **This is a fail-open control that was failing open 100% of the time**: per §3 *Denial of service*, a lookup failure yields `no_data` + a warning and never a hold, so every `/verify` answered `no_data` and every payment proceeded with a warning — indistinguishable, from the caller's side, from a genuine unknown IBAN, which §3 *Info disclosure* makes a **200** on purpose. The `no_data` collapse designed to defeat IBAN enumeration is also what hid this. Corroborating evidence that it never worked: NetworkPolicies are derived from those env URLs, so party's ingress allow-list gained `payments` for the first time in the same change — even a corrected hostname would have been dropped by the CNI before it. | **Fixed** (#3966). Not detectable by any test layer: unit tests stub both clients, the IT serves localhost, a consumer pact answers whatever it is asked, and `incluster-hostname-resolution` (#3956) reads `application.yaml` only and exempts localhost — so it sees neither the gitops env nor a wrong port on a right host. What WOULD have caught it: an alert on the `no_data` *rate*, which is the §4 item 2 detector, still open. |

## 5. Change log

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal account and party REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or Verification of Payee control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.
