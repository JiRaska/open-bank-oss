# Origination commands — Lending

ADR-0211 adds three explicit commands to the loan origination state machine, on top of the
generic `advance`:

- **withdraw** — pre-disbursement abandonment. Any non-terminal state the transition graph
  permits (DRAFT … AWAITING_SIGNATURE) moves to WITHDRAWN. SIGNED and later have no WITHDRAWN
  edge — a signed contract unwinds through the ADR-0215 exit paths — so the machine refuses
  with 422. This is NOT the statutory CCD2 cooling-off withdrawal, which runs post-disbursement
  on the live loan (`POST /api/v1/lending/loans/{id}/withdrawal`).
- **submitDocuments** — the explicit DOCS_REQUIRED → ASSESSMENT step. Only an application
  actually sitting in DOCS_REQUIRED moves; anything else answers 422 with the row unchanged.
- **sign** — AWAITING_SIGNATURE → SIGNED, persisting the signature artefact hash on the
  application row in the same conditional claim as the transition (migration V27). The hash is
  the evidence anchor of the signature artefact; a SYSTEM actor can never drive this transition
  — a signature is always a human act.

Two legs reach the same machine:

- **Desk** (`POST /api/v1/lending/applications/{id}/…`, actions `lending.withdraw`,
  `lending.submitDocuments`, `lending.sign`) — lending officers, credit risk and compliance,
  gated by OPA desk sets with the customer-edge vetoed.
- **Customer edge** (`POST /api/v1/lending/intake/applications/{id}/withdraw`, action
  `lending.intake`) — the customer's own withdrawal through customer-edge, owner-filtered so a
  foreign application id reads as 404, never as someone else's credit history. The actor
  recorded in evidence is the edge's vouched principal, never a request-body field.

All four POSTs are money-path endpoints and take a **required `Idempotency-Key`** (#8351): the
key is reserved before the command runs, bound to a canonical request fingerprint (same key,
different body → 409 IDEMPOTENCY_KEY_REUSED), and a successful 2xx response is stored, so a
retry replays the stored outcome with `X-Idempotency-Replayed: true` instead of re-running a
transition the machine would now refuse. Refused commands (4xx/422) release the reservation so
a corrected retry re-runs.

The state machine stays the single authority on which transitions are legal; the commands only
make the intents explicit and evidenced.
