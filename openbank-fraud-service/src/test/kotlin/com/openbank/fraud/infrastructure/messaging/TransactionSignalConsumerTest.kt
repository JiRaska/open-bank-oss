// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.application.port.out.PayeeHistoryRepository
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.application.usecase.FeatureOnlineUpdater
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private class TransientDbFailure : RuntimeException("DB down")

class TransactionSignalConsumerTest {

    private val velocityRepo = mockk<VelocityAggregateRepository>(relaxed = true)
    private val payeeHistoryRepo = mockk<PayeeHistoryRepository>(relaxed = true)
    private val featureUpdater = mockk<FeatureOnlineUpdater>(relaxed = true)
    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
    private val metrics = mockk<FraudMetricsPort>(relaxed = true)
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-09T00:00:00Z"), ZoneOffset.UTC)

    private val consumer =
        TransactionSignalConsumer(velocityRepo, payeeHistoryRepo, featureUpdater, objectMapper, fixedClock, metrics)

    @Test
    fun `happy path records velocity for valid signal`() {
        val accountId = UUID.randomUUID()
        val payload = """
            {
              "aggregateId": "${UUID.randomUUID()}",
              "sourceAccountId": "$accountId",
              "amount": "250.00",
              "currencyCode": "CZK"
            }
        """.trimIndent()

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 1) { velocityRepo.recordTransaction(accountId, BigDecimal("250.00"), "CZK", any(), any()) }
    }

    /**
     * Issue #6044: the velocity bucket must be derived from the event's own business time. The
     * repository is what applies it, but the consumer is the only place that can supply it — and it
     * used to supply nothing, leaving the repository to read its clock. Asserted here as the exact
     * `occurredAt` from the wire, not merely "some Instant": a fallback to processing time is
     * precisely the bug, and `any()` would agree with it.
     */
    @Test
    fun `forwards the event occurredAt to the velocity repository as the bucket time`() {
        val accountId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-07-09T10:59:00Z")
        val eventTimeSlot = slot<Instant>()
        coEvery {
            velocityRepo.recordTransaction(any(), any(), any(), any(), capture(eventTimeSlot))
        } returns Unit
        val payload = """
            {
              "aggregateId": "${UUID.randomUUID()}",
              "sourceAccountId": "$accountId",
              "amount": "250.00",
              "currencyCode": "CZK",
              "occurredAt": "$occurredAt"
            }
        """.trimIndent()

        consumer.onTransactionInitiated(payload)

        assertThat(eventTimeSlot.captured).isEqualTo(occurredAt)
        // Nothing was substituted, so the substitution must not be reported.
        coVerify(exactly = 0) { metrics.recordSignalMissingEventTime() }
    }

    /**
     * `occurredAt` is required on `TransactionInitiatedEvent`, so this should never happen — which is
     * exactly why it must be counted rather than absorbed. Processing time is still substituted (a
     * velocity row with an invented bucket beats no velocity row at all for a fraud control), but the
     * substitution is now visible from outside the database. #3883 is the precedent: the audit
     * consumer substituted ingest time for 7 of its 21 topics and nothing anywhere said so.
     */
    @Test
    fun `counts the substitution when the signal carries no occurredAt`() {
        val accountId = UUID.randomUUID()
        val eventTimeSlot = slot<Instant>()
        coEvery {
            velocityRepo.recordTransaction(any(), any(), any(), any(), capture(eventTimeSlot))
        } returns Unit
        val payload = """
            {
              "aggregateId": "${UUID.randomUUID()}",
              "sourceAccountId": "$accountId",
              "amount": "250.00",
              "currencyCode": "CZK"
            }
        """.trimIndent()

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 1) { metrics.recordSignalMissingEventTime() }
        assertThat(eventTimeSlot.captured).isEqualTo(Instant.parse("2026-07-09T00:00:00Z"))
    }

    @Test
    fun `forwards the signal aggregateId to the velocity repository as the dedup key`() {
        val accountId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        val transactionIdSlot = slot<UUID>()
        coEvery {
            velocityRepo.recordTransaction(any(), any(), any(), capture(transactionIdSlot), any())
        } returns Unit
        val payload = """
            {
              "aggregateId": "$aggregateId",
              "sourceAccountId": "$accountId",
              "amount": "250.00",
              "currencyCode": "CZK"
            }
        """.trimIndent()

        consumer.onTransactionInitiated(payload)

        // #5716: without this the redelivery guard has no key to compare and every signal is treated
        // as new — the guard would exist in SQL and never fire.
        assertThat(transactionIdSlot.captured).isEqualTo(aggregateId)
    }

    @Test
    fun `updates the online feature store when the event carries occurredAt`() {
        val accountId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-06-29T10:30:00Z")
        val payload = """
            {
              "sourceAccountId": "$accountId",
              "amount": "250.00",
              "currencyCode": "CZK",
              "occurredAt": "$occurredAt"
            }
        """.trimIndent()

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 1) { featureUpdater.onTransactionInitiated(accountId.toString(), occurredAt) }
    }

    @Test
    fun `skips the feature store when occurredAt is absent`() {
        val accountId = UUID.randomUUID()
        val payload = """{"sourceAccountId": "$accountId", "amount": "10.00", "currencyCode": "CZK"}"""

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 0) { featureUpdater.onTransactionInitiated(any(), any()) }
    }

    @Test
    fun `bad JSON is dropped without calling repository`() {
        consumer.onTransactionInitiated("not-valid-json{{{")

        coVerify(exactly = 0) { velocityRepo.recordTransaction(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `null sourceAccountId is skipped without calling repository`() {
        val payload = """{"aggregateId": "${UUID.randomUUID()}", "amount": "100.00", "currencyCode": "EUR"}"""

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 0) { velocityRepo.recordTransaction(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing amount defaults to zero`() {
        val accountId = UUID.randomUUID()
        val amountSlot = slot<BigDecimal>()
        coEvery { velocityRepo.recordTransaction(any(), capture(amountSlot), any(), any(), any()) } returns Unit

        val payload = """{"sourceAccountId": "$accountId", "currencyCode": "CZK"}"""
        consumer.onTransactionInitiated(payload)

        assertThat(amountSlot.captured).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `missing currencyCode defaults to CZK`() {
        val accountId = UUID.randomUUID()
        val currencySlot = slot<String>()
        coEvery { velocityRepo.recordTransaction(any(), any(), capture(currencySlot), any(), any()) } returns Unit

        val payload = """{"sourceAccountId": "$accountId", "amount": "50.00"}"""
        consumer.onTransactionInitiated(payload)

        assertThat(currencySlot.captured).isEqualTo("CZK")
    }

    @Test
    fun `repository exception is RETHROWN so the connector dead-letters`() {
        val accountId = UUID.randomUUID()
        coEvery { velocityRepo.recordTransaction(any(), any(), any(), any(), any()) } throws TransientDbFailure()

        val payload = """{"sourceAccountId": "$accountId", "amount": "100.00", "currencyCode": "CZK"}"""

        // Replaces a test that asserted the swallow. The velocity aggregate is what every velocity
        // rule reads: dropping an update weakens fraud detection for that account, silently (#5698).
        assertThrows<TransientDbFailure> { consumer.onTransactionInitiated(payload) }

        coVerify(exactly = 3) { velocityRepo.recordTransaction(any(), any(), any(), any(), any()) }
    }

    // ── Payee history (ADR-0084 §3 v4) ────────────────────────────────────────

    @Test
    fun `records payee history when targetAccountId is present`() {
        val accountId = UUID.randomUUID()
        val targetAccountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-06-29T10:30:00Z")
        val payload = """
            {
              "aggregateId": "$transactionId",
              "sourceAccountId": "$accountId",
              "targetAccountId": "$targetAccountId",
              "amount": "250.00",
              "currencyCode": "CZK",
              "occurredAt": "$occurredAt"
            }
        """.trimIndent()

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 1) {
            payeeHistoryRepo.recordPayment(accountId, targetAccountId.toString(), transactionId, occurredAt)
        }
    }

    @Test
    fun `falls back to the injected clock when occurredAt is absent for payee history`() {
        val accountId = UUID.randomUUID()
        val targetAccountId = UUID.randomUUID()
        val payload = """
            {
              "sourceAccountId": "$accountId",
              "targetAccountId": "$targetAccountId",
              "amount": "250.00",
              "currencyCode": "CZK"
            }
        """.trimIndent()

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 1) {
            payeeHistoryRepo.recordPayment(accountId, targetAccountId.toString(), null, fixedClock.instant())
        }
    }

    @Test
    fun `skips payee history when targetAccountId is absent`() {
        val accountId = UUID.randomUUID()
        val payload = """{"sourceAccountId": "$accountId", "amount": "100.00", "currencyCode": "EUR"}"""

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 0) { payeeHistoryRepo.recordPayment(any(), any(), any(), any()) }
    }

    @Test
    fun `payee history exception is RETHROWN so the connector dead-letters`() {
        val accountId = UUID.randomUUID()
        val targetAccountId = UUID.randomUUID()
        coEvery {
            payeeHistoryRepo.recordPayment(any(), any(), any(), any())
        } throws TransientDbFailure()

        val payload = """
            {
              "sourceAccountId": "$accountId",
              "targetAccountId": "$targetAccountId",
              "amount": "100.00",
              "currencyCode": "CZK"
            }
        """.trimIndent()

        // A hole in payee history reads as a first-time payee forever after — a fraud
        // discriminator quietly degraded. Replaces a test that asserted the swallow (#5698).
        assertThrows<TransientDbFailure> { consumer.onTransactionInitiated(payload) }
    }
}
