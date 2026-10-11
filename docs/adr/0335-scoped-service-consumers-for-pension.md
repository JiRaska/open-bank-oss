---
date: 2026-10-09
decision-status: proposed
delivery-status: partial
followup: "#12385 — the dedicated PENSION_OPERATION SCA purpose and pension's consumer pacts (#12401, #12408) are unbuilt"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, sca, security, accounts]
summary: "pension-service gets no broad M2M grant: scoped consumer/initiator registers in rules.yaml, read by OPA and enforced in each provider domain (sca namespace, account ownership projection, payout debtor, SDD creditor+party)."
---

# ADR-0335 — Scoped service consumers: a reserved SCA namespace and an ownership-verification projection for pension-service

## Context

ADR-0334's pension-service calls two money-path providers as its own Keycloak client
`openbank-pension` (principal `service-account-openbank-pension`, realm role `ROLE_API` only):

1. sca-service `POST /api/v1/sca/challenges/{id}/consume` (`scaChallenge.consume`), to spend the
   customer's device-signed approval of a pension operation (onboarding KID signature, transfer,
   exit payout);
2. account-service `GET /api/v1/accounts/iban/{iban}` (`account.read`), to check that a payout
   IBAN belongs to the participant and is active.

Both deny the principal today (#12385). The obvious fix is to add it to each service's
`*_rest_ext.rego` beside the clients that already hold those actions. That grant would be
broader than pension needs, in both cases:

- `scaChallenge.consume` is the ADR-0021 settlement gate. The OPA resource is the challenge id
  (`resource = "#id"`), and the policy has no view of the challenge's party, purpose or binding.
  Granted the action, a stolen pension credential could spend **any** customer's completed
  challenge whose binding it can restate: a payment approval, a card action, a document
  signature. sca-service's domain already checks the party and the dynamic linking, but both are
  facts the caller states, so neither narrows what a caller holding the action can try.
- `account.read` returns the whole account: balance-adjacent fields, the owner's party id, the
  product. Pension needs a yes/no.

Two further facts shape the choice:

- **The customer's token never reaches pension.** Customer tokens live in the
  `openbank-customers` realm. Backends trust only the operator realm. ADR-0065 keeps the two
  realms apart, and ADR-0331 records that no cross-realm token exchange exists. The token
  exchanges in this tree (ADR-0177 workload identity, ADR-0224 operator OBO) each stay inside one
  realm. Pension's exit and payout steps also run from Temporal workflows and schedulers, after
  the customer's session has ended.
- **The fleet already narrows M2M grants by resource where policy alone cannot.** ADR-0206 scopes
  the shared client's `consent.grant` to one grantee. ADR-0169 makes SCA challenges
  document-bound, so a signature cannot be spent on an unrelated document.

## Decision

We will give pension-service **scoped consumer** access to both providers. OPA admits the
identity for one action each. The provider's domain then decides what that identity may touch.

**D1 — sca-service: a reserved approval namespace.** An `approvalRequestId` beginning `pension-`
is reserved to pension-service. sca-service's domain resolves a `ConsumerScope` for every
consume, from the authenticated principal:

- `service-account-openbank-pension` gets `Reserved(PENSION)`. It may spend only a challenge whose
  purpose is `APPROVAL` and whose device-signed `approvalRequestId` matches
  `pension-<operation>:<ref>`, for example `pension-exit:<hash>`. Any other challenge is refused
  with 403 **before** the compare-and-consume, so a refused attempt never burns it.
- Every other principal gets `General`. It may spend anything **except** a reserved-namespace
  challenge. A customer's pension signature is therefore spendable by pension alone, and pension
  cannot spend anything else.

The existing checks stay as they are: the party must match, the binding must be exact, and a
challenge is spent once. The namespace is read from the **stored** linking data, which the
customer's device signed, and never from the request.

The scope is **structured data, not a comment**. `rules.yaml: scoped_sca_consumers` declares
`{principal, actions, purposes, approval_request_prefix}`. `sca_rest_ext.rego`
(`service-sca-scoped-consumer`) reads `principal` and `actions`, so the identity is admitted to
`scaChallenge.consume` only. `ConsumerScopesRulesParityTest` holds the domain's `ConsumerScopes`
to `purposes` and `approval_request_prefix`, so the two layers cannot drift. The party and the
namespace cannot be decided in OPA: the consume's OPA input carries the challenge id, while the
challenge's party and its device-signed approval id exist only in sca-service's database. The
domain is the only layer that sees them.
`rules.yaml four_eyes.exemptions.scaChallenge.consume` lists the principal under ADR-0280's bar:
it is a `service-account-*` identity and a ceremony-only caller, and no human path rides on it.

**D2 — account-service: an ownership-verification projection.** A new action,
`account.verifyOwnership`, guards `POST /api/v1/accounts/ownership-verifications`. The request
body is `{iban, partyId}` and the answer is exactly `{owned, active}`. An unknown IBAN and
another party's IBAN both answer `owned=false`, so the endpoint is not an existence oracle and
returns no account data. The IBAN travels in the body so it stays out of access logs. Pension
gets that action and nothing else. `account.read` stays denied to it.

**D2a — the projection names the account only to its owner's claim.** When `owned` is true the
answer also carries the account's `accountId`, because a caller that binds a mandate or an order
to that account needs it (#12387 item 3). It is never present when `owned` is false. The
projection also has a second declared caller: sdd-service's own client
(`service-account-openbank-sdd`), for D6.

**D5 — domestic-payment: a scoped payment initiator.** `rules.yaml: scoped_payment_initiators`
declares pension-service for `domestic-payment.create`, with `debtor_account_config` naming the
config key of its one debtor account, the pension payout account. The policy
(`service-scoped-payment-initiator`) reads `principal`, `service` and `actions`. The domain
resolves an `InitiatorScope` from the principal. Pension may pay only **from** the configured
account, and an unconfigured account permits nothing. Any other ROLE_API-only machine is
`Undeclared` and may pay from no account at all. That second rule is what makes it safe to add
`ROLE_API` to the create endpoint's `@RolesAllowed`. Staff and the operator-role edge are
unchanged. The domestic scheme rule already restricts payments to CZK. The per-step
`Idempotency-Key` stays mandatory, and the scope is checked before the key is claimed.

**D6 — sdd-service: a scoped mandate initiator.** The same register declares pension-service
for `sdd.create` and `sdd.delete`, with `creditor_identifier_config`. sdd-service's
`ScopedMandateService` checks three things. Pension may register only mandates whose
`creditorIdentifier` is its configured SEPA creditor identifier, the pension collection. It
must state the subject `partyId`. account-service's projection (D2) must confirm that the
debtor IBAN is owned and active by that party, and that its `accountId` is the account being
mandated. Pension may cancel only mandates whose creditor is its own. Every refusal is a 403,
before anything is persisted.

**Why the account and creditor values are not in OPA.** Both are environment configuration, not
repo data, and the ownership fact lives in account-service. So OPA decides "this identity, these
actions", the domain decides "this account, this creditor, this party", and a parity test in
each provider holds the code to the `rules.yaml` declaration.

**D3 — every scoped call is audited with actor and subject.** Each consume and each verification
emits an `AuditEvent`. `actorId` is the calling principal and `resourceId` is the data subject's
party id. On a refusal the result is `DENIED`. The trail then answers "which machine touched
which customer".

**D4 — the pattern generalises by declaration, not by copy.** A future scoped consumer adds an
entry to sca-service's `ReservedNamespace`, or one line to the account-service rule for
`account.verifyOwnership`. Each is a reviewed, test-pinned code change. Neither is a matrix
grant.

## Alternatives considered

- **Identity grant on the existing actions (#12385 as filed)**: the smallest diff, and the shape
  the edge and shared clients already have. Rejected because it grants the whole settlement gate
  and the full account read to a client that needs one namespace and one boolean. The narrowing
  would rest only on facts the caller states.
- **RFC 8693 delegated user context**: pension calls with a token whose `sub` is the customer
  and whose `act` is pension. It is the strongest model in principle, because a stolen pension
  credential alone would be useless. Rejected for now because it would need a customers-realm to
  operator-realm exchange, which ADR-0065 and ADR-0331 rule out. It also cannot serve
  pension's workflow and scheduler steps, which run with no live customer session.
  ADR-0177 (workload identity) is the place to revisit this. D1 and D2 remain correct under it,
  because the domain checks do not depend on how the caller authenticated.
- **A dedicated `PENSION_OPERATION` SCA purpose**: this would give pension its own push text
  (#12385 item 3), and the scope could key on purpose rather than a string prefix. Deferred. It
  is a contract change on both sides, and pension's adapters (#12401) and customer-edge (#12359)
  raise `APPROVAL` today. The reserved namespace sits in the same device-signed field the
  binding already compares, so scoping does not depend on the new purpose. When the purpose
  lands, `Reserved(PENSION)` gains it as an alternative.
- **Putting the namespace check in OPA**: the consume's OPA resource is the challenge id. Rego
  would see the namespace only if the caller stated it, which is the wrong source. The stored,
  device-signed value exists only in sca-service's database, so the domain is the only layer
  that can decide it.

## Consequences

**Positive**
- A stolen pension credential can spend only a challenge the customer signed **for a pension
  operation**. It can learn only whether a given IBAN belongs to a given party.
- Defence in depth: OPA denies every other action to the identity, and the domain refuses every
  other challenge. Either layer alone still holds the line pension needs.
- No other consumer can spend a customer's pension signature.

**Negative**
- The reserved namespace is a string prefix. A future approval flow that picks `pension-` ids
  for an unrelated purpose would collide. The prefix is pinned by tests and documented here.
- The beneficiary check (#12401) used the holder's party id from `account.read`. With D2 it asks
  "is this the claimant's IBAN" instead. That is the question it needs answered, and the
  consumer adapter changes accordingly.

**Neutral**
- `shared_m2m_matrix_write_grants` stays empty: nothing here touches `role_action_matrix`.

## Compliance impact

- PCI DSS: not applicable, because no card data is involved.
- DORA: not applicable. No change to ICT risk management, incident handling or third-party arrangements.
- GDPR: data minimisation. The ownership verdict replaces a full account read for this caller,
  and each verification is audited against the data subject.
- PSD2: the strong customer authentication evidence is spent only on the operation class it was
  raised for. Dynamic linking and single use are unchanged.
- CNB: not applicable. No change to regulatory reporting.

## References

- #12385, #12350, #12401 (pension identity/SCA adapters), ADR-0334
- ADR-0021 (SCA settlement gate), ADR-0169 (document-bound challenges), ADR-0206 (resource-scoped M2M grant)
- ADR-0280 (four-eyes service-account exemptions), ADR-0065 / ADR-0331 (realm separation), ADR-0177, ADR-0224
