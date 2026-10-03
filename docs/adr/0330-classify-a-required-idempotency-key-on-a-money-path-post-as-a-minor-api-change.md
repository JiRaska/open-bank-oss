---
date: 2026-10-03
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [api-contract, governance, ci]
summary: "A newly required Idempotency-Key header on a money-path POST, and nothing else breaking, is a MINOR API-contract change: the D5 classifier's MAJOR demand made idempotency hardening of existing money-path endpoints unreachable."
---

# ADR-0330 — Classify a required Idempotency-Key on a money-path POST as a MINOR API change

## Context

ADR-0048 D5 has `check-api-contract.py` classify every OpenAPI diff with `oasdiff`; any
error-level breaking finding demands an `info.version` MAJOR bump, and D2 ties that major to the
served URL (`/api/v{N}`). Adding a REQUIRED request parameter is breaking to `oasdiff`, and
correctly so: a client that does not send it now gets a 400.

The enforced `idempotency-coverage-money-path` gate (#8351) requires every money-path POST to
declare a required idempotency key. For a POST that already existed when its service joined
`rules.yaml: money_path_services`, the two gates together demand a whole-service move to
`/api/v{N+1}` in exchange for a duplicate-debit fix. Measured on card-processing (#11996):
`POST /api/v1/card-tokens/{tokenReference}/status` and `POST /api/v1/card-disputes/{id}/refresh`
reach a card network with no key; requiring one was red at 1.2.0 for want of a MAJOR, and the
only MAJOR D2 accepts moves every route of the service. The cheap way out — baselining the
endpoints in the coverage gate — keeps the double call.

## Decision

We will classify a breaking diff as `idempotency-hardening`, requiring a MINOR bump, when ALL hold:

- the service is in `rules.yaml: money_path_services` as of the PR head (a PR that adds the
  service there qualifies);
- every error-level `oasdiff breaking` finding in the spec is `new-required-request-parameter`
  for a HEADER named `Idempotency-Key` (case-insensitive) on a POST.

Any other breaking finding anywhere in the same spec — a different header, the same header on
another method, a removed path, a changed schema — keeps the normal MAJOR rule. The class is
decided mechanically from the diff, never from a declared marker, like the existing `correction`
class. The obligation that replaces the MAJOR bump: the same PR updates every in-repo caller to
send a key. An out-of-repo caller fails loudly with a 400 naming the header rather than silently
double-booking, which is the trade this decision makes on purpose.

Held to a must-pass and four must-fail fixtures in the gate's own `--self-test`
(`idempotency_hardening_self_test`), each of which goes red when its narrowing is removed.

## Alternatives considered

- **Keep MAJOR and move the service to `/api/v{N+1}`** — the literal ADR-0048 path. Rejected:
  every route and caller moves for a two-endpoint hardening; in practice the fix is not shipped.
- **Baseline the endpoints in `idempotency-coverage-baseline.txt`** — rejected: it records the
  defect instead of fixing it; the network is still asked twice on a retry.
- **Exempt all required-header additions on money-path POSTs** — rejected as too wide: a new
  required tenant or signature header is a genuine contract break with no comparable safety gain.

## Consequences

**Positive**
- Existing money-path POSTs can be idempotency-hardened within their URL major.

**Negative**
- An external client of such an endpoint starts receiving 400 without a URL-major signal; the
  MINOR bump and the changelog are the only notice.

**Neutral**
- Applies only to the `Idempotency-Key` header idiom; the body-property idiom
  (`idempotencyKey`) is unchanged and still classifies by the normal rules.

## Compliance impact

- PCI DSS: not applicable — classification of an API version number, no cardholder data.
- DORA:    not applicable — no change to ICT risk controls beyond enabling idempotency fixes.
- GDPR:    not applicable — no personal data processed.
- PSD2:    not applicable — no PSD2 interface semantics change.
- CNB:     not applicable — no reporting or supervisory interface affected.

## References

- ADR-0048 (D2, D5) — API-contract version axis and its CI classifier.
- `.github/scripts/check-api-contract.py` — `idempotency_hardening`, `idempotency_hardening_self_test`.
- #8351 (idempotency coverage gate), #11996 (card-processing joins money-path).
