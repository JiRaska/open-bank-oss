---
date: 2026-07-30
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, architecture, privacy-gdpr]
summary: "One entity-resolution contract per domain plus a global ⌘K palette: backoffice searches by name, email, phone, tax id, IBAN/BBAN, E2E id or card — never primarily by raw UUID; the same providers serve the MCP channel."
---

# ADR-0228 — Unified entity resolution and global search for backoffice

Relates: ADR-0055 (party search, trigram), ADR-0224 (MCP channel consumes
the same providers), ADR-0226 (audit of search itself).

## Context

The 2026-07 admin-UI audit's loudest finding was how a backoffice worker
finds things. Today:

1. **UUID is the primary lookup on the screens that matter most.** `/kyc`
   filters cases by raw Party UUID; `/transactions` leads with Account ID
   (UUID); `/audit` asks for an aggregate UUID; `/accounts/new` pastes two
   UUIDs. An operator with a customer on the phone has a name, an email, an
   IBAN — none of these screens take them.
2. **Global search is a painted prop.** The header shows "Rychlé hledání…
   ⌘K" as a static `<span>` with no input, no handler, no palette
   (`Header.tsx`).
3. **The one real search is narrow.** party-service `/parties/search`
   (ADR-0055) matches `legalName`/`tradingName` by trigram — no email,
   phone, tax id, national id, or IBAN — and sits behind a feature flag.
4. **Entities don't link.** `parties/[id]` links only back to `/parties`;
   `accounts/[id]` only to `/accounts`. The party → accounts → transactions
   → payment walk is copy-pasting UUIDs between tabs.

## Decision

We will give every domain a search-provider contract and the operator one
place to use all of them.

**D1 — Per-domain search providers.** Each service that owns a searchable
aggregate exposes a canonical `GET /api/v1/<domain>/search?q=&type=&limit=`
answering its business keys: party (name, email, phone, taxId, nationalId),
account (IBAN, BBAN, account-number fragment — largely exists), payment
(E2E id, creditor/debtor name, reference — largely exists), card (PAN
tail/token, holder), transaction (reference, counterparty, amount band —
exists). party-service's provider is extended beyond name-only.

**D2 — One entity-resolution facade.** A BFF route
(`GET /api/entities/resolve?q=`) fans out to the providers in parallel and
returns typed refs `{ type, id, label, route }` — the response is
UI-route-aware so the palette deep-links. Resolution queries are
audit-stamped (who searched what, ADR-0226 channel `ui`) — staff search of
customer data is itself a processing event.

**D3 — A real ⌘K palette** replaces the static header span: debounced
query to D2, grouped results (Klienti / Účty / Platby / Karty), keyboard
navigation, recent searches per operator.

**D4 — UUID is a fallback, never the primary key.** Screens that lead with
a UUID input switch to resolved-entity pickers (typeahead against D2); raw
UUID paste stays accepted for tooling but is never the suggested path.

**D5 — The same providers are MCP tools** (`entity_resolve`, and per-domain
`search_*` as they are granted), so the operator copilot (ADR-0224) and the
palette resolve identically — one search implementation, two channels.

## Alternatives considered

- **Per-screen filter improvements only** — rejected: it fixes inputs but
  not resolution ("find the customer first" stays manual); the palette is
  the actual daily driver for backoffice work.
- **Central search index (Elasticsearch/OpenSearch) now** — rejected as
  phase 1: a new stateful system with PII replication duties; federation
  over provider APIs delivers the UX without the compliance surface. The
  provider contract leaves room for an index behind the same facade later.

## Consequences

**Positive**
- The audit's worst UX defect (UUID-first backoffice) is removed from the
  four screens named in Context, plus a global entry point.
- Search becomes auditable by construction (D2) rather than invisible.
- The MCP channel inherits the same resolution — no second search stack.

**Negative**
- Provider coverage is a per-service sweep (most list endpoints already
  filter by business keys; the work is canonicalising shape and adding the
  missing party identifiers).
- Fan-out latency on the palette (parallel, short timeouts, cached
  per-query).

**Neutral**
- None.

## Compliance impact

- PCI DSS: card search MUST be by tail/token only — never full PAN (D1).
- DORA: not applicable — search availability is not an ICT-critical path.
- GDPR: staff search of customer data is purpose-bound and audit-stamped
  (D2); result lists are minimised (label + ref, no document content).
- PSD2: not applicable — resolution reads, it does not initiate.
- CNB: not applicable.

## References

- Audit evidence: `/kyc` (Party UUID filter), `/transactions` (Account
  UUID), `/audit` (aggregate UUID), `/accounts/new` (UUID paste),
  `Header.tsx` (dead ⌘K span)
- ADR-0055 (party trigram search), ADR-0224 D4/D5, ADR-0226
