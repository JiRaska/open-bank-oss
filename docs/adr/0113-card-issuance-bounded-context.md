---
date: 2026-06-25
decision-status: accepted
delivery-status: shipped
authors: [Claude (paired with Jiří Raška)]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [cards, architecture]
summary: "Card-issuance is the card registry and lifecycle manager, not a processor: it owns the card state machine, synthetic sandbox PANs, reference-only spending limits and two outbox events; authorisation, 3DS and PIN stay out of scope."
---

# 113. Card issuance bounded context — virtual-first, internal lifecycle, no external processor

## Context

`openbank-card-issuance-service` has been live in the sandbox since v0.4.0 without an architecture
decision documenting its scope, the card lifecycle, or its integration model. The service issues
debit, credit, prepaid and virtual cards (`CardType`), supports networks VISA/MC/AMEX/UNIONPAY
(`CardNetwork`), and is deployed in the `payments` namespace — yet no ADR clarifies what is
in-scope (lifecycle management, spending limits) vs. out-of-scope (real-time transaction
authorisation, 3DS, PIN management, external card processor).

A threat model (`docs/threat-models/openbank-card-issuance-service.md`) is also missing; it is
required by ADR-0030 for any service that handles payment-instrument data, even pseudonymised.

## Decision

We define card-issuance as the **card registry and lifecycle manager** — not a card processor.

**1. Card lifecycle.** The canonical state machine, enforced in `Card.kt` domain transitions:

```
PENDING → ACTIVE      (activate)
ACTIVE  → SUSPENDED   (suspend — temporary freeze, reversible)
ACTIVE  → BLOCKED     (block — permanent, reason required)
SUSPENDED → ACTIVE    (resume)
ACTIVE/SUSPENDED → BLOCKED (block)
any non-terminal → CANCELLED (operator-initiated)
PENDING/ACTIVE/SUSPENDED/BLOCKED → EXPIRED (time-driven, out-of-scope here)
```

**2. Stored data — sandbox PAN is synthetic.**
In the sandbox, `maskedPan` is generated as `"**** **** **** ${random 4-digit}"` — it is a
synthetic placeholder, not a mask of a real PAN. No real PAN is generated, transmitted, or stored
anywhere in this service. Integration with a real card processor (e.g. Visa DPS, Mastercard Issuer
Connect) will require a separate ADR and PCI DSS scope assessment at that point.

**3. Spending limits.** `dailyLimitMinorUnits` and `monthlyLimitMinorUnits` are stored as reference
data. They are not enforced in real time by this service; a future authorisation component would
consume them.

**4. Domain events.** Two events flow through the transactional outbox (ADR-0050):
- `card.issued.v1` — emitted on `issueCard`; carries `partyId`, `accountId`, `cardType`,
  `network`, `maskedPan`.
- `card.status_changed.v1` — emitted on every lifecycle transition; carries `previousStatus`,
  `newStatus`, `reason`, `changedBy`.

**5. Idempotency.** `issueCard` is idempotent on `idempotencyKey`; a duplicate request returns
the existing card without a second DB write or outbox event.

**6. Out of scope.**
- Real-time transaction authorisation.
- PIN management, 3DS, tokenisation.
- External card processor integration (deferred to a future ADR).
- Card expiry sweep (time-driven transition to EXPIRED).

## Alternatives considered

- **Integrate an external card processor immediately.** Premature without a banking licence; the
  sandbox has no need for a real network connection.
- **Combine card issuance and transaction authorisation in one service.** Violates SRP; real-time
  authorisation has different SLA and scaling requirements from lifecycle management.

## Consequences

**Positive**
- Clear scope: the service holds no full PAN (sandbox: synthetic; production: would require
  processor tokenisation) — outside PCI DSS CHD storage scope today.
- Lifecycle is fully testable in the domain layer without external dependencies.
- `card.issued.v1` enables downstream consumers (e.g. fraud-service, analytics) to react to
  card creation.

**Negative**
- Spending limits are not enforced in real time — they are stored but not acted on.
- No external processor means cards cannot be used for real payments.
- Threat model (`docs/threat-models/openbank-card-issuance-service.md`) is missing and must be
  authored before any production deployment (ADR-0030 gate).

**Neutral**
- Real PAN allocation and PCI DSS scope assessment are deferred to a future ADR once an external
  processor integration is planned.
- Card expiry sweep (ACTIVE → EXPIRED after `expiryDate`) requires a scheduled job (Temporal or
  Quartz); not yet implemented.

## Compliance impact

- PCI DSS: synthetic sandbox PAN → outside CHD storage scope. Real PAN integration = new ADR +
  PCI scope review required.
- GDPR: `cardholderName` and `embossedName` are PII → subject to party-service GDPR erasure
  flow; `card.status_changed.v1` consumers must handle `PARTY_ERASED` (ADR-0117).
- PSD2: not applicable — issuance ≠ payment initiation.
- ČNB: issuing payment instruments requires a PI or EMI licence (§ 8 ZPS).
- DORA: card-issuance is not in `money_path_services`; DORA RTO/RPO governed by ADR-0061.

## References

- `openbank-card-issuance-service/src/main/kotlin/.../domain/model/Card.kt`
- `openbank-card-issuance-service/src/main/kotlin/.../application/usecase/CardService.kt`
- ADR-0030 (supply-chain security — threat model gate)
- ADR-0050 (transactional outbox)
- ADR-0117 (GDPR data lifecycle — `PARTY_ERASED` propagation)
