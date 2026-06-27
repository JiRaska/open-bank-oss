// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.audit.KafkaCustomerAuditEventPublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [KafkaCustomerAuditEventPublisher].
 *
 * The adapter's contract: every [CustomerAuditEventPublisher.publish] call must be
 * translated into an [EdgeAuditPublisher.emit] call with the correct field mapping.
 * EdgeAuditPublisher is mocked (it owns the Kafka channel; its own tests cover emission).
 */
class KafkaCustomerAuditEventPublisherTest {

    private val delegate = mockk<EdgeAuditPublisher>(relaxed = true)
    private val publisher = KafkaCustomerAuditEventPublisher(delegate)

    @Test
    fun `action dot-notation is upper-snaked into the eventType`() {
        val eventTypeSlot = slot<String>()
        every {
            delegate.emit(
                eventType = capture(eventTypeSlot),
                partyId = any(),
                operation = any(),
                result = any(),
                resourceId = any(),
                details = any(),
            )
        } returns Unit

        publisher.publish(
            actorPartyId = "11111111-2222-3333-4444-555555555555",
            action = "payments.domestic",
            resourceType = "PAYMENT",
            outcome = "SUCCESS",
        )

        assertThat(eventTypeSlot.captured).isEqualTo("CUSTOMER_PAYMENTS_DOMESTIC")
    }

    @Test
    fun `resourceType and traceId are injected into the details map`() {
        val detailsSlot = slot<Map<String, String?>>()
        every {
            delegate.emit(
                eventType = any(),
                partyId = any(),
                operation = any(),
                result = any(),
                resourceId = any(),
                details = capture(detailsSlot),
            )
        } returns Unit

        publisher.publish(
            actorPartyId = "party-1",
            action = "sca.enrollDevice",
            resourceType = "DEVICE",
            traceId = "abc-trace-123",
            outcome = "SUCCESS",
            payload = mapOf("platform" to "FCM"),
        )

        val details = detailsSlot.captured
        assertThat(details["resourceType"]).isEqualTo("DEVICE")
        assertThat(details["traceId"]).isEqualTo("abc-trace-123")
        assertThat(details["platform"]).isEqualTo("FCM")
    }

    @Test
    fun `traceId is absent from details when not provided`() {
        val detailsSlot = slot<Map<String, String?>>()
        every {
            delegate.emit(
                eventType = any(),
                partyId = any(),
                operation = any(),
                result = any(),
                resourceId = any(),
                details = capture(detailsSlot),
            )
        } returns Unit

        publisher.publish(
            actorPartyId = "party-1",
            action = "accounts.read",
            resourceType = "ACCOUNT",
            outcome = "SUCCESS",
        )

        assertThat(detailsSlot.captured).doesNotContainKey("traceId")
    }

    @Test
    fun `actorPartyId is forwarded as partyId and action maps to operation`() {
        val partySlot = slot<String>()
        val operationSlot = slot<String>()
        every {
            delegate.emit(
                eventType = any(),
                partyId = capture(partySlot),
                operation = capture(operationSlot),
                result = any(),
                resourceId = any(),
                details = any(),
            )
        } returns Unit

        publisher.publish(
            actorPartyId = "22222222-3333-4444-5555-666666666666",
            action = "transfers.create",
            resourceType = "TRANSFER",
            outcome = "SUCCESS",
        )

        assertThat(partySlot.captured).isEqualTo("22222222-3333-4444-5555-666666666666")
        assertThat(operationSlot.captured).isEqualTo("transfers.create")
    }

    @Test
    fun `delegate is called exactly once per publish`() {
        publisher.publish(
            actorPartyId = "p",
            action = "onboarding.register",
            resourceType = "PARTY",
            outcome = "SUCCESS",
        )

        verify(exactly = 1) { delegate.emit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `outcome is forwarded as result`() {
        val resultSlot = slot<String>()
        every {
            delegate.emit(
                eventType = any(),
                partyId = any(),
                operation = any(),
                result = capture(resultSlot),
                resourceId = any(),
                details = any(),
            )
        } returns Unit

        publisher.publish(
            actorPartyId = "p",
            action = "payments.sepa",
            resourceType = "PAYMENT",
            outcome = "FAILURE",
        )

        assertThat(resultSlot.captured).isEqualTo("FAILURE")
    }

    @Test
    fun `resourceId is forwarded when provided`() {
        val resourceIdSlot = slot<String?>()
        every {
            delegate.emit(
                eventType = any(),
                partyId = any(),
                operation = any(),
                result = any(),
                resourceId = captureNullable(resourceIdSlot),
                details = any(),
            )
        } returns Unit

        publisher.publish(
            actorPartyId = "p",
            action = "payments.domestic",
            resourceType = "PAYMENT",
            resourceId = "pay-uuid-abc",
            outcome = "SUCCESS",
        )

        assertThat(resourceIdSlot.captured).isEqualTo("pay-uuid-abc")
    }
}
