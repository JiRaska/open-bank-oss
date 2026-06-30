// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.openbank.fraud.application.port.out.VelocityAggregateRepository
import com.openbank.fraud.application.usecase.FeatureOnlineUpdater
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class TransactionSignalConsumerTest {

    private val velocityRepo = mockk<VelocityAggregateRepository>(relaxed = true)
    private val featureUpdater = mockk<FeatureOnlineUpdater>(relaxed = true)
    private val objectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())

    private val consumer = TransactionSignalConsumer(velocityRepo, featureUpdater, objectMapper)

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

        coVerify(exactly = 1) { velocityRepo.recordTransaction(accountId, BigDecimal("250.00"), "CZK") }
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

        coVerify(exactly = 0) { velocityRepo.recordTransaction(any(), any(), any()) }
    }

    @Test
    fun `null sourceAccountId is skipped without calling repository`() {
        val payload = """{"aggregateId": "${UUID.randomUUID()}", "amount": "100.00", "currencyCode": "EUR"}"""

        consumer.onTransactionInitiated(payload)

        coVerify(exactly = 0) { velocityRepo.recordTransaction(any(), any(), any()) }
    }

    @Test
    fun `missing amount defaults to zero`() {
        val accountId = UUID.randomUUID()
        val amountSlot = slot<BigDecimal>()
        coEvery { velocityRepo.recordTransaction(any(), capture(amountSlot), any()) } returns Unit

        val payload = """{"sourceAccountId": "$accountId", "currencyCode": "CZK"}"""
        consumer.onTransactionInitiated(payload)

        assertThat(amountSlot.captured).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `missing currencyCode defaults to CZK`() {
        val accountId = UUID.randomUUID()
        val currencySlot = slot<String>()
        coEvery { velocityRepo.recordTransaction(any(), any(), capture(currencySlot)) } returns Unit

        val payload = """{"sourceAccountId": "$accountId", "amount": "50.00"}"""
        consumer.onTransactionInitiated(payload)

        assertThat(currencySlot.captured).isEqualTo("CZK")
    }

    @Test
    fun `repository exception is caught and does not propagate`() {
        val accountId = UUID.randomUUID()
        coEvery { velocityRepo.recordTransaction(any(), any(), any()) } throws RuntimeException("DB down")

        val payload = """{"sourceAccountId": "$accountId", "amount": "100.00", "currencyCode": "CZK"}"""

        // Must not throw
        consumer.onTransactionInitiated(payload)
    }
}
