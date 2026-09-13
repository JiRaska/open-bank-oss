---
# Domestic-payment request-fingerprint cutover (V11/V12)

Operational runbook for the durable-idempotency cutover shipped with the delegated-spend binding (#8274). Moved here from the generated svc-domestic-payment.md, which generate-service-runbooks.py owns — hand edits there are drift (#2255).

## Durable idempotency cutover

The request-fingerprint migration is intentionally strict: a historical payment whose nullable
`request_fingerprint` was written by the old image cannot prove that a replay carries the same
body/actor. It therefore always returns `409 IDEMPOTENCY_KEY_REUSED`; there is no compatibility flag
that can make the old row authoritative again. The former Redis implementation supported a 24-hour
(`86400` second) replay window, so this preserves confidentiality/integrity at the explicit cost of
availability for old keys still inside that window.

Use a blue/green switch, not a mixed-version rolling interval:

1. Apply the additive V11/V12 schema while the old image still runs; do not activate delegated spend.
2. Quiesce only domestic-payment creates at the ingress. Drain every in-flight create for at least
   the configured maximum request timeout (currently 15 seconds) and verify zero old-image requests.
3. Switch all traffic to the fully healthy new image, verify that no old writer replica remains, then
   reopen creates. Do not run old and new writers concurrently.
4. Probe a newly created exact replay (same id and `X-Idempotency-Replayed: true`) and a changed-body
   replay (409). Confirm one payment row and one created outbox row.
5. For a legacy/ambiguous 409, use payment status lookup and operator reconciliation. Never tell the
   caller to retry with a new key: the old attempt may have committed, so a new key can duplicate it.

Do not roll back by reintroducing Redis as response authority or accepting an unprovable NULL
fingerprint. If the switch must be reversed, quiesce creates again and treat every ambiguous request
as a reconciliation case before any resubmission.

## Delegated reservation state bootstrap

`openbank.delegation.spend-reservation-state` is a one-partition compacted bootstrap stream, not an
arrival-ordered transition log. Delegation publishes one bounded key per revision:
`<reservationId>:v1` for RESERVED and `<reservationId>:v2` for either terminal state. Compaction
therefore retains at most two records per reservation. A v1 send whose acknowledgement was
ambiguous may complete after v2; this is expected and must not reopen the binding.

On a rebuild, validate that every payload revision is exactly 1 or 2, group all retained values by
payload `reservationId`, and apply only the greatest `reservationVersion`. Never apply the last
record observed as the current state. A terminal-first then stale-v1 replay must finish terminal.
Keep the topic at one partition; increasing it changes the cross-revision ordering protocol even
though correctness still comes from maximum-revision application rather than arrival order. Before
first activation, verify no legacy plain-`reservationId` key exists: this key format is frozen before
the default-off writer is enabled, and a mixed format would retain one additional stale slot.

Activate in this order: deploy the consumer from `earliest` while delegated create and the finalizer
remain off; wait for consumer lag to reach zero and verify the projection selected the maximum
revision for every reservation; then enable delegated create; enable finalization last. Do not use
"last record wins" or a zero lag reading alone as bootstrap proof — explicitly test terminal v2
followed by delayed v1 and require the projection to remain terminal.

