// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.pensionfund.infrastructure.accounting

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.pensionfund.domain.model.ManagementFeeAccrual
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class PensionFeePublisherTest {
    private val config = mockk<PensionAccountingConfig>()
    private val receipts = mockk<FeeDeliveryReceipts>()
    private val clients = mockk<PensionBookClients>()
    private val fundClient = mockk<PensionBookClient>()
    private val companyClient = mockk<PensionBookClient>()

    // Match Quarkus's default additive DTO compatibility, including the computed source key.
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val publisher = PensionFeePublisher(config, receipts, mapper, clients)
    private val fundId = UUID.fromString("00000000-0000-0000-0000-000000000101")
    private val companyId = UUID.fromString("00000000-0000-0000-0000-000000000201")
    private val fundUrl = URI("https://fund.example.test")
    private val companyUrl = URI("https://company.example.test")
    private val day = LocalDate.of(2026, 11, 1)
    private val delivered = mutableMapOf<Pair<UUID, String>, FeeDeliveryReceipt>()
    private val fundRequests = mutableListOf<FeePosting>()
    private val companyRequests = mutableListOf<FeePosting>()

    init {
        every { config.deliveryEnabled() } returns true
        every { config.companyBookId() } returns Optional.of(companyId)
        every { config.companyUrl() } returns Optional.of(companyUrl)
        every { config.funds() } returns mapOf(fundId.toString() to fundUrl)
        every { clients.open(fundUrl) } returns fundClient
        every { clients.open(companyUrl) } returns companyClient
        every { fundClient.close() } returns Unit
        every { companyClient.close() } returns Unit
        coEvery { receipts.bind(fundId, companyId) } returns Unit
        coEvery { receipts.has(any(), any()) } answers { delivered[firstArg<UUID>() to secondArg<String>()] != null }
        coEvery { receipts.save(any(), any()) } answers {
            val receipt = secondArg<FeeDeliveryReceipt>()
            delivered[receipt.navId to firstArg<String>()] = receipt
        }
        coEvery { fundClient.post(any(), capture(fundRequests)) } answers {
            acknowledgement(secondArg(), firstArg<String?>() == "true")
        }
        coEvery { companyClient.post(any(), capture(companyRequests)) } answers {
            acknowledgement(secondArg(), firstArg<String?>() == "true")
        }
    }

    @Test
    fun `one source is delivered to distinct fund and company books without changing its amount`() {
        val entry = entry()
        runBlocking { publisher.publish(entry) }
        assertThat(fundRequests.single().bookId).isEqualTo(fundId)
        assertThat(companyRequests.single().bookId).isEqualTo(companyId)
        for (request in fundRequests + companyRequests) {
            assertThat(request.fundId).isEqualTo(fundId)
            assertThat(request.navId).isEqualTo(entry.eventId)
            assertThat(request.amount).isEqualByComparingTo("10.00")
            assertThat(request.valuationDate).isEqualTo(day)
        }
        assertThat(delivered.keys).containsExactlyInAnyOrder(
            entry.eventId to "PENSION_FUND",
            entry.eventId to "PENSION_COMPANY",
        )
        verify(exactly = 1) { fundClient.close() }
        verify(exactly = 1) { companyClient.close() }
    }

    @Test
    fun `retry after a company failure preserves the acknowledged fund leg`() {
        val entry = entry()
        var companyAttempts = 0
        coEvery { companyClient.post(any(), capture(companyRequests)) } answers {
            companyAttempts++
            if (companyAttempts == 1) error("company unavailable")
            acknowledgement(secondArg(), firstArg<String?>() == "true")
        }
        assertThatThrownBy { runBlocking { publisher.publish(entry) } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(delivered.keys).containsExactly(entry.eventId to "PENSION_FUND")

        runBlocking { publisher.publish(entry) }

        assertThat(fundRequests).hasSize(1)
        assertThat(companyRequests).hasSize(2)
        assertThat(delivered).hasSize(2)
    }

    @Test
    fun `a changed company binding fails before either remote destination is opened`() {
        val changedCompany = UUID.randomUUID()
        every { config.companyBookId() } returns Optional.of(changedCompany)
        coEvery { receipts.bind(fundId, changedCompany) } throws IllegalStateException("binding changed")

        assertThatThrownBy { runBlocking { publisher.publish(entry()) } }
            .isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 0) { clients.open(any()) }
        coVerify(exactly = 0) { receipts.save(any(), any()) }
    }

    @Test
    fun `zero fee requires explicit no posting receipts from both books`() {
        val entry = entry("0.00")
        runBlocking { publisher.publish(entry) }
        assertThat(delivered.values).hasSize(2).allSatisfy {
            assertThat(it.outcome).isEqualTo("NO_POSTING")
            assertThat(it.journalId).isNull()
        }
    }

    @Test
    fun `a missing synthetic acknowledgement is rejected before it can certify either book`() {
        val entry = entry(synthetic = true)
        coEvery { fundClient.post(any(), any()) } answers { acknowledgement(secondArg(), synthetic = false) }

        assertThatThrownBy { runBlocking { publisher.publish(entry) } }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("population")

        coVerify(exactly = 1) { fundClient.post("true", any()) }
        verify(exactly = 0) { clients.open(companyUrl) }
        coVerify(exactly = 0) { receipts.save(any(), any()) }
    }

    private fun entry(amount: String = "10.00", synthetic: Boolean = false): OutboxEntry {
        val navId = UUID.randomUUID()
        val fee = ManagementFeeAccrual(navId, fundId, day, "CZK", BigDecimal(amount), null, "checker")
        val at = Instant.parse("2026-11-02T06:00:00Z")
        return OutboxEntry(
            eventId = navId, aggregateId = fundId, eventType = "PensionManagementFeeAccrued.v1",
            payload = mapper.writeValueAsString(fee), status = OutboxStatus.PENDING, attemptCount = 0,
            createdAt = at, updatedAt = at, sentAt = null, lastError = null, synthetic = synthetic,
        )
    }

    private fun acknowledgement(request: FeePosting, synthetic: Boolean): FeeDeliveryReceipt = FeeDeliveryReceipt(
        bookId = request.bookId,
        navId = request.navId,
        postingDate = day,
        outcome = if (request.amount.signum() == 0) "NO_POSTING" else "POSTED",
        journalId = if (request.amount.signum() == 0) {
            null
        } else {
            UUID.nameUUIDFromBytes(
                "${request.bookId}:${request.navId}".toByteArray(),
            )
        },
        synthetic = synthetic,
    )
}
