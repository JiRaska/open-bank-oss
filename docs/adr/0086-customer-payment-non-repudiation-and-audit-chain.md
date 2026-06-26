# 86. Customer payment non-repudiation — SCA settlement gate, identity threading, audit chain

Date: 2026-06-12
Status: Accepted
Author(s): OpenBank platform

## Context

ADR-0021 shipped the cryptographic half of strong customer authentication: an enrolled
device key (P-256 in the Secure Enclave / Android Keystore) signs the dynamic-linking
payload (amount + payee) of an SCA challenge, and sca-service verifies that signature
before recording a write-once decision. What it did NOT ship was the *enforcement* half:

1. **SCA was advisory.** No payment service checked for an approved challenge — a payment
   initiated at the edge was screened and settled regardless of whether the customer's
   device had signed anything. The mobile app even ran the flow in the wrong order
   (payment first, SCA after), making the signature pure theatre.
2. **The customer identity died at the edge.** Upstream services run on the edge's M2M
   operator token; `initiatedBy` on a transaction was the *service account*, the
   `X-Customer-Party-Id` header was advisory and unstored, and the `transactions` table
   had no customer column populated. "Which customer ordered this transfer?" was not
   answerable from the operational stores.
3. **The audit trail had producers missing and no tamper evidence.** audit-service taps
   domain-event topics, but customer-edge — the only component that still knows the real
   customer — emitted nothing. The audit table is guarded by no-UPDATE/no-DELETE rules,
   yet anyone who can drop a rule (or edit the heap) leaves no trace; WORM exists only in
   the analytics layer (ADR-0023 F2).

## Decision

**1. Settlement gate at the edge, atomic compare-and-consume in sca-service.**
`POST /customer/v1/{domestic,sepa}-payments` requires an `X-SCA-Challenge-Id` header.
Before forwarding, the edge calls the new `POST /api/v1/sca/challenges/{id}/consume`
with `{partyId, amount, currency, creditor}` — the operation it is about to execute.
sca-service refuses unless the challenge (a) belongs to that party, (b) is APPROVED
(resolving a pending signature-verified device decision on the spot), (c) its
device-signed dynamic-linking data authorises *exactly* that amount/currency/creditor,
and (d) has never been consumed — consumption is an atomic
`UPDATE … WHERE consumed_at IS NULL` (single-use, RTS Art. 5 replay protection).
A refused consume never burns the challenge; a consumed challenge never gates a second
payment. The mobile Send flow is inverted to the PSD2 order: challenge → device-signed
approval → payment carrying the challenge id.

**2. Own-account transfers use the RTS Art. 15 exemption, explicitly.**
`POST /customer/v1/transfers` ownership-checks BOTH legs against the JWT party, so it is
a same-person, same-PSP transfer — PSD2 RTS 2018/389 Art. 15 exempts it from SCA. The
exemption is recorded, not implied: the saga request carries
`scaExemption=PSD2_RTS_ART15_OWN_ACCOUNT`.

**3. Identity threading into the money path.**
transaction-service accepts `initiatedByPartyId` + `scaChallengeId`/`scaExemption`,
persists them (identity reuses the dormant V2 compliance columns `actor_id`/`actor_type`;
SCA linkage gets new columns) and REFUSES any customer-initiated movement carrying
neither a challenge nor a documented exemption — the gate fails closed even if a future
edge route forgets it. Both fields ride the `transaction.initiated` outbox event, which
audit-service and analytics already consume.

**4. Customer audit trail from the edge, hash-chained at rest.**
customer-edge emits a structured audit event (actor = partyId, operation, result,
SCA linkage, correlation) to `openbank.customer.audit` for every customer-initiated
write: transfers, payments (including SCA refusals), onboarding registration, device
enrolment, SCA decisions. Emission is best-effort by design — an audit outage degrades
to an ERROR log, never a failed payment. audit-service chains every entry:
`record_hash = SHA-256(prev_hash | evidential fields | SHA-256(payload))`, verified
end-to-end by `GET /api/v1/audit/integrity`. In-place edits, deletes, or re-ordering
break the recomputation at the first affected row.

**5. Mobile TLS pinning activated (ADR-0066 S3).**
Leaf SPKI pins for `customer.open-bank.tech` and `kc.open-bank.tech` are baked into the
app (iOS Darwin pinner; an OkHttp pinner is added for Android), with each host's pin
doubling as the other's backup (RFC 7469 §2.1.3). The ingress certificates get
`cert-manager.io/private-key-rotation-policy: "Never"` so Let's Encrypt renewals reuse
the pinned key — rotating the key now requires a coordinated app-pin release first.

## Alternatives considered

- **Verify SCA inside each payment service** instead of the edge: strongest layering, but
  couples three services to sca-service and re-implements the same check thrice. The edge
  is the single customer choke point (ADR-0065); transaction-service's fail-closed
  invariant (3) covers the "edge forgot" case. Per-service verification can be added
  later without changing the wire contract.
- **Signed authorisation tokens from sca-service** (detached JWS the payment carries,
  verified offline): elegant, no extra round-trip, but adds key distribution and token
  lifecycle for no additional assurance in-cluster. Deferred.
- **GET-then-consume in two steps at the edge**: TOCTOU window between verification and
  consumption. Rejected for the atomic compare-and-consume.
- **Hash chain in PostgreSQL triggers**: survives an application bypass, but hides the
  chain logic in the schema and complicates the reactive write path. The single-writer
  consumer + in-process serialisation is sufficient at current scale; revisit before
  audit-service scales horizontally (the repository documents this constraint).

## Consequences

**Positive**
- A customer payment can no longer settle without a device-signed, amount+payee-bound,
  single-use approval — and the app's approval sheet is now load-bearing, not theatre.
- Every customer-initiated movement is attributable end-to-end: party id on the
  transaction row, on the outbox event, and in the hash-chained audit log.
- Audit tampering is detectable in one API call; the chain complements (not replaces)
  the ADR-0023 analytics WORM.

**Negative**
- One extra in-cluster round-trip (consume) per payment — bounded by UpstreamClient
  timeouts.
- TLS pin + `rotationPolicy: Never` couples key rotation to app releases; an emergency
  key rotation bricks payments on old app builds until users update (mitigated by the
  cross-host backup pin and sandbox context).
- The PSD2 exemption marker is asserted by the edge; a compromised edge could mislabel
  an external payment as exempt. The edge is already the trust anchor for ownership
  checks, so this adds no new trust, but OPA policy-as-code on the exemption is a
  worthwhile follow-up (ADR-0034).

## Compliance impact

- **PSD2 / RTS 2018/389**: Art. 97 SCA enforced at settlement; Art. 5 dynamic linking +
  single-use; Art. 15 exemption applied deliberately and recorded per transfer.
- **GDPR Art. 30 / DORA Art. 17**: processing records carry the acting customer;
  incident reconstruction has a tamper-evident, queryable trail.
- **EBA ICT**: audit immutability upgraded from preventive (DB rules) to detective
  (hash chain + integrity endpoint).

## References

- ADR-0021 — SCA decoupled device approval (the cryptographic half).
- ADR-0023 — analytics regulatory hardening (Merkle + S3 Object Lock WORM).
- ADR-0065 — customer edge as the single north-south choke point.
- ADR-0066 — passwordless customer authentication; S3 TLS pinning.
- ADR-0069 — customer onboarding journey (Phase 2 self-service landed alongside).
