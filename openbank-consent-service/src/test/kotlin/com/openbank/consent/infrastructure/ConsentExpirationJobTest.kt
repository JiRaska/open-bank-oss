// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.consent.application.port.out.ConsentOutboxRepository
import com.openbank.consent.application.port.out.ConsentRepository
import com.openbank.consent.domain.model.Consent
import com.openbank.consent.domain.model.ConsentScope
import com.openbank.consent.domain.model.ConsentStatus
import com.openbank.consent.domain.model.GranteeType
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ConsentExpirationJobTest {

    private val consentRepo = mockk<ConsentRepository>()
    private val outboxRepo = mockk<ConsentOutboxRepository>()
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-29T05:05:00Z"), ZoneOffset.UTC)

    private val job = ConsentExpirationJob().also {
        it.consentRepo = consentRepo
        it.outboxRepo = outboxRepo
        it.objectMapper = objectMapper
        it.clock = fixedClock
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
        verify(exactly = 0) { outboxRepo.persistInTransaction(any()) }
    }

    @Test
    fun `buildSweepPipeline - one expired consent - marks expired and enqueues outbox event`() {
        val c = consent()
        val outboxSlot = slot<OutboxMessage>()

        every { consentRepo.findExpiredActive(any()) } returns Uni.createFrom().item(listOf(c))
        every { consentRepo.markExpired(c.id, any()) } returns Uni.createFrom().item(true)
        every { outboxRepo.persistInTransaction(capture(outboxSlot)) } returns Uni.createFrom().voidItem()

        val count = job.buildSweepPipeline(OffsetDateTime.now(fixedClock)).await().indefinitely()

        assertThat(count).isEqualTo(1)
        verify(exactly = 1) { consentRepo.markExpired(c.id, any()) }
        assertThat(outboxSlot.captured.eventType).isEqualTo("ConsentExpired")
        assertThat(outboxSlot.captured.aggregateId).isEqualTo(c.id)
    }

    @Test
    fun `buildSweepPipeline - markExpired returns false (concurrent sweep race) - no outbox event`() {
        val c = consent()

        every { consentRepo.findExpiredActive(any()) } returns Uni.createFrom().item(listOf(c))
        every { consentRepo.markExpired(c.id, any()) } returns Uni.createFrom().item(false)

        val count = job.buildSweepPipeline(OffsetDateTime.now(fixedClock)).await().indefinitely()

        assertThat(count).isEqualTo(0)
        verify(exactly = 0) { outboxRepo.persistInTransaction(any()) }
    }

    @Test
    fun `buildSweepPipeline - two expired consents - both get outbox events`() {
        val c1 = consent()
        val c2 = consent()

        every { consentRepo.findExpiredActive(any()) } returns Uni.createFrom().item(listOf(c1, c2))
        every { consentRepo.markExpired(c1.id, any()) } returns Uni.createFrom().item(true)
        every { consentRepo.markExpired(c2.id, any()) } returns Uni.createFrom().item(true)
        every { outboxRepo.persistInTransaction(any()) } returns Uni.createFrom().voidItem()

        val count = job.buildSweepPipeline(OffsetDateTime.now(fixedClock)).await().indefinitely()

        assertThat(count).isEqualTo(2)
        verify(exactly = 2) { outboxRepo.persistInTransaction(any()) }
    }

    @Test
    fun `buildSweepPipeline - uses provided threshold`() {
        val threshold = OffsetDateTime.parse("2026-06-29T05:05:00Z")
        val thresholdSlot = slot<OffsetDateTime>()

        every { consentRepo.findExpiredActive(capture(thresholdSlot)) } returns Uni.createFrom().item(emptyList())

        job.buildSweepPipeline(threshold).await().indefinitely()

        assertThat(thresholdSlot.captured).isEqualTo(threshold)
    }
}
