# ADR-0152 — Single-tenancy boundary statement

Date: 2026-07-02
Decision-Status: Accepted   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: N/A — decision-only
Author(s): jiri.raska

## Context

Every deployment of OpenBank today implicitly assumes one bank per
deployment: Postgres-per-service (ADR-0009), the money-path OPA policies
(ADR-0034), and the ledger-as-golden-source model (ADR-0039) all reason
about "the bank," singular, with no tenant dimension anywhere in the
schema, the domain model, or the authorization model. This has never been
decided — it has only ever been assumed, which is precisely the condition
under which a well-meaning future change (e.g. "let's add a `tenant_id`
column so we could support a second brand on this cluster someday") gets
made piecemeal, in one service at a time, without ever resolving whether
multi-tenancy is actually a goal. A tenant dimension threaded halfway
through the domain layer — present in some entities, absent in others — is
worse than either a clean single-tenant model or a deliberately designed
multi-tenant one.

## Decision

We will declare single-tenant-per-deployment as an architectural invariant:
one OpenBank deployment (one cluster, one GitOps environment, one set of
Postgres instances) serves exactly one regulated banking entity. A second
bank wanting to run OpenBank runs its own deployment, from the same
codebase, via the existing GitOps templating (ADR-0027) — this is the
"multi-tenancy" the platform supports, at the *operator* layer, not inside
the domain. No service's domain model, database schema, or OPA policy may
introduce a tenant-scoping dimension; a genuine future need for
single-cluster multi-tenancy (multiple regulated entities sharing one
deployment) requires a new ADR that explicitly supersedes this one, because
it changes load-bearing assumptions in the ledger, authorization, and data-
residency model simultaneously and cannot be introduced as an incremental
patch to any single service.

## Alternatives considered

- **Design multi-tenancy now, before any tenant exists.** Rejected — this
  is speculative complexity with no current customer driving the
  requirement; it would touch the ledger's golden-source model
  (ADR-0039), every service's Postgres schema (ADR-0009), and the OPA
  policy model (ADR-0034) simultaneously, for a need that does not exist
  today and may never.
- **Say nothing and let single-tenancy remain an unstated assumption.**
  Rejected — this is the status quo, and it is exactly the condition that
  invites an incremental, undecided drift toward partial multi-tenancy,
  which is architecturally worse than either clean alternative.
- **Declare multi-tenancy support via schema-per-tenant within a shared
  deployment (a middle ground).** Rejected — even this "lighter" form
  still requires deciding tenant-scoping in the ledger and OPA policy
  layers; if a genuine need arises it deserves a full ADR weighing this
  option properly, not a default reached by omission here.

## Consequences

**Positive**
- Removes ambiguity that could otherwise lead to inconsistent, partial
  tenant-scoping creeping into individual services over time.
- Gives a clear, high bar for the alternative (a full superseding ADR) if a
  genuine multi-tenant requirement ever appears, rather than allowing it to
  be decided implicitly by whichever service touches it first.
- Simplifies data-residency and GDPR controllership reasoning: one
  deployment, one controller, one regulator relationship — no ambiguity
  about which tenant's data a given record belongs to.

**Negative**
- Every additional bank that wants to run OpenBank pays the operational
  cost of a full separate deployment rather than joining a shared,
  amortized cluster — an explicit, accepted trade-off in exchange for
  architectural simplicity and regulatory clarity.

**Neutral**
- Does not affect internal multi-currency/multi-pocket modeling
  (ADR-0024/0109/0110), which is a per-customer, not per-tenant, dimension
  and is unaffected by this decision.

## Compliance impact

- PCI DSS: not applicable directly.
- DORA: not applicable directly.
- GDPR: Art. 28 (clear controller/processor boundary) — a single-tenant
  deployment gives an unambiguous data controller per deployment, which is
  a compliance-positive simplification rather than a burden.
- PSD2: not applicable directly.
- CNB: not applicable directly — one deployment per regulated entity aligns
  cleanly with the standard model of one banking license per legal entity.

## References

- ADR-0009 (Postgres per service) — the schema-isolation model this ADR
  declares tenant-free.
- ADR-0034 (unified OPA authorization) — the policy model this ADR declares
  tenant-free.
- ADR-0039 (ledger as golden source) — the ledger model most sensitive to
  an incremental tenant-scoping change.
- ADR-0027 (cloud-agnostic in-cluster substrate) — the GitOps templating
  mechanism that is the actual answer to "a second bank wants to run this."
