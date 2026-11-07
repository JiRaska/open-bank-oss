---
date: 2026-11-06
decision-status: proposed
delivery-status: shipped
authors: [OpenBank contributors]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, governance, gitops]
summary: "Business ownership is declared once in per-module governance against a shared taxonomy, independently of namespaces; operational inventories, catalogs and cost allocation derive the same assignment."
---

# ADR-0364 — Canonical business domain ownership independent of deployment isolation

## Context

Governance, registry overrides, namespace discovery and map editorial metadata assigned
business ownership independently. Pensions and Wealth were classified as Open Banking or
Platform; lending, accounting and reporting disagreed across views. Unknown workloads were
silently assigned to Platform. These classifications also affected cost allocation.

A business domain, a bounded context, an isolation namespace and participation in a flow
answer different questions. A service can post to the ledger or participate in a payment
without becoming owned by Accounting or Payments. Namespace comments attributing a
“domain = namespace” rule to ADR-0037 are inaccurate: that ADR concerns AnaCredit.

## Decision

1. `openbank-libs/governance/domain-taxonomy.json` owns the versioned identifiers,
   bilingual labels, capability definitions and business/supporting/platform distinction.
   Each module's `governance.yaml:dataDomain` owns its one primary assignment.
2. Derive governance, catalog membership, registry groups, discovery classification,
   dashboard, API catalog, lineage, FinOps, public inventory and map groups from those declarations.
   New runnable modules cannot omit ownership. Unknown observed workloads remain visible
   as Unclassified; unknown ownership is never a claim of Platform ownership.
3. Use the responsibilities reviewed in [domain-boundaries.md](../architecture/domain-boundaries.md).
   Pensions contains participant contracts and provider fund operations as separate contexts
   (ADR-0334). Wealth owns declared holdings (ADR-0301), not implied custody or trading.
   Accounting, Lending, Cards, Treasury, Products, Risk and Reporting have explicit domains.
4. Preserve existing bounded-context models, write ownership, tenant isolation, namespaces,
   workload identities, API routes, topics, database ownership and access controls.
   Shared capability ownership does not merge stores or grant cross-context access.
   Runtime health and discovery overlay current published ownership as metadata only;
   operational targets and BFF exposure remain pinned to the deployed image.
   MCP tools derive their image metadata from those same packaged declarations and taxonomy.
   The tools/list relay overlays published ownership without changing tool identities,
   capabilities, argument schemas or execution responses.
   Declared packaged resource inputs drive CI, consumer image rollouts and immutable
   artifact comparisons, so metadata-only updates reach the runtime consumer.
5. Publish the expanded taxonomy at the separate `admin-ui/content/domains-v1` evidence
   prefix. Preserve the legacy selector for older images and rollback. New readers validate
   the taxonomy discriminator and catalog/governance agreement; incompatible content uses
   the explicitly stale, coherent image fallback. Expand exact publication permissions
   before publishing the versioned prefix. Image and publisher rollouts may overlap safely.

## Alternatives considered

- Move only map nodes: leaves governance, costs and operational views contradictory.
- Infer ownership from namespace: conflates isolation, shared infrastructure and business meaning.
- Rename namespaces, databases and topics: introduces operational migration without fixing
  competing classification sources and without evidence of a write-ownership defect.
- Keep a Platform catch-all: turns missing evidence into an incorrect ownership assertion.

## Consequences

The taxonomy can evolve through reviewed source changes. Generated type/schema drift,
missing runnable declarations and mismatched published membership fail checks. Cost-flow
membership remains many-to-many and independent of primary ownership. AWS infrastructure
cost categories remain distinct from business-domain showback.

No database conversion, message replay or business transaction migration is required.
Rollback reverts the reader, source assignments and generated artifacts together; legacy
publication permissions and selectors remain available. The versioned publisher can fail
safely while its narrow infrastructure permission expansion is pending.

## Compliance impact

Classification improves consistent ownership and evidence interpretation without changing
money movement, authentication, authorization, retention or regulatory computations.
Required money-path review remains applicable to affected service governance declarations.

## References

- [Implementation and responsibility review](../architecture/domain-boundaries.md)
- [Issue #14951](https://github.com/JiRaska/open-bank-oss/issues/14951)
- ADR-0002, ADR-0051, ADR-0062, ADR-0071, ADR-0301, ADR-0315, ADR-0334 and ADR-0337.

### Consumer boundaries

Navigation destinations declare their owning service, not a second domain table. The server
resolves those identities through the same reviewed ownership snapshot used by the fleet
views and seeds the first render. Domain headings use canonical labels; role-specific shortcuts
and cross-domain operational views remain separate. Permissions, upstream routes and operational
allowlists do not derive from these presentation labels.

FinOps business-domain showback joins service footprints to governance. AWS billing categories
and namespace-level resource observations are independent dimensions: a shared namespace must
not be attributed to one service or treated as one business domain. Application cost footprints include Deployments and Rollouts. Overlay duplicates represent
one namespace/workload identity; distinct tenant namespaces contribute independently before
rolling up to the owning service. Workload naming exceptions use the existing operational
registry, not suffix guesses or another domain table. Cluster topology likewise
shows physical application namespaces without claiming one namespace per business domain.

Both MCP registries package the canonical taxonomy and service declarations as runtime inputs.
A multi-service tool reports one business domain only when all declared source owners agree;
unknown or conflicting ownership is explicitly unclassified. This metadata does not grant a
capability, alter consent scope, or change execution. Customer analytics aggregate types and
approval queue identifiers retain their contracts and use distinct labels rather than claiming
to be business domains.
