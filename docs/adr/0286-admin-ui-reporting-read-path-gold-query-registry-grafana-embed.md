---
date: 2026-09-06
decision-status: proposed
delivery-status: planned
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [analytics, admin-ui, regulatory-reporting]
summary: "Admin UI reads the ClickHouse warehouse two ways: a governed server-side query registry over gold marts for authoritative reporting, and embedded Grafana for exploration — never raw SQL from the browser."
---

# ADR-0286 — Admin UI reporting read-path: governed query registry over ClickHouse gold with embedded Grafana

## Context

ADR-0022 built the write-path of the analytics layer — event-fed bronze/silver/gold in
ClickHouse — and the Admin UI already has a working *read* precedent: the customer-360 and
onboarding-funnel BFF routes query `openbank_analytics` over the ClickHouse HTTP interface
with server-side SQL, a permission gate and a typed `available: false` degradation contract
(ADR-0210). But there is no *decided* read-path for the reporting workloads now arriving:

- **Risk reporting** is being added now; **financial and management reporting** follow.
- The regulatory page (`openbank-admin-ui/src/app/regulatory/page.tsx`) is a hand-built
  catalogue whose only live previews come from `finrep-service` over the ledger trial
  balance — nothing reads the warehouse, and the FINREP/COREP preview correctly does not
  (a regulator-facing return needs a frozen trial balance, not an eventually-consistent
  projection).
- Grafana already has a ClickHouse datasource, Keycloak SSO, a network policy into
  `analytics:8123` and a business-warehouse dashboard-as-code — an exploratory surface
  that exists but is not reachable from the Admin UI.

Without a decision, each new report lands as a one-off page with its own ad-hoc SQL — the
exact sprawl the medallion gold layer was designed to prevent — or the team is tempted to
expose ClickHouse directly to the browser, which would bypass every control the BFF pattern
carries (permission gating, parameter validation as the SQL-injection boundary, typed
degradation, audit).

