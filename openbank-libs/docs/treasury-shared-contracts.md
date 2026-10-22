# Shared treasury and close contracts

The ADR-0344 branch reuses shared domain types for cross-service values. These are
source contracts, not evidence of rollout, broker delivery, a completed accounting
day or an authorized payment. Deployment holds and required reviews remain in
force.

## Reports and completeness are different values

[`CloseOutcome`](../../openbank-libs-domain/src/main/kotlin/com/openbank/libs/close/CloseOutcome.kt)
contains `RAN`, `SKIPPED` and `REFUSED` for participant reports.
[`ParticipantDayStatus`](../../openbank-libs-domain/src/main/kotlin/com/openbank/libs/close/ParticipantDayStatus.kt)
adds `WAIVED` and `MISSING` for the completeness read response. A missing report
is not a report, and only the ledger's operator path may waive participation.
Consumers must not use the read-side enum to widen the report request contract.

[`CloseParticipationReporter`](../../openbank-libs-runtime/src/main/kotlin/com/openbank/libs/close/CloseParticipationReporter.kt)
retains the participant-owned sink and delivery behavior. Its metric name
`openbank.close.participation.reports` is now available through the public `METRIC`
constant. Delivery failure is observed without failing the caller's job; the
caller must re-offer persisted outcomes on a later tick. An identical report
already delivered is not sent again while remembered by this process. This is
not a durable exactly-once guarantee. Whether an absent report actually blocks
ledger tie-out depends on the ledger's configured participation mode.

## Custody quantity dimension

[`PortfolioQuantityBasis`](../../openbank-libs-domain/src/main/kotlin/com/openbank/libs/domain/portfolio/PortfolioQuantityBasis.kt)
defines `UNIT`, `FACE_AMOUNT` and `AMORTISED_VALUE`. This dimension describes the
custodian's quantity, independently of the instrument's accounting class.
[`Semt002Reader`](../../openbank-libs-iso20022/src/main/kotlin/com/openbank/libs/iso20022/Semt002Reader.kt)
maps the ISO fields `Unit`, `FaceAmt` and `AmtsdVal` to those respective values;
they must not be treated as interchangeable measurements.

## Producer attribution

[`TopicProducers`](../../openbank-libs-domain/src/main/kotlin/com/openbank/libs/analytics/TopicProducers.kt)
adds `openbank.treasury.payments` → `treasury-service` and
`openbank.swift.institution-transfers` → `swift-service`. This table supplies
producer attribution where an event does not provide its own source. A declared
topic owner alone does not prove that an outbox dispatched or a consumer ran.
