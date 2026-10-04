---
date: 2026-10-04
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authn, customer-edge, testing, security]
followup: "#4348 — realm client, SYNTHETIC party, Vault secret, trusted-principals config and the identity gate are unbuilt; this ADR records the decision only"
summary: "A canary authenticates as a confidential customers-realm client whose service-account user carries one SYNTHETIC party_id and ROLE_CUSTOMER; no password, no impersonation, no cross-realm exchange. A gate pins its shape."
---

# ADR-0331 — Bank-owned synthetic customer identity: a customers-realm service account bound to one SYNTHETIC party

## Context

ADR-0252 decided that permanent bank-owned synthetic customers exercise real journeys in
production, tainted end to end. It did not decide how such a customer authenticates. That gap is
now the blocker for every customer journey in `openbank-libs/governance/journeys.yaml` that is
not anonymous: `wealth-declared-holding`, `account-overview`, `domestic-payment` and the rest all
name ADR-0252 phase 1 (#4348).

The rest of phase 1 is built on `main`:

- `PartyClassification.SYNTHETIC`, with creation restricted to `ROLE_ADMIN` (party-service).
- `SyntheticTaintRequestFilter`, which honours `x-openbank-synthetic` only for a principal listed
  in `openbank.synthetic.trusted-principals`. The list is empty in every environment.
- The taint on every `@RegisterRestClient` hop, across customer-edge's `UpstreamClient` (#12050),
  on outbox rows, Kafka headers and the ledger's `synthetic` dimension.

What is missing is a principal to put on that list: a caller that customer-edge accepts as a
customer and that a CronJob can use headlessly.

Three existing decisions constrain the answer.

- **ADR-0065** gives customers a dedicated `openbank-customers` realm, and states that the customer
  edge and the admin edge "share no realm, no client, and no proxy".
- **ADR-0066** makes customer authentication passkey-first, with no password ever created or used.
  The realm's only customer client, `openbank-app`, has `directAccessGrantsEnabled: false`.
- **ADR-0177** and **ADR-0224** use RFC 8693 token exchange, but each stays inside one realm: one
  for workload identity, the other for operator on-behalf-of exchange in the operator realm.
  Neither bridges the two realms.

customer-edge only accepts customers-realm tokens. The issuer is pinned to
`.../realms/openbank-customers`, roles come from `realm_access/roles`, and the party comes from
the `party_id` claim, falling back to `sub`.

## Decision

We will give each canary persona a **confidential client in the `openbank-customers` realm**,
authenticating with `client_credentials`. Its service-account user is the synthetic customer.

1. **One client per persona**, named `openbank-synthetic-<persona>`. The client is confidential
   with `serviceAccountsEnabled: true`. `standardFlowEnabled`, `directAccessGrantsEnabled` and
   `implicitFlowEnabled` are all false, so the client has no browser login and no password grant.
2. **The service-account user is the customer.** It holds exactly one realm role, `ROLE_CUSTOMER`.
   It carries a `party_id` user attribute naming one party-service party with
   `classification: SYNTHETIC`. The client emits that attribute through the same
   `oidc-usermodel-attribute-mapper` the app client uses, so customer-edge reads the canary
   exactly as it reads a customer and needs no code path of its own.
3. **The party is created first, by an admin.** `POST /api/v1/parties` with
   `classification: SYNTHETIC` already requires `ROLE_ADMIN`. The party holds no personal data,
   per ADR-0252.
4. **Trust is configured, not inferred.** customer-edge's `openbank.synthetic.trusted-principals`
   lists `service-account-openbank-synthetic-<persona>`, and nothing else from the customers
   realm. Downstream services list customer-edge's own service account, because that is the
   principal they see on every hop the edge forwards. Adding any principal to either list is a
   reviewed GitOps change.
5. **Provisioning follows the precedent of `openbank-synthetic-catalog-read`** (#10692).
   - The client is declared in `customers-realm-template.json`, without a secret.
   - The owner creates it in the live realm with `kcadm`, because realm import runs on cold start
     only.
   - The Keycloak-generated secret is put in OpenBao KV.
   - The journey CronJob reads that secret through an `optional: true` ExternalSecret, so an
     unprovisioned identity is a red run, never a crash loop and never a green one.
6. **A gate pins the shape.** `check-synthetic-customer-identity.py` will fail if any
   `openbank-synthetic-*` client in the customers realm template:
   - enables a browser, password or implicit flow;
   - holds any realm role other than `ROLE_CUSTOMER`;
   - lacks the `party_id` mapper.

   It also fails if a trusted-principals value in GitOps names a customers-realm principal that is
   not such a client.

## Alternatives considered

- **Cross-realm token exchange.** A canary service account in the operator realm exchanges its
  token for a customers-realm token of one synthetic user. This was the first proposal in #4348.
  It avoids a secret in the customers realm. Rejected: it builds exactly the trust bridge between
  the staff and customer realms that ADR-0065 forbids. An operator-realm credential could then
  mint customer tokens, so the most exposed realm would inherit the blast radius of the other.
- **Password grant for a synthetic user.** A normal customers-realm user with a password, logged
  in with the resource-owner password grant. This is the simplest to script. Rejected: ADR-0066
  rules out customer passwords, and it would need `directAccessGrantsEnabled` on a customer
  client, which re-opens a credential-stuffing surface the realm closed deliberately.
- **Same-realm impersonation exchange.** A confidential customers-realm client impersonates the
  synthetic user through token exchange. Rejected: Keycloak's standard token exchange does not
  impersonate a different user. The legacy path needs preview features plus fine-grained admin
  permissions, a broader and less stable surface than a service account that simply *is* the
  customer.
- **A test-only edge bypass.** customer-edge accepts an operator-realm token for synthetic
  callers. Rejected: a second trust path in the internet-facing edge is precisely what a canary
  must not need. A canary that does not authenticate the way customers do proves nothing about
  customer authentication.

## Consequences

**Positive**
- Every journey blocked on "no synthetic identity" can be built. The canary reaches
  customer-edge the way a customer does: same issuer, same role claim, same `party_id` claim.
- The design adds no password, no impersonation and no cross-realm trust. It needs no edge code.
- The taint switches on per persona, by listing one principal. Removing that one line switches
  it off.

**Negative**
- A canary client secret is a production credential in the customers realm. Its blast radius is
  bounded to one SYNTHETIC party with `ROLE_CUSTOMER`. It must be covered by the secret-rotation
  tiers of ADR-0099 like the other customers-realm confidential clients.
- Flows that need a customer's SCA (payments, card operations) are not unlocked by this identity
  alone. They need ADR-0252's canary authenticator (phase 3).
- Provisioning is a manual owner step, the same as for `openbank-synthetic-catalog-read`.

**Neutral**
- `customer-edge-admin` and `openbank-edge-webauthn` already show that the customers realm holds
  confidential service-account clients. What is new is that this one is a customer.

**Enforcement:** gate `synthetic-customer-identity` (`check-synthetic-customer-identity.py`),
introduced `advisory` with a `target_enforce_date`, per ADR-0144.

### Delivery check

```bash
# 1. The client exists with the pinned shape (expect one object, serviceAccountsEnabled true, no flows)
jq '.clients[] | select(.clientId|startswith("openbank-synthetic-"))
    | {clientId, serviceAccountsEnabled, standardFlowEnabled, directAccessGrantsEnabled}' \
  openbank-infra/gitops/components/keycloak/customers-realm-template.json
# 2. The gate exists and is enforced (expect: mode: enforced)
grep -A3 'id: synthetic-customer-identity' .github/gates/gates.yaml
# 3. customer-edge trusts exactly the canary principal(s) (expect: service-account-openbank-synthetic-...)
git grep -n 'OPENBANK_SYNTHETIC_TRUSTED_PRINCIPALS\|synthetic.trusted-principals' -- openbank-infra/gitops/components/customer-edge
# 4. A customer journey runs on it (expect: status: active)
grep -A6 'id: wealth-declared-holding' openbank-libs/governance/journeys.yaml | grep status
```

Until check 4 shows `active`, the honest delivery status is `planned` or `partial`.

## Compliance impact

- PCI DSS: not applicable — the canary holds no card and this identity unlocks no card flow.
- DORA: the decision exists so the bank detects customer-visible ICT incidents before customers
  report them, which is ADR-0252's DORA rationale. This ADR adds no separate obligation.
- GDPR: canary parties hold no personal data (ADR-0252), so no data subject is created. The
  synthetic taint keeps canary activity out of real customers' views.
- PSD2: not applicable to this decision — the identity performs no SCA. The canary SCA
  authenticator is ADR-0252 phase 3.
- CNB: not applicable — no reporting duty changes. Exclusion from regulatory aggregates is the
  taint's job under ADR-0252.

## References

- ADR-0065 — customer-facing edge and the dedicated customer realm (the separation this preserves)
- ADR-0066 — passwordless customer authentication (why no password grant)
- ADR-0099 — automated secret rotation
- ADR-0144 — gate graduation
- ADR-0177, ADR-0224 — same-realm token exchange (why not cross-realm)
- ADR-0252 — synthetic customer fleet (the decision this completes)
- #4348 — ADR-0252 phases 1-4; #9829 — the wealth journey this unblocks; #10692 — the
  `openbank-synthetic-catalog-read` provisioning precedent; #12050 — taint across customer-edge
