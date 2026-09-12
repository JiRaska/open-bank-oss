# Incentive service threat model

This service decides what a customer is offered; it holds no money and has no ledger credentials.
Its two assets are the promo-code secret and the offer lifecycle, and both are protected in code
rather than by convention.

**Promo codes are never stored.** `IncentiveApplication.digest` normalises a code and keeps only
`SHA-256(pepper : code)`; the raw code exists in the import request and nowhere afterwards, so a
database or backup disclosure yields digests that cannot be reversed without the pepper. The pepper
is a **boot-time gate, not a warning**: the bean is `@Startup` and the constructor does
`configuredPepper.filter { it.length >= 32 }.orElseThrow(...)`, so a missing or short
`PROMO_CODE_PEPPER` stops the service starting instead of silently degrading to an unpeppered
digest. That distinction matters here — an `@ApplicationScoped` bean is lazy, and the same check
written without `@Startup` would first run on a request, long after a deploy went green. The value
is projected from Vault as the `incentive-service-promo-code-pepper` ExternalSecret; it is never in
the repository, and a failed projection is a failed start.

**Publication is four-eyes, enforced in the domain.** `IncentiveOffer.publish` refuses unless the
offer is `PENDING_APPROVAL` and rejects `checker == maker` outright, so the control is in the
aggregate rather than in a REST guard a second caller could bypass. Both actors are persisted
(`maker`, `checker`) and every transition writes an evidence row. Retirement is only reachable from
`PUBLISHED`.

**Two distinct callers, two distinct roles.** `/api/v1/incentives` (the operator lifecycle: create,
submit, publish, import codes, reserve, commit) is `ROLE_OPERATOR`. `/api/v1/customer-incentives`
(reserve, commit, release against a published offer) is `ROLE_API`, the machine-to-machine path.
Separating them is what keeps a customer-facing token from reaching offer creation or code import.

**Reservations are the abuse surface.** A reservation holds capacity against `totalLimit` and
`perPartyLimit` and expires on a TTL (`PROMO_RESERVATION_TTL`, 15 minutes by default) with an
explicit release path, so an attacker cannot exhaust an offer by reserving and walking away. The
limits are properties of the offer, checked on the reserve path, not advisory metadata.

**Trust boundaries in the sandbox deployment.** Generated NetworkPolicies admit application traffic
from exactly three namespaces — `campaign` (credit-offer delivery after ADR-0269), `admin-ui`, and
same-namespace pods — plus health and metrics traffic from `observability` and `security-scanner`.
That list is the artefact to check a new inbound edge against; it is also the reason this document
exists, since #9166 added the campaign edge when there was no callee list to update. State lives in
its own CloudNativePG database. Domain events leave through a transactional outbox
(`IncentiveOutboxDispatcher` → `KafkaIncentiveOutboxEventPublisher`) over the service's own
KafkaUser, so an event and the state change that produced it commit together.

**Out of scope, deliberately.** This service requests nothing monetary and cannot post to the
ledger. If an incentive ever becomes a monetary credit rather than an eligibility decision, that is
a money-path change and needs its own ADR and a revision of this document before it ships.
