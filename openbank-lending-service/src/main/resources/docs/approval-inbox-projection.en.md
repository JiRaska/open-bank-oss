# Approval inbox projection — lending

ADR-0227 D1 makes this service's checker-facing approval responses serve the canonical
unified-inbox projection. `ApprovalResponse` in `openapi.yaml` (contract version `1.38.0`)
gained six **optional** fields — `summary`, `domain`, `amount`, `currency`, `riskClass`,
`payloadRef` — populated by the shared `ApprovalEndpointSupport` (`openbank-libs-runtime`)
mapping store rows through the canonical `ApprovalItem` (`openbank-libs-domain`). The bump is
a MINOR (additive) one per ADR-0048; existing consumers are unaffected.

Field semantics for consumers of this service's approval endpoints:

- `summary` — bounded human-readable description of the paused request, persisted with the
  approval. `null` when the approval was created directly by application code rather than by
  the intercepting pause.
- `domain` — this service's inbox domain (`lending`), derived from `quarkus.application.name`
  (strip `openbank-`, strip a trailing `-service`). Informational; the federated inbox keys
  items off its own source list, so a mislabelled domain degrades display, never routing.
- `amount` / `currency` — **always `null` today**. The maker's original request body is not
  persisted — only its SHA-256 fingerprint and the bounded summary are. Absent never means
  zero; the D3 amount-band filter must treat these items as outside every band.
- `riskClass` — `UNKNOWN` from this service. The authoritative action-class classification
  lives in `openbank-libs/governance/rules.yaml` (`four_eyes` metadata) which the approval
  store cannot see; the admin-UI BFF re-derives it per action. Consumers must not classify by
  verb heuristics here.
- `payloadRef` — **always `null` today**. The persisted fingerprint is a one-time integrity
  digest, not a retrievable pointer to the maker's payload.

The four-eyes invariants are unchanged: maker identity still comes only from the security
identity recorded at creation, and self-approval is still refused by the store.
