// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.application.usecase

import com.openbank.dispute.application.port.`in`.GetDisputeUseCase
import com.openbank.dispute.application.port.`in`.OpenDisputeUseCase
import com.openbank.dispute.application.port.`in`.UpdateDisputeUseCase
import com.openbank.dispute.application.port.out.DisputeEvidenceRepository
import com.openbank.dispute.application.port.out.DisputeRepository
import com.openbank.dispute.application.port.out.DisputeTimelineRepository
import com.openbank.dispute.domain.model.Dispute
import com.openbank.dispute.domain.model.DisputeEvidence
import com.openbank.dispute.domain.model.DisputeResolution
import com.openbank.dispute.domain.model.DisputeStatus
import com.openbank.dispute.domain.model.DisputeTimelineEvent
import com.openbank.dispute.domain.model.EvidenceChain
import com.openbank.dispute.domain.model.EvidenceChainVerification
import com.openbank.dispute.domain.model.OpenDisputeRequest
import com.openbank.dispute.domain.model.RemediationOutcome
import com.openbank.dispute.domain.model.ResolveDisputeRequest
import com.openbank.dispute.domain.model.UpdateDisputeRequest
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

@ApplicationScoped
class DisputeService(
    private val disputeRepo: DisputeRepository,
    private val evidenceRepo: DisputeEvidenceRepository,
    private val timelineRepo: DisputeTimelineRepository,
    @ConfigProperty(name = "openbank.dispute.resolution-sla-days", defaultValue = "45")
    private val resolutionSlaDays: Long,
    @ConfigProperty(name = "openbank.dispute.chargeback-window-days", defaultValue = "120")
    private val chargebackWindowDays: Long,
    private val clock: Clock,
) : OpenDisputeUseCase,
    UpdateDisputeUseCase,
    GetDisputeUseCase {

    // CDI constructor (Clock is not a CDI bean): bank time is Europe/Prague, like ComplaintService.
    @Inject
    constructor(
        disputeRepo: DisputeRepository,
        evidenceRepo: DisputeEvidenceRepository,
        timelineRepo: DisputeTimelineRepository,
        @ConfigProperty(name = "openbank.dispute.resolution-sla-days", defaultValue = "45")
        resolutionSlaDays: Long,
        @ConfigProperty(name = "openbank.dispute.chargeback-window-days", defaultValue = "120")
        chargebackWindowDays: Long,
    ) : this(disputeRepo, evidenceRepo, timelineRepo, resolutionSlaDays, chargebackWindowDays, Clock.system(BANK_TIME))

    override fun open(request: OpenDisputeRequest): Uni<Dispute> {
        val reference = "DSP-${System.currentTimeMillis()}"
        val now = OffsetDateTime.now(clock)
        val dispute = Dispute(
            reference = reference,
            transactionId = request.transactionId,
            accountId = request.accountId,
            partyId = request.partyId,
            disputeType = request.disputeType,
            amount = request.amount,
            currency = request.currency,
            description = request.description,
            merchantName = request.merchantName,
            merchantId = request.merchantId,
            transactionDate = request.transactionDate,
            filingDate = LocalDate.now(clock),
            resolutionDeadline = LocalDate.now(clock).plusDays(resolutionSlaDays),
            createdAt = now,
            updatedAt = now,
        )
        // The timeline row and the Kafka event are NOT the same thing, and the difference was a
        // real gap: "OPENED" below is a DisputeTimelineEvent — an audit entry inside this service
        // — so until now nothing outside dispute-service could learn that a customer had opened
        // one. ADR-0220 D1 needs exactly that fact to stop sending promotional surfaces to a
        // customer in dispute, and #4070 records that the absence made the exclusion unbuildable.
        return disputeRepo.save(dispute, listOf(openedOutboxMessage(dispute))).flatMap { saved ->
            val event = DisputeTimelineEvent(
                disputeId = saved.id,
                eventType = "OPENED",
                description = "Dispute opened: ${request.disputeType.name}",
                actor = "CUSTOMER",
                createdAt = OffsetDateTime.now(clock),
            )
            timelineRepo.save(event).map { saved }
        }
    }

    override fun update(id: UUID, request: UpdateDisputeRequest): Uni<Dispute> =
        disputeRepo.findById(id).flatMap { dispute ->
            if (dispute == null) {
                Uni.createFrom().failure(IllegalArgumentException("Dispute not found: $id"))
            } else {
                val updated = dispute.copy(
                    status = request.status ?: dispute.status,
                    resolution = request.resolution ?: dispute.resolution,
                    chargebackAmount = request.chargebackAmount ?: dispute.chargebackAmount,
                    resolvedBy = request.resolvedBy ?: dispute.resolvedBy,
                    resolvedAt = if (request.status in TERMINAL_STATUSES) {
                        OffsetDateTime.now(clock)
                    } else {
                        dispute.resolvedAt
                    },
                    updatedAt = OffsetDateTime.now(clock),
                )
                // A dispute that ENDS here must announce it exactly as `resolve` does.
                // engagement-service applies an ADR-0220 D3.5 targeting exclusion on
                // `dispute.opened` and lifts it ONLY on `dispute.resolved`
                // (DisputeOpenedEventConsumer:26-27), so a dispute that reaches a terminal state
                // through this path without the event leaves that customer excluded permanently.
                // `withdraw()` delegates here, so every withdrawal took that path. The timeline row
                // below is internal to this service and reaches no consumer.
                // Guarded on the PREVIOUS status so a repeated PUT of an already-terminal status
                // does not re-announce; the empty list makes this call identical to the one-arg
                // overload, which the repository implements with the same @WithTransaction.
                val messages = if (updated.status in TERMINAL_STATUSES && dispute.status !in TERMINAL_STATUSES) {
                    listOf(resolvedOutboxMessage(updated))
                } else {
                    emptyList()
                }
                disputeRepo.update(updated, messages).flatMap { saved ->
                    val event = DisputeTimelineEvent(
                        disputeId = saved.id,
                        eventType = "STATUS_CHANGED",
                        description = "Status updated to ${saved.status.name}",
                        actor = request.resolvedBy ?: "SYSTEM",
                        createdAt = OffsetDateTime.now(clock),
                    )
                    timelineRepo.save(event).map { saved }
                }
            }
        }

    /**
     * Append an evidence item to the dispute's tamper-evident chain (ADR-0117 hardening §1/§2).
     * Reads the chain tail (highest [DisputeEvidence.sequence] so far), stamps the new item with
     * the next sequence + prevHash via [EvidenceChain.append], and persists it. Concurrency note:
     * two concurrent `addEvidence` calls for the SAME dispute racing on the same tail read could
     * both compute the same next sequence — the unique `(dispute_id, sequence)` index added in
     * V6 turns that race into a persist-time constraint violation rather than a silently corrupted
     * chain, matching the "detect, don't silently accept" spirit of ADR-0133. Serializing writes
     * per-dispute (mutex/advisory lock, as audit-service does globally) is a follow-up if this
     * proves to matter at this service's evidence-submission volume.
     */
    override fun addEvidence(disputeId: UUID, evidence: DisputeEvidence): Uni<DisputeEvidence> =
        evidenceRepo.findLatestByDisputeId(disputeId).flatMap { previous ->
            val stamped = evidence.copy(disputeId = disputeId, submittedAt = OffsetDateTime.now(clock))
            val chained = EvidenceChain.append(stamped, previous)
            evidenceRepo.save(chained).flatMap { saved ->
                val event = DisputeTimelineEvent(
                    disputeId = disputeId,
                    eventType = "EVIDENCE_ADDED",
                    description = "Evidence added: ${evidence.evidenceType}",
                    actor = evidence.submittedBy,
                    createdAt = OffsetDateTime.now(clock),
                )
                timelineRepo.save(event).map { saved }
            }
        }

    override fun withdraw(id: UUID, actor: String): Uni<Dispute> = update(
        id,
        UpdateDisputeRequest(
            status = DisputeStatus.WITHDRAWN,
            resolution = DisputeResolution.WITHDRAWN,
            resolvedBy = actor,
        ),
    )

    override fun escalate(id: UUID, actor: String): Uni<Dispute> =
        update(id, UpdateDisputeRequest(status = DisputeStatus.ESCALATED, resolvedBy = actor))

    /**
     * Record the remediation verdict (ADR-0117 hardening §3). Only reachable from an
     * evidence-gathering state ([RESOLVABLE_FROM]); a case already terminal (resolved, withdrawn,
     * escalated) cannot be re-resolved through this path. [RemediationOutcome.PARTIAL] requires a
     * [ResolveDisputeRequest.remediationAmount] strictly between zero and the dispute's claimed
     * amount.
     *
     * On [RemediationOutcome.UPHELD] or [RemediationOutcome.PARTIAL], emits a
     * `dispute.remediation_requested` event alongside `dispute.resolved` in the SAME transaction
     * (transactional outbox) describing the compensating action a downstream consumer may take.
     * **No consumer exists yet** — ADR-0143's billing-service fee-reversal flow (phase 2e) is
     * still an open gap (issue #548) and no other service currently subscribes to this event
     * type; this service's job per ADR-0117 is to emit it, not to call a ledger/billing reversal
     * itself (dispute-service is not a money-path service).
     */
    override fun resolve(id: UUID, request: ResolveDisputeRequest): Uni<Dispute> =
        disputeRepo.findById(id).flatMap { dispute ->
            when {
                dispute == null -> Uni.createFrom().failure(IllegalArgumentException("Dispute not found: $id"))
                dispute.status !in RESOLVABLE_FROM -> Uni.createFrom().failure(
                    IllegalStateException(
                        "Dispute $id cannot be resolved from status ${dispute.status}; " +
                            "must be one of $RESOLVABLE_FROM",
                    ),
                )
                request.outcome == RemediationOutcome.PARTIAL &&
                    !isValidPartialAmount(request.remediationAmount, dispute.amount) ->
                    Uni.createFrom().failure(
                        IllegalArgumentException(
                            "PARTIAL remediation requires remediationAmount in (0, ${dispute.amount})",
                        ),
                    )
                else -> doResolve(dispute, request)
            }
        }

    private fun doResolve(dispute: Dispute, request: ResolveDisputeRequest): Uni<Dispute> {
        val now = OffsetDateTime.now(clock)
        val remediationAmount = when (request.outcome) {
            RemediationOutcome.UPHELD -> dispute.amount
            RemediationOutcome.PARTIAL -> request.remediationAmount
            RemediationOutcome.REJECTED -> null
        }
        val resolvedStatus = if (request.outcome == RemediationOutcome.REJECTED) {
            DisputeStatus.RESOLVED_MERCHANT
        } else {
            DisputeStatus.RESOLVED_CUSTOMER
        }
        val updated = dispute.copy(
            status = resolvedStatus,
            remediationOutcome = request.outcome,
            remediationAmount = remediationAmount,
            resolvedAt = now,
            resolvedBy = request.resolvedBy,
            updatedAt = now,
        )
        val messages = buildList {
            add(resolvedOutboxMessage(updated))
            if (request.outcome != RemediationOutcome.REJECTED) {
                add(remediationRequestedOutboxMessage(updated))
            }
        }
        return disputeRepo.update(updated, messages).flatMap { saved ->
            val event = DisputeTimelineEvent(
                disputeId = saved.id,
                eventType = "RESOLVED",
                description = "Resolved: ${request.outcome.name}" + (request.notes?.let { " — $it" } ?: ""),
                actor = request.resolvedBy,
                createdAt = OffsetDateTime.now(clock),
            )
            timelineRepo.save(event).map { saved }
        }
    }

    private fun isValidPartialAmount(amount: BigDecimal?, claimAmount: BigDecimal): Boolean =
        amount != null && amount > BigDecimal.ZERO && amount < claimAmount

    /**
     * The `dispute.opened` event.
     *
     * `partyId` is the field that makes this consumable at all — a consumer holding only a
     * disputeId would have to call back into this service to learn whose dispute it is, on a path
     * where that lookup is exactly what the ADR-0220 eligibility snapshot exists to avoid. Paired
     * with the existing `dispute.resolved`, the two bracket the window during which a customer is
     * in dispute, so a consumer can both apply and lift the exclusion.
     *
     * `occurredAt` is the OPENING instant — `dispute.createdAt`, the same value `openedAt` already
     * carries — converted with `.toInstant()` (#8352). Two separate points, and the second is the
     * one a rename alone would have missed:
     *  - `openedAt` is not a spelling `AuditConsumer.eventTime` accepts. It reads `occurredAt` and
     *    only `occurredAt`, so every `dispute.opened` row in the ten-year audit trail recorded the
     *    consumer's ingest clock as the moment a customer disputed a payment.
     *  - `createdAt` is an `OffsetDateTime`, and this file's two sibling builders both spell
     *    `.toInstant()` for that reason. `openedAt` is left exactly as it was — additive, no
     *    existing field changes name, place or form.
     *
     * Why this one builder was missed by the #3914/#3926 sweep that patched its two siblings:
     * `dispute.opened` was introduced by #4087, which merged about three hours BEFORE that sweep
     * did — so the sweep's branch, cut earlier, could not see the sibling it was about to acquire.
     */
    private fun openedOutboxMessage(dispute: Dispute): OutboxMessage = OutboxMessage(
        aggregateId = dispute.id,
        eventType = "dispute.opened",
        payload = """{"eventType":"dispute.opened","disputeId":"${dispute.id}",""" +
            """"reference":"${dispute.reference}","partyId":"${dispute.partyId}",""" +
            """"disputeType":"${dispute.disputeType}","status":"${dispute.status}",""" +
            """"openedAt":"${dispute.createdAt}",""" +
            """"occurredAt":"${dispute.createdAt.toInstant()}",""" +
            """"sourceService":"$SOURCE_SERVICE"}""",
        createdAt = Instant.now(clock),
    )

    /**
     * `occurredAt` is the RESOLUTION instant, not the outbox-write instant (#3914).
     *
     * The two are within microseconds of each other here — both come from the same injected clock
     * inside one transaction — so the choice looks cosmetic and is not: the outbox row's own
     * `createdAt` already records when the row was written, and duplicating it under the name of
     * the business time would make the audit trail assert something it did not measure. The
     * resolution is the event; `dispute.resolvedAt` is when it happened.
     */
    private fun resolvedOutboxMessage(dispute: Dispute): OutboxMessage = OutboxMessage(
        aggregateId = dispute.id,
        eventType = "dispute.resolved",
        // partyId added alongside dispute.opened: without it a consumer can learn that a customer
        // entered dispute but never that they left it, so an ADR-0220 exclusion applied on open
        // would never lift. Additive, and `dispute.remediation_requested` already carries one.
        payload = """{"eventType":"dispute.resolved","disputeId":"${dispute.id}",""" +
            """"reference":"${dispute.reference}","partyId":"${dispute.partyId}",""" +
            // WITHDRAWN reaches this builder with no remediation outcome; interpolating it
            // straight would put the four-character string "null" in a quoted field.
            """"outcome":${dispute.remediationOutcome?.let { "\"$it\"" } ?: "null"},""" +
            """"status":"${dispute.status}","resolvedAt":"${dispute.resolvedAt}",""" +
            """"occurredAt":"${dispute.resolvedAt?.toInstant() ?: Instant.now(clock)}",""" +
            """"sourceService":"$SOURCE_SERVICE"}""",
        createdAt = Instant.now(clock),
    )

    /**
     * A downstream-facing event describing the compensating action warranted by this dispute's
     * outcome. Deliberately does NOT reference a ledger journal, GL account, or any billing-service
     * concept — this service has no visibility into those and must not assume a specific consumer
     * shape. `amount`/`currency` are the compensation amount (full claim for UPHELD, the partial
     * amount for PARTIAL); `accountId`/`transactionId` let a consumer resolve which account/payment
     * to compensate.
     */
    private fun remediationRequestedOutboxMessage(dispute: Dispute): OutboxMessage = OutboxMessage(
        aggregateId = dispute.id,
        eventType = "dispute.remediation_requested",
        payload = """{"eventType":"dispute.remediation_requested","disputeId":"${dispute.id}",""" +
            """"reference":"${dispute.reference}","accountId":"${dispute.accountId}",""" +
            """"transactionId":"${dispute.transactionId}","partyId":"${dispute.partyId}",""" +
            """"outcome":"${dispute.remediationOutcome}","amount":${dispute.remediationAmount},""" +
            """"currency":"${dispute.currency}",""" +
            // Same instant as dispute.resolved above, deliberately: this event is emitted in the
            // same transaction and describes the remediation that resolution warrants. It has no
            // separate business instant of its own, and inventing one (a fresh clock read) would
            // put two different "when"s on one indivisible state change.
            """"occurredAt":"${dispute.resolvedAt?.toInstant() ?: Instant.now(clock)}",""" +
            """"sourceService":"$SOURCE_SERVICE"}""",
        createdAt = Instant.now(clock),
    )

    override fun getDispute(id: UUID): Uni<Dispute?> = disputeRepo.findById(id)
    override fun getByReference(reference: String): Uni<Dispute?> = disputeRepo.findByReference(reference)
    override fun listByAccount(accountId: UUID): Uni<List<Dispute>> = disputeRepo.findByAccountId(accountId)
    override fun listByStatus(status: DisputeStatus): Uni<List<Dispute>> = disputeRepo.findByStatus(status)
    override fun getTimeline(disputeId: UUID): Uni<List<DisputeTimelineEvent>> = timelineRepo.findByDisputeId(disputeId)
    override fun getEvidence(disputeId: UUID): Uni<List<DisputeEvidence>> = evidenceRepo.findByDisputeId(disputeId)

    override fun verifyEvidenceChain(disputeId: UUID): Uni<EvidenceChainVerification> =
        evidenceRepo.findByDisputeIdOrderedBySequence(disputeId).map { items ->
            EvidenceChain.verify(disputeId, items)
        }

    companion object {
        private val BANK_TIME: ZoneId = ZoneId.of("Europe/Prague")

        /**
         * The states in which a dispute is over. Shared by [update] between the `resolvedAt` stamp
         * and the `dispute.resolved` emission so the two can never disagree about what "terminal"
         * means -- before this they were the same three names written out twice, one of which
         * emitted nothing.
         */
        internal val TERMINAL_STATUSES = setOf(
            DisputeStatus.RESOLVED_CUSTOMER,
            DisputeStatus.RESOLVED_MERCHANT,
            DisputeStatus.WITHDRAWN,
        )

        /** States from which a remediation resolution may be recorded (evidence-gathering states). */
        internal val RESOLVABLE_FROM = setOf(
            DisputeStatus.OPEN,
            DisputeStatus.UNDER_REVIEW,
            DisputeStatus.PENDING_CUSTOMER,
            DisputeStatus.PENDING_MERCHANT,
        )

        /**
         * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
         * (EVENT-sourced) attribution — issue #3994/#5256. Before this field, `TopicAttribution`
         * already resolves `openbank.dispute.events` -> `dispute-service` correctly, but only as
         * TOPIC-sourced — and audit-service DOES subscribe to this topic today (it is in
         * `application.yaml`'s consumed-topics list), so this is a live attribution improvement.
         * Value matches the fleet's audit convention: the module directory without the
         * `openbank-` prefix, the same spelling `TopicAttribution` already maps this topic to.
         */
        internal const val SOURCE_SERVICE = "dispute-service"
    }
}
