// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

class KafkaDomesticPaymentEventPublisherTest {

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())

    private fun payment(
        status: DomesticPaymentStatus = DomesticPaymentStatus.VALIDATED,
        rejectReason: DomesticRejectReason? = null,
        rejectDetail: String? = null,
        initiatedByPartyId: UUID? = null,
        delegationId: UUID? = null,
        reservationId: UUID? = null,
    ) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-kafka",
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1000",
        debtorBankCode = "0800",
        debtorName = "Payer",
        creditorAccountNumber = "2000",
        creditorBankCode = "0100",
        creditorName = "Payee",
        amount = BigDecimal("12.34"),
        currency = "CZK",
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOMS1",
        rejectReason = rejectReason,
        rejectDetail = rejectDetail,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.parse("2026-06-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-06-01T09:00:00Z"),
        initiatedByPartyId = initiatedByPartyId,
        delegationId = delegationId,
        reservationId = reservationId,
    )

    private fun entry(payload: String = "{\"event\":\"x\"}") = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        eventType = "domestic.payment.created",
        payload = payload,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        createdAt = Instant.parse("2026-06-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-06-01T09:00:00Z"),
        sentAt = null,
        lastError = null,
    )

    @Test
    fun `paymentCreatedPayload serializes the created event with the payment id`() {
        val payment = payment()
        val emitter = mockk<MutinyEmitter<String>>()
        val publisher = KafkaDomesticPaymentEventPublisher(emitter, objectMapper, Clock.systemUTC())

        val payload = publisher.paymentCreatedPayload(payment)

        assertThat(payload).contains("\"paymentId\":\"${payment.id}\"")
        assertThat(payload).contains("\"status\":\"VALIDATED\"")
        assertThat(payload).contains("\"endToEndId\":\"DOMS1\"")
    }

    @Test
    fun `paymentCreatedPayload carries eventType and sourceService for AuditConsumer attribution (issue 3994)`() {
        val payment = payment()
        val emitter = mockk<MutinyEmitter<String>>()
        val publisher = KafkaDomesticPaymentEventPublisher(emitter, objectMapper, Clock.systemUTC())

        val payload = publisher.paymentCreatedPayload(payment)
        val node = objectMapper.readTree(payload)

        // These are the exact keys AuditConsumer.resolveSourceService / the eventType chain read
        // off the body (node.textOrNull("eventType") / node.textOrNull("sourceService")) — before
        // this fix neither key existed and 124 domestic-payment rows landed as
        // event_type="UNKNOWN", source_service="unknown".
        assertThat(node.get("eventType").asText()).isEqualTo("DOMESTIC_PAYMENT_CREATED")
        assertThat(node.get("sourceService").asText()).isEqualTo("domestic-payment")
    }

    @Test
    fun `statusChangedPayload serializes previous and new status with reject metadata`() {
        val previous = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        val current = payment(
            status = DomesticPaymentStatus.REJECTED,
            rejectReason = DomesticRejectReason.SANCTIONS_HIT,
            rejectDetail = "creditor on list",
        ).copy(id = previous.id)
        val emitter = mockk<MutinyEmitter<String>>()
        val publisher = KafkaDomesticPaymentEventPublisher(emitter, objectMapper, Clock.systemUTC())

        val payload = publisher.statusChangedPayload(previous, current)

        assertThat(payload).contains("\"previousStatus\":\"SENT_TO_CLEARING\"")
        assertThat(payload).contains("\"newStatus\":\"REJECTED\"")
        assertThat(payload).contains("\"rejectReason\":\"SANCTIONS_HIT\"")
        assertThat(payload).contains("\"rejectDetail\":\"creditor on list\"")
    }

    @Test
    fun `statusChangedPayload carries eventType and sourceService for AuditConsumer attribution (issue 3994)`() {
        val previous = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        val current = payment(status = DomesticPaymentStatus.SETTLED).copy(id = previous.id)
        val emitter = mockk<MutinyEmitter<String>>()
        val publisher = KafkaDomesticPaymentEventPublisher(emitter, objectMapper, Clock.systemUTC())

        val payload = publisher.statusChangedPayload(previous, current)
        val node = objectMapper.readTree(payload)

        assertThat(node.get("eventType").asText()).isEqualTo("DOMESTIC_PAYMENT_STATUS_CHANGED")
        assertThat(node.get("sourceService").asText()).isEqualTo("domestic-payment")
    }

    @Test
    fun `statusChangedPayload serializes the durable delegated spend binding`() {
        val initiator = UUID.randomUUID()
        val delegation = UUID.randomUUID()
        val reservation = UUID.randomUUID()
        val previous = payment(
            status = DomesticPaymentStatus.VALIDATED,
            initiatedByPartyId = initiator,
            delegationId = delegation,
            reservationId = reservation,
        )
        val current = previous.copy(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        val publisher = KafkaDomesticPaymentEventPublisher(
            mockk<MutinyEmitter<String>>(),
            objectMapper,
            Clock.systemUTC(),
        )

        val node = objectMapper.readTree(publisher.statusChangedPayload(previous, current))

        assertThat(node.path("initiatedByPartyId").asText()).isEqualTo(initiator.toString())
        assertThat(node.path("delegationId").asText()).isEqualTo(delegation.toString())
        assertThat(node.path("reservationId").asText()).isEqualTo(reservation.toString())
    }

    @Test
    fun `finalized absent payload carries the immutable tuple and never the raw idempotency key`() {
        val binding = com.openbank.domestic.contract.DelegatedSpendFinalizedAbsentPactFixture.binding()
        val publisher = KafkaDomesticPaymentEventPublisher(
            mockk<MutinyEmitter<String>>(),
            objectMapper,
            Clock.systemUTC(),
        )

        val payload = publisher.delegatedSpendFinalizedAbsentPayload(binding)
        val node = objectMapper.readTree(payload)

        assertThat(node.path("eventType").asText()).isEqualTo("DELEGATED_SPEND_FINALIZED_ABSENT")
        assertThat(node.path("sourceService").asText()).isEqualTo("domestic-payment")
        assertThat(node.path("version").asLong()).isEqualTo(1)
        assertThat(node.path("reservationId").asText()).isEqualTo(binding.snapshot.reservationId.toString())
        assertThat(node.path("idempotencyKeyHash").asText()).isEqualTo(
            "d5fcf99c283a194aff198754caa138862271e9f046af15e706ee317058ba9aad",
        )
        assertThat(node.has("idempotencyKey")).isFalse()
        assertThat(payload).doesNotContain("payment-42")
    }

    @Test
    fun `publish sends the entry payload through the emitter with Kafka headers`(): Unit = runBlocking {
        val emitter = mockk<MutinyEmitter<String>>()
        val capturedMessage = slot<Message<String>>()
        every { emitter.sendMessage(capture(capturedMessage)) } returns Uni.createFrom().voidItem()
        val publisher = KafkaDomesticPaymentEventPublisher(emitter, objectMapper, Clock.systemUTC())
        val outboxEntry = entry("{\"event\":\"x\"}")

        publisher.publish(outboxEntry)

        verify { emitter.sendMessage(any()) }
        assertThat(capturedMessage.captured.payload).isEqualTo("{\"event\":\"x\"}")
    }
}
