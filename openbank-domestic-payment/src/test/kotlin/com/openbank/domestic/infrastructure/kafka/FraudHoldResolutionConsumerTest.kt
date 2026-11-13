// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.usecase.FraudHoldResolutionService
import com.openbank.domestic.application.usecase.PaymentHoldResolutionService
import com.openbank.domestic.application.workflow.FraudHoldDelivery
import com.openbank.domestic.application.workflow.FraudReviewDecision
import com.openbank.domestic.application.workflow.PaymentHoldDecision
import com.openbank.domestic.application.workflow.PaymentHoldPolicy
import com.openbank.domestic.application.workflow.fraudReviewCaseKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class FraudHoldResolutionConsumerTest {
    private val resolution: FraudHoldResolutionService = mockk()
    private val holdResolution: PaymentHoldResolutionService = mockk()
    private val consumer = FraudHoldResolutionConsumer(ObjectMapper(), resolution, holdResolution)
    private val paymentId = UUID.randomUUID()

    private fun event(
        status: String,
        alert: String? = "FRAUD_REVIEW",
        tx: String? = paymentId.toString(),
        key: String? = fraudReviewCaseKey(paymentId),
    ) = ObjectMapper().writeValueAsString(
        mapOf(
            "caseId" to UUID.randomUUID(),
            "newStatus" to status,
            "alertCode" to alert,
            "transactionId" to tx,
            "idempotencyKey" to key,
        ),
    )

    @Test
    fun `CLEARED releases and BLOCKED rejects`() {
        every { resolution.resolve(any(), any(), any()) } returns FraudHoldDelivery.DELIVERED

        runBlocking { consumer.consume(event("CLEARED")) }
        verify(exactly = 1) {
            resolution.resolve(paymentId, FraudReviewDecision.RELEASE, fraudReviewCaseKey(paymentId))
        }

        runBlocking { consumer.consume(event("BLOCKED")) }
        verify(exactly = 1) { resolution.resolve(paymentId, FraudReviewDecision.REJECT, fraudReviewCaseKey(paymentId)) }
    }

    @Test
    fun `nothing else on the shared topic can release a payment`(): Unit = runBlocking {
        consumer.consume(event("ESCALATED"))
        consumer.consume(event("UNDER_REVIEW"))
        consumer.consume(event("CLEARED", alert = "SANCTIONS_HIT"))
        consumer.consume(event("CLEARED", alert = null))
        consumer.consume(event("CLEARED", tx = null))
        verify(exactly = 0) { resolution.resolve(any(), any(), any()) }
    }

    @Test
    fun `a case someone else created for the same payment cannot release it`(): Unit = runBlocking {
        consumer.consume(event("CLEARED", key = "attacker-chosen-key"))
        consumer.consume(event("CLEARED", key = null))
        consumer.consume(event("CLEARED", key = fraudReviewCaseKey(UUID.randomUUID())))
        verify(exactly = 0) { resolution.resolve(any(), any(), any()) }
    }

    @Test
    fun `the decision of an admission hold's own case releases or rejects it (ADR-0374)`() {
        every { holdResolution.resolve(any(), any(), any()) } returns true
        val key = PaymentHoldPolicy.caseKey(paymentId)

        runBlocking { consumer.consume(event("CLEARED", alert = "PAYMENT_HELD", key = key)) }
        runBlocking { consumer.consume(event("BLOCKED", alert = "PAYMENT_HELD", key = key)) }

        verify(exactly = 1) { holdResolution.resolve(paymentId, PaymentHoldDecision.RELEASE, key) }
        verify(exactly = 1) { holdResolution.resolve(paymentId, PaymentHoldDecision.REJECT, key) }
        verify(exactly = 0) { resolution.resolve(any(), any(), any()) }
    }

    @Test
    fun `no other case can decide an admission hold`(): Unit = runBlocking {
        val key = PaymentHoldPolicy.caseKey(paymentId)
        consumer.consume(event("ESCALATED", alert = "PAYMENT_HELD", key = key))
        consumer.consume(event("UNDER_REVIEW", alert = "PAYMENT_HELD", key = key))
        consumer.consume(event("CLEARED", alert = "PAYMENT_HELD", key = "attacker-chosen-key"))
        consumer.consume(event("CLEARED", alert = "PAYMENT_HELD", key = null))
        consumer.consume(event("CLEARED", alert = "PAYMENT_HELD", key = PaymentHoldPolicy.caseKey(UUID.randomUUID())))
        // The fraud hold's key does not open an admission hold, and the other way round.
        consumer.consume(event("CLEARED", alert = "PAYMENT_HELD", key = fraudReviewCaseKey(paymentId)))
        consumer.consume(event("CLEARED", alert = "FRAUD_REVIEW", key = key))
        consumer.consume(event("CLEARED", alert = "PAYMENT_HELD", tx = null, key = key))
        consumer.consume(event("CLEARED", alert = "PAYMENT_HELD", tx = "not-a-uuid", key = key))
        verify(exactly = 0) { holdResolution.resolve(any(), any(), any()) }
        verify(exactly = 0) { resolution.resolve(any(), any(), any()) }
    }

    @Test
    fun `a malformed record reaches the dead-letter strategy instead of being acked`() {
        assertThatThrownBy { runBlocking { consumer.consume("{not json") } }
            .isInstanceOf(Exception::class.java)
    }
}
