<!-- SPDX-License-Identifier: Apache-2.0 -->
# Business domains and service boundaries

Decision: [ADR-0364](../adr/0364-canonical-business-domain-ownership-independent-of-deployment-isolation.md).

Business ownership is declared in each module's `governance.yaml:dataDomain`. The allowed
identifiers, bilingual names and descriptions live in
[`domain-taxonomy.json`](../../openbank-libs/governance/domain-taxonomy.json). The catalog,
governance inventory, operational views and public inventory consume these declarations;
presentation code must not maintain a competing service-to-domain assignment.

A business domain groups capabilities that share business meaning. A bounded context owns
its model, invariants and writes. A deployment namespace provides runtime isolation. A
business flow uses several contexts, so participation in a payment or pension flow does
not transfer ownership to the flow's domain. These are separate concepts.

## Responsibility review

| Domain | Responsibility and boundary |
|---|---|
| Accounts and deposits (`core`) | Account lifecycle, balances, deposit interest and customer statements. The existing identifier remains for compatibility; it no longer means every central banking service. |
| Financial accounting | Ledger books and posting orchestration. Journal entries record other domains' financial effects without owning their contracts or lifecycle. |
| Lending | Loan origination, contracts, repayment, servicing and provisioning. Being paid through a payment rail does not make a loan a payment aggregate. |
| Payments | Payment instructions, schemes, clearing and settlement. Customer FX conversion remains here; the bank's own-book FX deal belongs to Treasury. |
| Cards | Card issuance, processing, disputes and chargebacks. Card transactions use payments and accounting but retain card-specific lifecycle ownership. |
| Wealth | Declared off-platform holdings and wealth composition (ADR-0301). The classification does not claim custody, investment execution or portfolio management exists. |
| Pensions | Participant contracts, contributions, benefits and transfers; provider-side funds, units, NAV and investment operations (ADR-0334). These are two bounded contexts inside one business domain. |
| Treasury | The bank's deal lifecycle, liquidity and funding (ADR-0315). Treasury posts through the ledger API; it does not own the ledger's storage. |
| Products and pricing | Product definitions, composition, fees and billing. A shared product description does not own a customer's pension, loan or account contract. |
| Party and identity | Supporting contexts for party reference data and onboarding, identity documents, authentication and delegated authority. Grouping them does not merge their models, databases or authorization responsibilities. |
| Financial crime and audit | KYC due-diligence cases, AML, sanctions, fraud investigation, context evidence and audit. KYC case management is distinct from party master data; `context-service` is an investigation graph, not a marketing audience store. |
| Risk | Balance-sheet risk, capital, liquidity, valuation, IRRBB and limit assessment (ADR-0313/0314). It is not merely a lending scorer and does not own Treasury's deal execution. |
| Regulatory and tax reporting | FINREP/COREP, credit reporting and statutory/tax returns. Consuming pension data does not make reporting part of the participant or fund aggregate. Tax residency remains a party attribute. |
| Customer engagement | Campaigns, incentives, referrals, loyalty, communication and notification delivery. Delivery is a supporting context within this grouping; it does not establish the originating business event. |
| Open banking | Third-party access, PSD2 consent and provider registration. Pensions, Wealth, loyalty and delegation are not Open Banking merely because they expose APIs. |
| Channels | Customer/operator entry points and composition, including staff agent/MCP access. A channel calls domain APIs and does not acquire ownership of their data. |
| Document services | Shared document generation, signatures, evidence and document lifecycle. Account statement business ownership remains with accounts even when it uses document infrastructure. |
| Technical platform | Engineering automation, assurance agents, analytical transport and simulation infrastructure. This is an explicit responsibility, never the fallback for an unknown module. |

The review preserves existing ownership where it already matches the implemented model.
It does not create new services solely to match product labels. In particular,
`pension-service` owns participant contracts and orchestrates the provider through a
`FundAdministrationPort`, while `pension-fund-service` owns funds and unit/NAV operations.
Their models must remain distinct even though both are classified as Pensions. Distributor
mode binds the same provider port to an external provider (ADR-0334); business ownership
does not depend on that deployment choice.

## Operational compatibility

Several namespace manifest comments use the historical shorthand “domain = namespace”
and attribute it to ADR-0037, which actually concerns AnaCredit. This document corrects
that terminology for business classification only. Namespace isolation continues to apply to deployed contexts;
it does not imply a one-to-one mapping from business capability to namespace. A pensions
business domain can span `pension` and `pension-fund`; a shared accounting service can
have separately isolated bank and pension-company deployments (ADR-0337).

This classification migration changes no namespace, workload name, database, REST URL,
Kafka topic, authorization principal, RBAC grant or network policy. Those operational
identities remain the compatibility boundary. Service discovery resolves a known workload's
business domain from the canonical inventory, separately from its namespace. Unknown
ownership is shown as unclassified; it must not masquerade as a platform assignment.

The API catalog uses the same primary ownership as the service map. Discovery and
static health reads overlay ownership from one validated published catalog, while their
operational target registry remains image-pinned. Metadata publication cannot change
ports, tenant selection, routing or the BFF allowlist. This allows reviewed ownership
updates without waiting for an unrelated image rebuild.

FinOps domain rollups follow ownership. A cross-domain flow's showback continues to include
all required services and may overlap with other flows (ADR-0062); those flow totals must
not be interpreted as a partition of fleet spend. Card-specific alert domain labels use
`cards`; alert expressions and service identities remain unchanged.

## Verification and rollback

The acceptance conditions are complete and valid declarations for governed runnable modules,
agreement among catalog, governance, discovery, operational views and public inventory,
explicit handling of unknown ownership, and coverage proving that Pensions, Wealth, Treasury,
Lending and Cards cannot silently return to Platform. Generated artifacts are produced by
their generators. The schema is expanded before readers consume new identifiers.

The publisher and new default reader use `admin-ui/content/domains-v1`, leaving the
legacy `admin-ui/content` pointer untouched. This isolates the enum change from older
server images even when publication and image deployment finish in either order. An explicit `OPENBANK_ADMIN_UI_CONTENT_SNAPSHOT_URL` still takes precedence;
pointing it at a legacy bundle causes validated image fallback rather than reinterpretation.

Expand the evidence writer's exact selector, snapshot and ListBucket prefixes through the
reviewed infrastructure change before first publication to the versioned prefix. The legacy
prefix grants remain for rollback. No new principal, action or bucket-wide permission is
introduced. If infrastructure has not yet been applied, publication safely fails and the new
image uses its baked inventory; the old pointer remains usable by old images. This repository
change does not apply infrastructure or publish evidence itself.

Published catalog and governance datasets both carry
`domainTaxonomy: openbank.domain-taxonomy/v1`. New readers reject a pre-migration bundle
or inconsistent ownership across those datasets and use the coherent image-baked fallback,
visibly marked stale, until the updated publisher runs. Older readers ignore the additive
dataset fields. The migration requires no database conversion or message replay. Deploy the
generated inventory together with readers that understand the taxonomy. Roll back the complete change through a reviewed
revert, including readers and generated artifacts, rather than reverting declarations alone.
