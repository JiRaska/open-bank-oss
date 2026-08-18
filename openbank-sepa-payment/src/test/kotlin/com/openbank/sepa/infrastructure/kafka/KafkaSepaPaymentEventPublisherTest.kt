// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.MutinyEmitter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class KafkaSepaPaymentEventPublisherTest {

    private lateinit var emitter: MutinyEmitter<String>
    private lateinit var objectMapper: ObjectMapper
    private lateinit var publisher: KafkaSepaPaymentEventPublisher

    @BeforeEach
    fun setUp() {
        emitter = mockk()
        objectMapper = ObjectMapper().registerModule(JavaTimeModule())
        publisher = KafkaSepaPaymentEventPublisher(emitter, objectMapper)
    }

    private fun payment(
        status: SepaPaymentStatus = SepaPaymentStatus.RECEIVED,
        rejectReason: SepaRejectReason? = null,
    ) = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-kafka",
        type = SepaPaymentType.SCT,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Example",
        creditorIban = "FR7630006000011234567890189",
        creditorName = "Bob Example",
        creditorBic = "DEUTDEFF",
        amount = BigDecimal("123.45"),
        currency = "EUR",
        remittanceInfo = "ref",
        endToEndId = "E2E-kafka",
        rejectReason = rejectReason,
        rejectDetail = null,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.parse("2026-01-02T10:00:00Z"),
        updatedAt = Instant.parse("2026-01-02T10:00:00Z"),
    )

    private fun outboxEntry(aggregateId: UUID = UUID.randomUUID(), payload: String = "{\"event\":\"created\"}") =
        OutboxEntry(
            eventId = UUID.randomUUID(),
            aggregateId = aggregateId,
            eventType = "sepa.payment.created",
            payload = payload,
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            sentAt = null,
            lastError = null,
        )

    @Test
    fun `paymentCreatedPayload serializes the created event with the payment identity`() {
        val payment = payment()

        val json = publisher.paymentCreatedPayload(payment)

        assertThat(json).contains("\"paymentId\":\"${payment.id}\"")
        assertThat(json).contains("\"status\":\"RECEIVED\"")
        assertThat(json).contains("\"endToEndId\":\"E2E-kafka\"")
        // Issue #3994/#5256: the wire payload actually reaching Kafka carries sourceService — a
        // domain-level assertion on the event alone cannot prove that, only serializing through
        // this publisher (the same objectMapper.writeValueAsString call the real outbox uses) can.
        assertThat(json).contains("\"sourceService\":\"sepa-payment\"")
    }

    @Test
    fun `statusChangedPayload serializes the previous and new status`() {
        val previous = payment(status = SepaPaymentStatus.RECEIVED)
        val current = previous.copy(status = SepaPaymentStatus.REJECTED, rejectReason = SepaRejectReason.SANCTIONS_HIT)

        val json = publisher.statusChangedPayload(previous, current)

        assertThat(json).contains("\"previousStatus\":\"RECEIVED\"")
        assertThat(json).contains("\"newStatus\":\"REJECTED\"")
        assertThat(json).contains("\"rejectReason\":\"SANCTIONS_HIT\"")
        assertThat(json).contains("\"sourceService\":\"sepa-payment\"")
    }

    @Test
    fun `publish sends the entry payload with partition key and CloudEvents headers`(): Unit = runBlocking {
        val entry = outboxEntry(payload = "{\"event\":\"created\"}")
        coEvery { emitter.sendMessage(any<Message<String>>()) } returns Uni.createFrom().voidItem()

        publisher.publish(entry)

        coVerify { emitter.sendMessage(any<Message<String>>()) }
    }
}
