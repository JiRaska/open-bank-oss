# ApprovalItem — the canonical unified-inbox contract (ADR-0227 D1/D3)

`openbank-libs-domain` owns the one shape every service's pending-approval queue projects
into, so the federated inbox (D2) has a single vocabulary to merge and filter on (D3):

- `ApprovalItem` — the canonical inbox item: `id`, `domain`, `action`, `summary`,
  `amount`, `currency`, `riskClass`, `maker` (`ApprovalMaker`: id, channel, captured-at),
  `state`, `payloadRef`.
- `ApprovalMaker` — maker provenance captured at creation, never inferred later.
- `ApprovalRiskClass` — `MONEY_PATH | DESTRUCTIVE | OPERATIONAL | UNKNOWN`. The authoritative
  source is the action-class metadata in `openbank-libs/governance/rules.yaml`
  (`four_eyes.verbs` / `four_eyes.actions` / `money_path_services`); the approval store does
  NOT carry the classification, so a projection made without a classifier lands on `UNKNOWN`
  and the admin-UI BFF overwrites it. Do not classify by verb heuristics — the rules.yaml
  lists exist because action-name-alone guesses were wrong twice (issue #395, the #938
  follow-up).
- `PendingApproval.toApprovalItem(domain, riskClass)` — the projection. `domain` is the
  caller's service domain; the store is per-service and carries none.

`openbank-libs-runtime`'s `ApprovalEndpointSupport` (the shared body of every service's
checker-facing maker-checker endpoint, ADR-0155) now serves that projection:

- `listPending` maps each store row through `toApprovalItem` and serves it through the
  additively-extended `ApprovalResponse`. All six new wire fields (`summary`, `domain`,
  `amount`, `currency`, `riskClass`, `payloadRef`) are optional, so the change is additive
  per the ADR-0048 api-contract gate and each service's openapi MINOR bump is
  backward-compatible.
- `derivedDomain()` derives `domain` from `quarkus.application.name` with the same
  normalisation rest.rego applies to `money_path_services` (strip `openbank-` and a trailing
  `-service`); it falls back to `unknown` only when the property is absent (e.g. a plain unit
  test). The BFF keys items off its own source list, so a mislabelled domain degrades
  display, never routing.

Honest field sourcing — consumers of any service's approval endpoints must know this:

- `amount` / `currency` are `null` for every record today. The maker's original request body
  is NOT persisted — only its SHA-256 `requestFingerprint` and the bounded summary are.
  A future phase that persists a structured amount will fill these; until then the D3
  amount-band filter has nothing to match and must treat these items as outside every band,
  never as "zero amount".
- `payloadRef` is `null` today for the same reason: the fingerprint is a one-time integrity
  digest, not a retrievable pointer. The field is the slot a future payload-pointer (e.g. an
  object-storage key) occupies.
- `riskClass` is `UNKNOWN` from the service side (see above); the BFF re-derives the
  authoritative class per action from rules.yaml.

What did NOT change: reading `quarkus.application.name` is not a CDI bean presence, so the
four-eyes invariant (maker identity only from the security identity recorded at creation,
never from the request body) is untouched, and self-approval is still refused by
`ApprovalStore.decide`.
