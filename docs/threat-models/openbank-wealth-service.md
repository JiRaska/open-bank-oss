# Wealth service threat model

This service holds **what a customer says they own** — property, vehicles, unlisted shareholdings,
private debt — and nothing the bank itself custodies. It moves no money, has no ledger credentials
and calls no other service (there is not one `@RegisterRestClient` in the module). That shifts the
model away from the usual money-path shape: the asset here is not a balance an attacker can drain,
it is a **confidential picture of a customer's net worth** and an **integrity claim other decisions
will lean on**.

## What is actually at stake

`DeclaredHolding` carries `ownerPartyId`, `holdingType`, a `Valuation` (`amount`, `currency`,
`valuedAt`, `source`, optional `appraiserReference`), `ownershipShare`, `documentIds`, and
`pledgedToLoanId`. Two of those fields decide the threat profile:

**Confidentiality is the primary asset, and it is unusually concentrated.** One row states that a
named party owns an asset worth a stated amount. A dump of this table is a list of the bank's wealthy
customers and what they own — materially more attractive than a transaction log, because it needs no
aggregation to be useful. GDPR treats it as ordinary personal data, but the practical harm of
disclosure (targeted fraud, physical risk, coercion) is closer to the special categories, so the
control posture is written for that rather than for the classification.

**`pledgedToLoanId` makes valuation an integrity target.** A declared holding that is pledged
becomes collateral. Inflating `valuation.amount`, or setting `ownershipShare` to 1 on an asset the
customer owns a fraction of, turns into borrowing capacity the bank would not otherwise extend. The
threat is therefore not "an attacker reads a valuation" — it is **an attacker, or the customer,
writes one**. `ValuationSource` exists to separate a self-declared figure from an appraised one, and
`attributableAmount` (`amount × ownershipShare`) is a derived getter rather than a stored column
precisely so a stored value cannot disagree with its inputs.

## Spoofing and elevation — who may write

The resource is `@RolesAllowed(Roles.API, Roles.OPERATOR, Roles.ADMIN)`, so there is no
unauthenticated surface and no customer-facing path into this service directly. The authorization
decision that matters is in `wealth_rest_ext.rego`, and it deliberately splits read from write:

- `operator-wealth-read` permits `wealth.holding.read` **only** — a HUMAN holding
  `ROLE_OPERATOR`, `ROLE_ADMIN` or `ROLE_COMPLIANCE`, with `not startswith(input.principal.id,
  "service-account-")`. Staff can look; staff cannot declare, revalue or withdraw.
- `edge-service-wealth` permits all four actions, but only for
  `input.principal.id == "service-account-openbank-edge"` — the customer edge, which authenticates
  the human and stamps the party.

That asymmetry is the control. Writes reach this service only through the edge acting for the
authenticated owner, so an operator with a stolen session cannot inflate a collateral valuation, and
a compromised staff account yields disclosure rather than forged borrowing capacity. The rule is
keyed on `principal.id`, not on a role, because every Keycloak service-account authenticates as
`HUMAN` here — gating on `HUMAN` + `ROLE_OPERATOR` would have granted real staff the write path.

`X-Customer-Party-Id` is declared **nullable** and checked with `requireNotNull` in the body. That is
not stylistic: JAX-RS injects `null` for an absent header, and on a plain `fun` Kotlin's
`checkNotNullParameter` at offset 0 makes a body guard dead code, so the absent-header case — the one
the guard exists for — would answer 500 instead of 400 (#3104).

## Tampering — what the domain refuses

`Side` is a property of `HoldingType` rather than a field beside it, so no request can declare a
mortgage as an asset; the sign is not caller-supplied. `status` and `pledgedToLoanId` are lifecycle
state, not request fields. Revaluation is a distinct operation (`PUT /holdings/{id}/valuation`,
action `wealth.holding.revalue`) with its own history — `GET /holdings/{id}/valuations` — so a
valuation cannot be quietly overwritten; the previous figure remains as evidence.

## Information disclosure — the event stream is the widest edge

`wealth.holding.declared.v1` and `wealth.holding.revalued.v1` put `ownerPartyId`, `holdingType`,
`amount`, `currency` and `ownershipShare` **on Kafka**. That is the concentrated disclosure risk
described above, leaving the service's own authorization behind: anyone who can read
`openbank.wealth.events` reads the net-worth picture. What contains it is transport and ACL, not the
REST rules — `kafka-wealth-mtls.yaml` and `kafka-mtls-externalsecrets.yaml` (mTLS, per-service
credentials from Vault) plus the KafkaUser ACLs, and `network-policies.yaml` for the pod edge.
`rules.yaml` documents that this topic has **no consumer yet** (ADR-0301 D1): the two planned readers
are the customer-edge net-worth composition (#9772) and the affluent micro-segment (#9774). Each new
consumer widens this exposure, so the ACL — not the topic's existence — is the thing to re-check when
one lands.

The payload carries the amount by design: a segmentation rule needs type and amount. The mitigation
available and not yet needed is that it does **not** need the label, `externalReference` or
`appraiserReference`, and those are absent from both payloads — a deliberate narrowing rather than a
full projection of the aggregate.

## Repudiation and availability

Every valuation is retained rather than replaced, so "the bank changed my valuation" is answerable
from `GET /holdings/{id}/valuations`. Availability is the mildest axis here: this service is on no
payment path, and an outage degrades a net-worth view rather than blocking a transaction. The
consequence of that is a deliberate asymmetry — the confidentiality controls above are worth paying
for in latency and operational friction; hardening this service against a denial-of-service attack is
not.

## Known gaps, stated rather than implied

- **No consumer exists yet**, so the Kafka ACL has never been exercised against a real reader. The
  first consumer PR is the moment to verify the ACL restricts to it, and `rules.yaml` already
  requires that entry to be removed then.
- **Document handling is by reference only.** `documentIds` points at document-service; this model
  does not cover the documents themselves (a valuation report can name an address). That boundary
  belongs to `openbank-document-service`'s model, and this service never fetches them.
- **No rate limit on declaration.** The edge authenticates the owner, so abuse is attributable rather
  than anonymous, and a customer flooding their own holdings is a data-quality problem rather than a
  security one. Worth revisiting if a consumer ever acts on declarations automatically.
