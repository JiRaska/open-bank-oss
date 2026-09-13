// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure

import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.domain.event.ConsentExpired
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.GranteeType
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ConsentExpirationJobTest {

    private val consentRepo = mockk<ConsentRepository>()
    private val metrics = mockk<DomainMetrics>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-29T05:05:00Z"), ZoneOffset.UTC)

    private val job = ConsentExpirationJob().also {
        it.consentRepo = consentRepo
        it.clock = fixedClock
        it.domainMetrics = metrics
    }

    private fun consent(
        id: UUID = UUID.randomUUID(),
        validTo: OffsetDateTime = OffsetDateTime.now(fixedClock).minusHours(1),
    ): Consent {
        val createdAt = OffsetDateTime.now(fixedClock).minusDays(30)
        return Consent(
            id = id,
            partyId = UUID.randomUUID(),
            granteeId = "tpp-001",
            granteeType = GranteeType.TPP,
            granteeName = "Test TPP",
            scopes = setOf(ConsentScope.ACCOUNTS_READ),
            accountIbans = null,
            validFrom = createdAt,
            validTo = validTo,
            redirectUri = null,
            tppTransactionId = null,
            ipAddress = null,
            userAgent = null,
            status = ConsentStatus.ACTIVE,
            createdAt = createdAt,
            updatedAt = createdAt,
            scaSessionId = null,
        )
    }

    @Test
    fun `buildSweepPipeline - no expired consents - returns zero`() {
        every { consentRepo.findExpiredActive(any()) } returns Uni.createFrom().item(emptyList())

        val count = job.buildSweepPipeline(OffsetDateTime.now(fixedClock)).await().indefinitely()

        assertThat(count).isEqualTo(0)
        verify(exactly = 0) { consentRepo.markExpired(any(), any(), any()) }
    }

    @Test
    fun `buildSweepPipeline - one expired consent - marks expired and enqueues ConsentExpired atomically`() {
        val c = consent()
        val eventSlot = slot<DomainEvent>()

        every { consentRepo.findExpiredActive(any()) } returns Uni.createFrom().item(listOf(c))
        every { consentRepo.markExpired(eq(c.id), any(), capture(eventSlot)) } returns Uni.createFrom().item(true)

        val count = job.buildSweepPipeline(OffsetDateTime.now(fixedClock)).await().indefinitely()

        assertThat(count).isEqualTo(1)
        verify(exactly = 1) { consentRepo.markExpired(eq(c.id), any(), any()) }
        assertThat(eventSlot.captured).isInstanceOf(ConsentExpired::class.java)
        assertThat(eventSlot.captured.eventType).isEqualTo("ConsentExpired")
        assertThat(eventSlot.captured.aggregateId).isEqualTo(c.id)
    }

    @Test
    fun `buildSweepPipeline - markExpired returns false (concurrent sweep race) - no event, counts zero`() {
        val c = consent()

        every { consentRepo.findExpiredActive(any()) } returns Uni.createFrom().item(listOf(c))
        every { consentRepo.markExpired(eq(c.id), any(), any()) } returns Uni.createFrom().item(false)

        val count = job.buildSweepPipeline(OffsetDateTime.now(fixedClock)).await().indefinitely()

        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `buildSweepPipeline - two expired consents - both transition and enqueue`() {
        val c1 = consent()
        val c2 = consent()

        every { consentRepo.findExpiredActive(any()) } returns Uni.createFrom().item(listOf(c1, c2))
        every { consentRepo.markExpired(eq(c1.id), any(), any()) } returns Uni.createFrom().item(true)
        every { consentRepo.markExpired(eq(c2.id), any(), any()) } returns Uni.createFrom().item(true)

        val count = job.buildSweepPipeline(OffsetDateTime.now(fixedClock)).await().indefinitely()

        assertThat(count).isEqualTo(2)
        verify(exactly = 2) { consentRepo.markExpired(any(), any(), any()) }
    }

    @Test
    fun `buildSweepPipeline - uses provided threshold`() {
        val threshold = OffsetDateTime.parse("2026-06-29T05:05:00Z")
        val thresholdSlot = slot<OffsetDateTime>()

        every { consentRepo.findExpiredActive(capture(thresholdSlot)) } returns Uni.createFrom().item(emptyList())

        job.buildSweepPipeline(threshold).await().indefinitely()

        assertThat(thresholdSlot.captured).isEqualTo(threshold)
    }

    @Test
    fun `sweep registers heartbeat and records success only after completed pipeline`(): Unit = runBlocking {
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        every { consentRepo.findExpiredActive(any()) } returns Uni.createFrom().item(emptyList())

        job.registerLiveness(StartupEvent())
        job.sweepExpiredConsents()

        verify(exactly = 1) { metrics.registerWorkflowLiveness("consent-expiration-sweep", any()) }
        verify(exactly = 1) { liveness.recordSuccess() }
    }

    @Test
    fun `sweep failure records no liveness success`(): Unit = runBlocking {
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        every { consentRepo.findExpiredActive(any()) } returns
            Uni.createFrom().failure(IllegalStateException("db down"))

        job.registerLiveness(StartupEvent())
        job.sweepExpiredConsents()

        verify(exactly = 0) { liveness.recordSuccess() }
    }
}
