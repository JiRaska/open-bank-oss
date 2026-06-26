# Cross-service search contract (bounded fulltext + keyset pagination)

Date: 2026-06-01
Status: Accepted
Author(s): OpenBank platform

> **Amendment 2026-06-19 — Phase 1 complete.** `SearchRequest` + `CursorPage`/`CursorEncoder`
> ship in `openbank-libs` at `com.openbank.libs.api.search` and
> `com.openbank.libs.api.pagination`. Guardrails enforced centrally: page-size clamping
> `[1, 100]` / default 20, wildcard semantics (blank/`*`/sub-2-char → list mode), LIKE-
> metacharacter escaping, keyset-only cursor design. Comprehensive unit tests ship alongside.
> **Phases 2–4 remain** (account-service `pg_trgm` GIN search endpoint, product-catalog
> cursor pagination + auth gate, admin-ui search-field unification).

## Context

Every list/search field in the admin-ui is effectively non-functional today, and the cause
is in the backend, not the UI:

- **account-service** `GET /api/v1/accounts` accepts only `partyId` + keyset `limit`/`cursor`.
  It has no name / BBAN / free-text search. The admin-ui already sends `?q=`, `?name=`,
  `?surname=`, `?rc=`, `?bban=`; the service silently ignores them. Without `partyId` it 400s.
- **product-catalog** `GET /api/v1/products` filters `type`/`status`/`currency` in memory,
  has **no pagination**, and (a maturity gap) **no `@RolesAllowed`** at all.
- **openbank-libs** offers `CursorPage`/`PageInfo`/`CursorEncoder` but **no** shared search or
  request-bounding abstraction, so each service would reinvent page-size limits, wildcard
  handling, and input sanitisation — inconsistently, and with real DB-overload risk
  (`limit=1000000`, single-char `ILIKE '%a%'` full scans, unescaped `%` injected by users).

We want search that returns "everything I am authorised to see" — including a `*`/empty
"list all in scope" mode — while protecting the database by construction. The question is
where the responsibility lives: a single shared engine, or per-service SQL with a shared
contract.

## Decision

We will split the concern:

1. **Shared cost-guardrail contract in `openbank-libs`** (`api/search`): a `SearchRequest`
   value object normalised via `SearchRequest.of(q, limit, cursor, filters)`, returning a
   `com.openbank.libs.api.pagination.CursorPage<T>`. The library owns only the cross-cutting,
   DB-safety policy — **enforced once, centrally**:
   - **page size** clamped to `[1, MAX_LIMIT=100]`, default `DEFAULT_LIMIT=20`;
   - **keyset/cursor pagination only** (no `OFFSET` → constant cost at any depth);
   - **wildcard semantics**: blank, `*`, or a term shorter than `MIN_TERM_LENGTH=2`
     collapses to "list first page within authorised scope, no fulltext predicate" — a
     1-char `ILIKE` is unselective anyway, so this is both correct UX and DB-safe;
   - **`LIKE` metacharacter escaping** (`\ % _`) so user input matches literally and cannot
     turn a bounded term into a `%`-prefixed full scan;
   - a hard `LIMIT` is always present in the generated SQL, and services set a
     `statement_timeout` on the search query.

2. **Per-domain SQL stays in each service.** Which columns are searchable and which index
   backs them is a domain decision. Searchable text columns are indexed with PostgreSQL
   **`pg_trgm` + GIN** so `ILIKE`/similarity scales without a sequential scan; the migration
   ships as Flyway under the owning service.

3. **Authorisation is the endpoint's job, not the library's.** `SearchRequest` carries no
   identity; the library bounds *cost*, not *visibility*. Each search endpoint keeps its
   `@RolesAllowed` and adds the caller's party/tenant predicate so results are exactly the
   set the caller may see.

Rollout is phased, one PR per service (fleet-sweep style):

- **Phase 1 (this ADR + the libs contract).** Non-money-path; ships `SearchRequest` + tests.
- **Phase 2 — account-service.** Add `search(criteria, caller-scope)`, a `pg_trgm` GIN
  migration, an `openapi.yaml` bump + contract test. account-service is a **money-path**
  service ⇒ 2 approvals + threat-model update (ADR-0030).
- **Phase 3 — product-catalog.** Add cursor pagination **and** an auth gate.
- **Phase 4 — admin-ui.** Unify every search field on the contract: debounce, `*`/empty =
  list, an explicit "min. 2 characters" hint, and surfacing of `hasNextPage`/`nextCursor`.

## Alternatives considered

- **A single shared search engine in `openbank-libs` (generic query-spec → SQL).** One place
  for everything. Rejected: it would have to know every domain's columns, indexes, and
  authorisation shape, dragging framework/persistence concerns into a library whose domain
  layer must stay framework-free (ADR-0002). The guardrails are uniform; the SQL is not.
- **Per-service search with no shared contract.** Simplest to start. Rejected: it is exactly
  today's failure mode — each service re-derives (or forgets) page caps, wildcard rules, and
  input escaping, so DB-overload protection becomes best-effort and drifts per service.
- **OFFSET pagination.** Familiar. Rejected: deep pages degrade linearly and the existing
  account/ledger endpoints already standardise on keyset `CursorPage`.

## Consequences

**Positive**
- DB-overload protection (page cap, escaping, keyset-only, statement timeout) is enforced in
  one tested place; a service cannot opt out of it by accident.
- Consistent `*`/empty "show me everything I can see" semantics across every search field.
- Reuses the existing `CursorPage` response shape — no new pagination dialect.

**Negative**
- A small migration per searchable service (Flyway `pg_trgm` GIN index) and an `openapi.yaml`
  bump. account-service touches a money-path service ⇒ heavier review (2 approvals + threat
  model).

**Neutral**
- The library bounds cost, not visibility; correct authorisation scoping remains per-endpoint
  and is verified by each service's contract test.

## Compliance impact

- PCI DSS: not applicable (no cardholder data in scope).
- DORA:    not applicable.
- GDPR:    Art. 5(1)(c) data minimisation / Art. 32 — search returns only records within the
           caller's authorised scope; the library does not widen visibility. Free-text search
           over personal data (name, BBAN) is access-controlled and audited per existing
           service audit logging.
- PSD2:    not applicable.
- CNB:     not applicable.

## References

- ADR-0002 — hexagonal architecture (domain layer is framework-free; SQL stays per service).
- ADR-0030 — money-path change controls (account-service phase needs 2 approvals + threat model).
- `openbank-libs/src/main/kotlin/com/openbank/libs/api/pagination/CursorPage.kt` — response shape.
- `openbank-libs/src/main/kotlin/com/openbank/libs/api/search/SearchRequest.kt` — this contract.
- PostgreSQL `pg_trgm` (GIN-indexed trigram `ILIKE`/similarity) — per-service fulltext backing.
