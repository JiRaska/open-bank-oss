// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
import com.openbank.dispute.domain.model.OpenDisputeRequest
import com.openbank.dispute.domain.model.UpdateDisputeRequest
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
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
        return disputeRepo.save(dispute).flatMap { saved ->
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
                    resolvedAt = if (request.status in listOf(
                            DisputeStatus.RESOLVED_CUSTOMER,
                            DisputeStatus.RESOLVED_MERCHANT,
                            DisputeStatus.WITHDRAWN,
                        )
                    ) {
                        OffsetDateTime.now(clock)
                    } else {
                        dispute.resolvedAt
                    },
                    updatedAt = OffsetDateTime.now(clock),
                )
                disputeRepo.update(updated).flatMap { saved ->
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

    override fun addEvidence(disputeId: UUID, evidence: DisputeEvidence): Uni<DisputeEvidence> = evidenceRepo.save(
        evidence.copy(disputeId = disputeId, submittedAt = OffsetDateTime.now(clock)),
    ).flatMap { saved ->
        val event = DisputeTimelineEvent(
            disputeId = disputeId,
            eventType = "EVIDENCE_ADDED",
            description = "Evidence added: ${evidence.evidenceType}",
            actor = evidence.submittedBy,
            createdAt = OffsetDateTime.now(clock),
        )
        timelineRepo.save(event).map { saved }
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

    override fun getDispute(id: UUID): Uni<Dispute?> = disputeRepo.findById(id)
    override fun getByReference(reference: String): Uni<Dispute?> = disputeRepo.findByReference(reference)
    override fun listByAccount(accountId: UUID): Uni<List<Dispute>> = disputeRepo.findByAccountId(accountId)
    override fun listByStatus(status: DisputeStatus): Uni<List<Dispute>> = disputeRepo.findByStatus(status)
    override fun getTimeline(disputeId: UUID): Uni<List<DisputeTimelineEvent>> = timelineRepo.findByDisputeId(disputeId)
    override fun getEvidence(disputeId: UUID): Uni<List<DisputeEvidence>> = evidenceRepo.findByDisputeId(disputeId)

    companion object {
        private val BANK_TIME: ZoneId = ZoneId.of("Europe/Prague")
    }
}
