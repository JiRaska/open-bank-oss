# Kafka mTLS migration — topic-scoped enforcement of payment.scheme-accepted

Date: 2026-06-29
Status: Accepted
Decision-Status: Accepted
Delivery-Status: Partial
Author(s): Claude (paired with Jiří Raška)

## Context

ADR-0108 opened a Kafka inbound trust boundary: `transaction-service`'s
`SchemeAcceptedConsumer` turns any message on `payment.scheme-accepted` into a
settlement transaction in the money-path engine (no party-level SCA — it is
system-initiated). The transaction-service threat model §2a requires that only
the four payment rails may produce to that topic and only transaction-service
may consume it.

The sandbox Strimzi cluster (`openbank-cluster`, ns `messaging`) runs a single
**anonymous plaintext** listener `plain:9092` with broker setting
`allow.everyone.if.no.acl.found=true` — the "migration gate". `#2013`/`#2018`
added the §2a `KafkaUser` ACLs (`authentication: tls`) but **no TLS listener was
ever added**, so no client could present those identities; every client connects
as `User:ANONYMOUS`. Because `payment.scheme-accepted` was the only ACL'd topic,
SimpleAuthorizer made it deny-by-default and the anonymous (legitimate) consumer
was denied → settlements stuck in PROCESSING. `#2554` therefore **removed** the
ACLs to unblock settlement, explicitly deferring re-introduction until a real
TLS listener + client mTLS exist. This ADR is that follow-up.

Investigation while planning this change surfaced three facts that shape it:

1. **The four rails do not currently produce to `payment.scheme-accepted`.** The
   live ADR-0108 settlement path is **HTTP** (`SettlementAdapter` →
   transaction-service REST), not the event in the ADR's design. So the topic
   has no live producers today; the consumer reads an (empty) topic. This makes
   the *consumer-side* lockdown more important, not less: any message that does
   appear there is by definition unexpected and would still be booked.
2. **The consumer's live `group.id` was `openbank-transaction-service`**, but the
   §2a ACL granted the group `transaction-scheme-accepted-cg`. Enforcing with
   that mismatch would deny the legitimate consumer.
3. **`allow.everyone.if.no.acl.found` is a single cluster-global broker flag.**
   Flipping it to `false` makes the *entire* cluster deny-by-default. There are
   ~33 Kafka clients across ~23 namespaces (account, ledger, balance, party,
   kyc, aml, fraud, audit, notifications, …, plus kafka-ui and the Strimzi
   operators); none except these five would have mTLS + ACLs, so all would break
   at once.

The key enabling property of Strimzi's SimpleAuthorizer: a resource that carries
**any** ACL is deny-by-default for principals not in it, **regardless** of the
global gate; a resource with **no** ACL stays open while the gate is `true`. And
ACLs bind to the authenticated **principal**, not the listener — so an
unauthenticated client (`User:ANONYMOUS`, including anyone on `plain:9092`) is
simply not in the scheme-accepted ACLs and is denied. This means the §2a threat
model can be fully enforced for `payment.scheme-accepted` **without** flipping
the global gate and **without** migrating the other ~28 clients.

## Decision

**We enforce the `payment.scheme-accepted` trust boundary with a topic-scoped
mTLS control, and we explicitly do _not_ flip the cluster-global allow-everyone
gate in this change.** Concretely:

1. **Add a TLS-auth listener** `tls:9093` (`authentication.type: tls`) to the
   Strimzi `Kafka` CR, alongside the kept `plain:9092`. Mutual TLS ⇒ a client
   with no cluster-CA-signed client cert cannot connect on `9093` at all.
2. **Re-introduce the five `KafkaUser`s + ACLs**, named by **service identity**
   (one cert per service, not per topic-role): `sepa-payment`, `sepa-instant`,
   `domestic-payment`, `swift-service` (Write+Describe on
   `payment.scheme-accepted`) and `transaction-service` (Read+Describe on the
   topic, Write+Describe on its `.dlq`, Read on group
   `transaction-scheme-accepted-cg`). The consumer's channel `group.id` is
   aligned to `transaction-scheme-accepted-cg`.
3. **Wire all five services to connect over mTLS** on `9093`: SmallRye connector
   SSL config (PKCS#12 keystore/truststore), with the Strimzi-minted keystores
   and the shared cluster-CA truststore **projected from `messaging` into
   `payments`** by External Secrets' kubernetes provider (least-privilege `get`
   on exactly the six secrets; the CA *signing-key* secrets are never exposed).
4. **Leave `allow.everyone.if.no.acl.found=true`.** The scheme-accepted topic is
   enforced by its own ACLs; every other topic stays open so the rest of the
   fleet is untouched.

The four rail producer grants are provisioned now even though the rails settle
over HTTP today — they make the topic deny-by-default for everyone else and are
ready for when the ADR-0108 event path is wired. (Scope chosen with the owner:
wire all five, not consumer-only.)