The forces: reporting must keep **zero read load on OLTP** (ADR-0022 constraint 1);
browser-to-warehouse must be **governed** (a bank's reporting surface is compliance-relevant);
the set of reports will **change faster than code** (management reporting especially); and
the platform philosophy is **reuse what already runs** rather than adding infrastructure a
small team must operate (ADR-0045).

## Decision

**We will give the Admin UI two complementary read-paths into the ClickHouse warehouse, and
no others.**

**1. Governed query registry over gold marts — the authoritative reporting path.**
A single BFF route family (`/api/reporting/...`) serves *named, curated* queries defined
server-side: each entry is a gold-layer view (or a fixed SQL template over one) plus a
declared parameter schema, a required permission, and a typed response contract. The browser
selects a query by name and supplies validated parameters; it **never sends SQL**. This
generalises the customer-360 route's proven rules rather than inventing new ones:

- **The parameter validator is the injection boundary.** Parameters are typed (UUID, date,
  enum) and validated before they touch a query string — the same role `UUID_RE` plays in
  `/api/customer-360/[partyId]`, now declarative per registry entry.
- **Permission per entry.** Every registry entry names its `requireApiPermission` scope
  (e.g. `compliance:view` for risk, a new `reporting:view` for management reporting), so a
  report's visibility is reviewable in one file.
- **Always-200 typed degradation.** Registry routes return the `available: false` envelope
  on warehouse failure — a broken page and an empty warehouse render as different, calm
  states, per the existing house style.
- **Gold only.** Registry entries read gold marts/views shipped as ClickHouse migrations
  (`V__*.sql`), never bronze and never silver directly — one definition of each business
  figure, living with the schema, reviewed like schema. Adding a report = one migration +
  one registry entry, not a new route.

**2. Embedded Grafana for exploratory analytics.** Exploratory dashboards (risk trends,
funnel drill-downs, warehouse health) are Grafana dashboards-as-code embedded in Admin UI
pages via iframe (kiosk mode), reusing the existing ClickHouse datasource, Keycloak SSO and
the `observability → analytics:8123` network policy. Grafana remains an operator surface
*embedded*, not replaced: exploratory questions are answered by changing a dashboard
ConfigMap, not by shipping Admin UI code.

**3. The boundary between the two is explicit.** A figure that an operator will *act on or
submit* (risk limits, regulatory-adjacent numbers, management KPIs) goes through the
registry — versioned, permissioned, audited. A figure that an operator *explores* (trends,
distributions, ad-hoc cuts) lives in Grafana. When an exploratory question hardens into a
standing report, it graduates: the dashboard's SQL becomes a gold view and a registry entry,
and the Grafana panel links out.

**4. Regulatory returns stay on their services.** FINREP/COREP and future regulator-bound
artefacts remain sourced from their owning services over frozen OLTP state (finrep-service /
trial balance), not from the warehouse. The warehouse read-path is for *internal* reporting
and exploration; ADR-0022's eventual-consistency model is acceptable there and not at a
regulator boundary. The regulatory page may *link* to warehouse-backed context but must not
render return figures from it.

**5. No raw SQL console, no browser-direct ClickHouse.** There is no third path. The Admin
UI never holds a ClickHouse credential in the browser bundle and never proxies arbitrary
SQL; the registry is the only query surface, and its entries are the audit inventory of
"what the bank reports from the warehouse".

## Alternatives considered

- **Custom React page + bespoke BFF route per report (scale the customer-360 pattern
  unchanged)** — maximal control and a native look, and it is the proven pattern. Rejected
  as the *sole* path: risk + financial + management reporting means dozens of reports whose
  definitions change faster than release cadence; a route-per-report sprawl is what the
  registry exists to prevent. The pattern survives *inside* the registry, not per report.
- **Dedicated BI platform over the gold layer (Metabase / Apache Superset)** — ADR-0022
  names these as the intended gold consumers, and they are the strongest self-service
  option for management reporting. Rejected for now: it is another service to deploy, SSO,
  secure, patch and upgrade — against the ADR-0045 lightweight-over-cluster philosophy —
  while Grafana already covers the exploratory need with zero new infrastructure. Revisit
  if management reporting gains external (non-operator) business users who need self-service
  query building.
- **Browser-direct ClickHouse (HTTP interface from the React app)** — the "just embed
  ClickHouse" reading of the request. Rejected outright: it puts warehouse credentials in
  the browser, removes the permission gate, makes the injection boundary the client's
  problem, and loses the typed degradation contract — every control the BFF pattern exists
  to carry.
- **ClickHouse's built-in Play UI (`/play`) linked or embedded** — zero build cost. Rejected
  as an unauthenticated-by-design ad-hoc SQL console intended for operators and demos, not
  a governed reporting surface; it fails the same tests as browser-direct access.
- **Warehouse-sourced regulatory returns** — serving FINREP/COREP figures from gold marts.
  Rejected: a regulator-bound figure must reconcile to a *frozen* OLTP state at a sealed
  period close, and the warehouse is eventually consistent by design (ADR-0022). The
  frozen-trial-balance path through finrep-service stays the only source for returns.

## Consequences

**Positive**
- One audit inventory of warehouse reporting: the registry file *is* the list of what the
  bank reports from ClickHouse, with permission per entry — a compliance artefact for free.
- New reports ship as schema (gold migration) + configuration (registry entry), matching
  the change cadence of risk/finance/management reporting without route-per-report sprawl.
- Zero new infrastructure: Grafana embed reuses the existing datasource, SSO and network
  policy; the registry reuses the existing BFF pattern and ClickHouse client wiring already
  declared on the admin-ui Deployment.
- A clear graduation path (explore in Grafana → harden into gold + registry) keeps
  exploratory velocity without letting ad-hoc SQL leak into authoritative surfaces.

**Negative**
- Two rendering stacks to keep visually coherent (native React vs embedded Grafana); kiosk
  theming mitigates but does not eliminate the seam.
- The registry becomes a chokepoint that needs its own review discipline: a registry entry
  *is* a security-sensitive change (new data exposure), so registry diffs warrant the same
  scrutiny as a new endpoint.
- Grafana embed adds an Admin UI → Grafana dependency and network path that must be
  maintained (iframe auth flows through Keycloak SSO; a broken SSO shows as a broken tile).

**Neutral**
- Regulatory returns are untouched — finrep-service remains their source; this ADR adds
  context surfaces around them, not a new source for them.
- Gold-layer migrations grow in number; that is the medallion design working as intended
  (ADR-0022), not new complexity.

## Compliance impact

- PCI DSS: not applicable — the warehouse holds PII-masked, cardholder-free analytics data (ADR-0022).
- DORA:    not applicable — read-path presentation decision; no ICT resilience obligation engaged.
- GDPR:    engaged in the same posture as ADR-0022 — gold marts inherit PII-masked payloads and pseudonymous `aggregateId`; the registry's per-entry permission gate is the access-control surface over that data, and no registry entry may unmask what the sink masked.
- PSD2:    not applicable — no payment-initiation or customer-facing surface; Admin UI is an operator tool.
- CNB:     engaged — internal risk/finance reporting becomes warehouse-backed, and the registry provides a reviewable inventory of reported figures; regulator-bound returns are explicitly *not* sourced here (they remain on frozen OLTP state via finrep-service).

## References

- ADR-0022 — event-fed ClickHouse analytics layer (bronze/silver/gold, retention, consistency model)
- ADR-0210 — Customer-360 as a query over the silver layer (the BFF read-path precedent and its rules)
- ADR-0003 — transactional outbox (the extraction path the warehouse consumes)
- ADR-0009 — Postgres-per-service (what reporting must never read)
- ADR-0045 — lightweight-over-cluster operability philosophy (why Grafana-over-new-BI)
- `openbank-admin-ui/src/app/api/customer-360/[partyId]/route.ts` — the reference BFF implementation (validation-as-injection-boundary, always-200 degradation)
- `openbank-admin-ui/src/app/regulatory/page.tsx` — the regulatory catalogue this ADR surrounds but does not re-source
- `openbank-infra/gitops/components/analytics/clickhouse-grafana-network-policy.yaml` — the existing Grafana → ClickHouse path the embed reuses
