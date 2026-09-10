---
date: 2026-09-07
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, authz]
summary: "SCA creation POSTs are already idempotent: challenges replay on Idempotency-Key/X-Request-ID via the store, device enrollment dedups on the credentialId natural key (V4)."
---

# ADR-0296 — SCA creation POSTs are idempotent on caller keys and the credential natural key

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. Two sca-service creation POSTs were
baselined as uncovered:

- `POST /api/v1/sca/challenges` — initiate an SCA challenge (PSD2 SCA for payments, ADR-0021)
- `POST /api/v1/sca/parties/{partyId}/devices` — enroll a device credential for decoupled approval

## Decision

Verified in code; no fix was needed — both endpoints are already enforced, and the baseline
entries re-point here:

- **Challenges** — the resource replays on the caller-supplied `Idempotency-Key` header, with
  `X-Request-ID` as an accepted fallback (both are headers every edge caller already supplies):
  a retry within the 300 s store window is answered from the idempotency store with
  `X-Idempotency-Replayed: true`, and no second challenge is minted. The key stays OPTIONAL
  (the pre-existing edge contract) — a keyless retry still creates a new challenge, which is
  the documented trade-off of the edge contract, not an oversight.
- **Devices** — the credentialId IS the natural key (`credential_id UNIQUE`, V4):
  re-enrolling the same credential for the same party replays the original enrollment; the
  same credential for a DIFFERENT party is refused (CredentialAlreadyEnrolledException) — a
  credential belongs to exactly one party. The residual true-concurrency race (two concurrent
  first enrollments of the same credential) loses one attempt on the constraint as a 500; not
  recovered in code because a credentialId is device-generated and a genuine same-credential
  race is not a pattern enrollment sees — the replay path is fully covered by the check-first
  read.

## Alternatives considered

- **Mandatory Idempotency-Key on challenges** — rejected: breaks existing keyless callers; the
  baseline-with-ADR-reason mechanism exists for exactly this class.
- **Race recovery on the device constraint** — rejected (see above): cost and constraint-name
  coupling for a race the credential lifecycle does not produce.

## Consequences

- Both endpoints have a stated, enforced idempotency contract; the OpenAPI now declares the
  replay headers on challenges and the credentialId semantics on enrollment; `info.version`
  bumped PATCH.
- Two baseline entries re-point here.

## Compliance impact

Documents the replay protection on the PSD2 SCA initiation path (a duplicate challenge would
mean a duplicate customer authentication prompt, and downstream a second consumable
authorisation) and on device credential ownership. No new obligation introduced.
