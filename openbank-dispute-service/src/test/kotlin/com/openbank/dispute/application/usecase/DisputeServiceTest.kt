// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.dispute.application.usecase

import com.openbank.dispute.application.port.out.DisputeEvidenceRepository
import com.openbank.dispute.application.port.out.DisputeRepository
import com.openbank.dispute.application.port.out.DisputeTimelineRepository
import com.openbank.dispute.domain.model.Dispute
import com.openbank.dispute.domain.model.DisputeResolution
import com.openbank.dispute.domain.model.DisputeStatus
import com.openbank.dispute.domain.model.DisputeTimelineEvent
import com.openbank.dispute.domain.model.DisputeType
import com.openbank.dispute.domain.model.OpenDisputeRequest
import com.openbank.dispute.domain.model.UpdateDisputeRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DisputeServiceTest {

    private val clock = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)
    private val today = LocalDate.now(clock)

    private lateinit var disputeRepo: DisputeRepository
    private lateinit var evidenceRepo: DisputeEvidenceRepository
    private lateinit var timelineRepo: DisputeTimelineRepository

    private lateinit var service: DisputeService

    @BeforeEach
    fun setUp() {
        disputeRepo = mockk()
        evidenceRepo = mockk()
        timelineRepo = mockk()
        service = DisputeService(
            disputeRepo,
            evidenceRepo,
            timelineRepo,
            resolutionSlaDays = 45L,
            chargebackWindowDays = 120L,
            clock = clock,
        )
    }

    @Test
    fun `open persists dispute and adds timeline event`() {
        val request = OpenDisputeRequest(
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.UNAUTHORIZED,
            amount = BigDecimal("25.00"),
            transactionDate = today,
            description = "Unauthorized card payment",
        )

        val persistedId = UUID.randomUUID()

        every { disputeRepo.save(any()) } answers { Uni.createFrom().item(firstArg<Dispute>().copy(id = persistedId)) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.open(request).await().indefinitely()

        assertThat(result.id).isEqualTo(persistedId)
        assertThat(result.status).isEqualTo(DisputeStatus.OPEN)
        assertThat(result.resolution).isEqualTo(DisputeResolution.PENDING)
        assertThat(result.filingDate).isEqualTo(today)
        assertThat(result.resolutionDeadline).isEqualTo(today.plusDays(45))

        verify(exactly = 1) { disputeRepo.save(any()) }
        verify(exactly = 1) { timelineRepo.save(any()) }
    }

    @Test
    fun `update changes status and emits timeline event`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-1000",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.DUPLICATE,
            amount = BigDecimal("10.00"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        val update = UpdateDisputeRequest(status = DisputeStatus.RESOLVED_CUSTOMER, resolvedBy = "caseworker")
        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)
        every { disputeRepo.update(any()) } answers { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.update(id, update).await().indefinitely()

        assertThat(result.status).isEqualTo(DisputeStatus.RESOLVED_CUSTOMER)
        assertThat(result.resolvedBy).isEqualTo("caseworker")
        assertThat(result.resolvedAt).isNotNull()
        assertThat(result.updatedAt).isNotNull()

        verify(exactly = 1) { disputeRepo.findById(id) }
        verify(exactly = 1) { disputeRepo.update(any()) }
        verify(exactly = 1) { timelineRepo.save(any()) }
    }

    @Test
    fun `withdraw sets withdrawn status`() {
        val id = UUID.randomUUID()
        val existing = Dispute(
            id = id,
            reference = "DSP-2000",
            transactionId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            disputeType = DisputeType.OTHER,
            amount = BigDecimal("99.99"),
            transactionDate = today,
            filingDate = today,
            resolutionDeadline = today.plusDays(45),
            createdAt = now,
            updatedAt = now,
        )
        every { disputeRepo.findById(id) } returns Uni.createFrom().item(existing)
        every { disputeRepo.update(any()) } answers { Uni.createFrom().item(firstArg<Dispute>()) }
        every { timelineRepo.save(any()) } answers { Uni.createFrom().item(firstArg<DisputeTimelineEvent>()) }

        val result = service.withdraw(id, "customer").await().indefinitely()

        assertThat(result.status).isEqualTo(DisputeStatus.WITHDRAWN)
        assertThat(result.resolution).isEqualTo(DisputeResolution.WITHDRAWN)
        assertThat(result.resolvedBy).isEqualTo("customer")
        assertThat(result.updatedAt).isNotNull()

        verify(exactly = 1) { disputeRepo.findById(id) }
        verify(exactly = 1) { disputeRepo.update(any()) }
        verify(exactly = 1) { timelineRepo.save(any()) }
    }
}
