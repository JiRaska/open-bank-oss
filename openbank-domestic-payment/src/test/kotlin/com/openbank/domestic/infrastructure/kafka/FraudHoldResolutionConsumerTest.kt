// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.usecase.FraudHoldResolutionService
import com.openbank.domestic.application.workflow.FraudHoldDelivery
import com.openbank.domestic.application.workflow.FraudReviewDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FraudHoldResolutionConsumerTest {
    private val resolution: FraudHoldResolutionService = mockk()
    private val consumer = FraudHoldResolutionConsumer(ObjectMapper(), resolution)
    private val paymentId = UUID.randomUUID()

    private fun event(status: String, alert: String? = "FRAUD_REVIEW", tx: String? = paymentId.toString()) =
        ObjectMapper().writeValueAsString(
            mapOf("caseId" to UUID.randomUUID(), "newStatus" to status, "alertCode" to alert, "transactionId" to tx),
        )

    @Test
    fun `CLEARED releases and BLOCKED rejects`() {
        every { resolution.resolve(any(), any()) } returns FraudHoldDelivery.DELIVERED

        runBlocking { consumer.consume(event("CLEARED")) }
        verify(exactly = 1) { resolution.resolve(paymentId, FraudReviewDecision.RELEASE) }

        runBlocking { consumer.consume(event("BLOCKED")) }
        verify(exactly = 1) { resolution.resolve(paymentId, FraudReviewDecision.REJECT) }
    }

    @Test
    fun `nothing else on the shared topic can release a payment`() = runBlocking {
        consumer.consume(event("ESCALATED"))
        consumer.consume(event("UNDER_REVIEW"))
        consumer.consume(event("CLEARED", alert = "SANCTIONS_HIT"))
        consumer.consume(event("CLEARED", alert = null))
        consumer.consume(event("CLEARED", tx = null))
        verify(exactly = 0) { resolution.resolve(any(), any()) }
    }

    @Test
    fun `a malformed record reaches the dead-letter strategy instead of being acked`() {
        assertThatThrownBy { runBlocking { consumer.consume("{not json") } }
            .isInstanceOf(Exception::class.java)
    }
}
