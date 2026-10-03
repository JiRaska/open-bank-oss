# Compliance

> **Money-path classification:** the threat model treats this service as money-path from its first commit (ADR-0030, [`docs/threat-models/openbank-card-processing-service.md`](../../../../docs/threat-models/openbank-card-processing-service.md)). At the time of writing it is **not yet listed** in `rules.yaml: money_path_services`, so the two-approval rule is not mechanically enforced for it.

## Regulatory framework

| Regulation | Relation | Implementation |
|---|---|---|
| **PCI DSS** | Card transactions without card data | No PAN, CVV or card credential is accepted, stored, logged or emitted; cards are referenced by card-issuance id (ADR-0283 D7). Keeps the service outside the cardholder-data environment. Vendor credentials come from OpenBao, never from the repository. |
| **PSD2** | Card payment execution | Authorisation, hold, clearing and release with every decision recorded, including declines. SCA / 3-D Secure is **not** implemented here. |
| **Accounting law** | Card spend is an accounting record | 7-year retention; every accepted clearing is posted on the `CARD` rail. |
| **GDPR** | Spending behaviour of identifiable customers | Confidential classification; references by id; no free-text card data. |
| **DORA** | Operational resilience | Fail-closed issuer call, short timeouts, fault-tolerant outbox, liveness gauges, three-valued outcomes for posting and scoring. |
| **Scheme rules (chargebacks)** | Dispute handling | The network's reason code, status and respond-by date are stored verbatim next to the bank's own status, so a deadline is never computed from a translated value. |
| **AML** | Transaction monitoring input | Events on Kafka; fraud scoring is shadow only and blocks nothing. |

## Controls

| Control | Where |
|---|---|
| No double hold on retry | UNIQUE `idempotency_key` |
| No double clearing on a repeated presentment | UNIQUE `card_clearings (authorization_id, idempotency_key)` + replay / 409 `IDEMPOTENCY_KEY_REUSED` |
| No lost or over-hold clearing, and no reversal/expiry overwriting one, under concurrency | optimistic lock `card_authorizations.version` on every write + re-evaluation against the remaining hold |
| One ledger posting per clearing, never shared across authorisations | ledger key `card-clearing:<authorizationId>:h:<base64url(SHA-256(key))>` — scoped, always hashed |
| No over-clearing | `AuthorizationLifecycle.clear` **and** a CHECK constraint |
| Decline reason only on declines | CHECK constraint |
| No silent unbound integration | `NOT_BOUND` from vendor bindings without credentials or without a contract |
| Sandbox acquirer cannot move money in a deployed environment | default off, `ROLE_ADMIN` only, 404 when off |
| One live chargeback per transaction | partial UNIQUE index `ux_card_dispute_live_per_authorization` plus an application check |
| No local dispute without a network case | opening fails closed; `network_case_id` is NOT NULL |
| A stale token list is never shown as current | every list carries `source` (`NETWORK` / `LOCAL_MIRROR`) |
| Authorisation | OIDC roles + OPA `@Authorize` actions (advisory while `AUTHZ_ENFORCE=false`; the effective control today is the role check) |

## Known gaps (stated, not hidden)

- `AUTHZ_ENFORCE=false`: the OPA decision is advisory.
- The authorisation endpoint is not rate limited (threat model §4).
- A clearing whose ledger posting fails stays recorded and unposted until someone acts on the `FAILED` outcome.
- No scheme connection, 3-D Secure or tokenisation/dispute vendor binding exists; the token and dispute desks run against the simulators only.
- A won or lost chargeback moves no money in this service; any refund or write-off is outside it today.

## GDPR

- **Lawful basis:** contract (Art. 6(1)(b)) and legal obligation for accounting retention (Art. 6(1)(c)).
- **Erasure:** constrained by the 7-year accounting retention.
- **Data flows out:** Kafka `openbank.card.processing.events` (intra-platform), transaction-service (posting), fraud-service (shadow score), card-issuance (decision). Optional BIN lookups to Visa/Mastercard sandboxes send a BIN only, never a PAN.