## Alternatives considered

- **Flip `allow.everyone.if.no.acl.found=false` now (the literal #2013/#2554
  follow-up wording).** Correct *end-state* posture, but it is a cluster-wide,
  all-or-nothing change: ~33 clients across 23 namespaces would need mTLS + ACLs
  simultaneously or they break. Conflates a surgical money-path control with a
  fleet migration. Rejected for this change; see "Out of scope".
- **Consumer-only mTLS.** Only `transaction-service` strictly needs mTLS for the
  topic to function today (the rails use HTTP). Smaller blast radius, but leaves
  the producer identities un-provisioned and diverges from the ADR-0108 design.
  Rejected in favour of wiring all five (owner decision).
- **Reflector / kubernetes-replicator to copy the Strimzi secrets.** Not present
  in the platform; would add a new mechanism. Rejected — ESO is the established
  cross-namespace secret pattern (OIDC client secrets already use it).
- **Copy certs into OpenBao and project via the existing `vault-kv` store.**
  Breaks Strimzi's automatic cert rotation and adds manual key handling.
  Rejected.
- **Make the Strimzi User Operator watch `payments` so secrets land there.**
  Spreads Kafka identity CRs outside the `messaging` domain boundary (ADR-0037)
  and changes operator topology. Rejected.

## Consequences

**Positive**
- §2a is genuinely enforced: only the four rail principals may write
  `payment.scheme-accepted`, only `transaction-service` may read it; `ANONYMOUS`
  (plaintext or un-certed) is denied. Channel is encrypted + authenticated.
- Zero blast radius for the other ~28 clients — they keep using `plain:9092`.
- Cert rotation stays automatic (Strimzi rotates → ESO re-projects → pod reload
  on restart). No static secrets.
- Fixes the latent group-id/ACL mismatch and swift-service's hardcoded bootstrap.

**Negative**
- Two listeners and a partial-mTLS fleet are a transitional state that must be
  finished (the global gate flip) for full cluster hardening — tracked, not done.
- New cross-namespace coupling (ESO kubernetes provider + RBAC) and a bootstrap
  ordering dependency: payments pods can't mount their keystores until the
  KafkaUser secrets exist and ESO has projected them — staged apply per
  runbook 0008.
- `plain:9092` staying open means an attacker on the bus can still write to any
  *un-ACL'd* topic — a pre-existing, cluster-wide exposure this change neither
  widens nor closes.

**Neutral**
- Local dev/test are unchanged (connector SSL defaults to PLAINTEXT).
- ADR-0108's HTTP-vs-event settlement divergence is documented here but not
  changed.

## Out of scope — the cluster-wide gate flip

Flipping `allow.everyone.if.no.acl.found=false` is a **separate, fleet-wide
program**: migrate every Kafka client (per criticality tier — money-path, then
compliance/audit, then peripheral) to mTLS + per-service ACLs, verify each, and
only then flip the global gate. Tracked as its own initiative in **#2665**;
runbook 0008 captures the per-service recipe this ADR establishes as the template.

**Delivery note (post-merge).** mTLS + ACL enforcement landed and is e2e-verified
live (authorized producer consumed; anonymous and read-only principals denied
`Write`). One consistency item remains: the consumer group is baked in
`application.yaml` as `transaction-scheme-accepted-cg` and the DLQ as
`payment.scheme-accepted.dlq`, but the deployed image lags main, so the running
consumer still uses the old group `openbank-transaction-service` (allowed because
it carries no ACL while the gate is `true`) and SmallRye's default DLQ name.
These converge on the next image rebuild — blocked by an unrelated Pact
`can-i-deploy` gate, tracked in **#2664**. An env-var shortcut was tried and
reverted (#2649 → #2659): SmallRye cannot take hyphenated `mp.messaging` *channel*
config from env vars (channel discovery mis-maps the names and fails startup).

## Compliance impact

- PCI DSS: 4.2.1 (strong cryptography for transmission), 7.x/8.x (least-privilege
  access to the settlement event) — strengthened for this topic.
- DORA: ICT risk — reduces an unauthorised-settlement vector on the money path.
- GDPR: not applicable (no new PII; consumer logs already redact per §2a).
- PSD2: indirect — protects integrity of rail settlement booking.
- CNB: not applicable.

## References

- ADR-0108 — Rail settlement via transaction-service (the trust boundary)
- ADR-0037 — Domain = namespace (why KafkaUsers live in `messaging`)
- transaction-service threat model §2a
- #2013 / #2018 (premature ACLs), #2554 (their removal), runbook 0008 (cutover)
- #2602 (this migration), #2649 → #2659 (reverted env-convergence shortcut)
- #2664 (Pact gate blocking the image rebuild), #2665 (fleet-wide gate flip)
- ADR-0005 / OpenBao + External Secrets (the cross-namespace secret pattern)
