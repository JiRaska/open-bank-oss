// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.libs.domain.money.Money
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SepaPaymentEventsTest {

    private val mapper = ObjectMapper().registerModule(JavaTimeModule())

    private fun payment(
        status: SepaPaymentStatus = SepaPaymentStatus.RECEIVED,
        rejectReason: SepaRejectReason? = null,
        rejectDetail: String? = null,
    ) = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "idem-event",
        type = SepaPaymentType.SCT_INST,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Example",
        creditorIban = "FR7630006000011234567890189",
        creditorName = "Bob Example",
        creditorBic = "DEUTDEFF",
        amount = Money.of(BigDecimal("123.45"), "EUR"),
        remittanceInfo = "ref",
        endToEndId = "E2E-event",
        rejectReason = rejectReason,
        rejectDetail = rejectDetail,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.parse("2026-01-02T10:00:00Z"),
        updatedAt = Instant.parse("2026-01-02T10:00:00Z"),
    )

    @Test
    fun `toCreatedEvent projects identity, parties and amount with the supplied timestamp`() {
        val payment = payment()
        val now = Instant.parse("2026-01-02T11:00:00Z")

        val event = payment.toCreatedEvent(Clock.fixed(now, ZoneOffset.UTC))

        assertThat(event.paymentId).isEqualTo(payment.id)
        assertThat(event.version).isZero()
        assertThat(event.idempotencyKey).isEqualTo("idem-event")
        assertThat(event.type).isEqualTo(SepaPaymentType.SCT_INST)
        assertThat(event.status).isEqualTo(SepaPaymentStatus.RECEIVED)
        assertThat(event.debtorAccountId).isEqualTo(payment.debtorAccountId)
        assertThat(event.debtorIban).isEqualTo(payment.debtorIban)
        assertThat(event.creditorIban).isEqualTo(payment.creditorIban)
        assertThat(event.amount).isEqualByComparingTo(BigDecimal("123.45"))
        assertThat(event.currency).isEqualTo("EUR")
        assertThat(event.endToEndId).isEqualTo("E2E-event")
        assertThat(event.occurredAt).isEqualTo(now)
        // Issue #3994/#5256: read by AuditConsumer.resolveSourceService as the strongest
        // (EVENT-sourced) attribution, upgrading over EventAttribution.TopicAttribution's
        // TOPIC-sourced `openbank.sepa.payment.events` -> `sepa-payment` fallback.
        assertThat(event.sourceService).isEqualTo("sepa-payment")
    }

    @Test
    fun `toStatusChangedEvent carries previous and new status with reject context`() {
        val payment = payment(
            status = SepaPaymentStatus.REJECTED,
            rejectReason = SepaRejectReason.SANCTIONS_HIT,
            rejectDetail = "OFAC hit",
        ).copy(revision = 3)
        val now = Instant.parse("2026-01-02T12:00:00Z")

        val event = payment.toStatusChangedEvent(SepaPaymentStatus.RECEIVED, Clock.fixed(now, ZoneOffset.UTC))

        assertThat(event.paymentId).isEqualTo(payment.id)
        assertThat(event.version).isEqualTo(3)
        assertThat(event.previousStatus).isEqualTo(SepaPaymentStatus.RECEIVED)
        assertThat(event.newStatus).isEqualTo(SepaPaymentStatus.REJECTED)
        assertThat(event.rejectReason).isEqualTo("SANCTIONS_HIT")
        assertThat(event.rejectDetail).isEqualTo("OFAC hit")
        assertThat(event.occurredAt).isEqualTo(now)
        assertThat(event.sourceService).isEqualTo("sepa-payment")
    }

    @Test
    fun `toStatusChangedEvent leaves reject fields null for a non-reject transition`() {
        val payment = payment(status = SepaPaymentStatus.VALIDATED)

        val event = payment.toStatusChangedEvent(SepaPaymentStatus.RECEIVED, Clock.systemUTC())

        assertThat(event.newStatus).isEqualTo(SepaPaymentStatus.VALIDATED)
        assertThat(event.rejectReason).isNull()
        assertThat(event.rejectDetail).isNull()
    }

    @Test
    fun `return evidence wire record preserves caller attribution and reversal outcome`() {
        val paymentId = UUID.randomUUID()
        val evidence = SepaPaymentReturnedEvent(
            paymentId = paymentId,
            version = 5,
            originalEndToEndId = "E2E-return",
            returnReasonCode = "AC04",
            actorId = "operator-example",
            actorType = "ROLE_OPERATOR",
            correlationId = "correlation-example",
            reversalPerformed = true,
            occurredAt = Instant.parse("2026-01-02T12:00:00Z"),
        )

        val json = mapper.readTree(mapper.writeValueAsString(evidence))

        assertThat(json.path("paymentId").asText()).isEqualTo(paymentId.toString())
        assertThat(json.path("version").asLong()).isEqualTo(5)
        assertThat(json.path("originalEndToEndId").asText()).isEqualTo("E2E-return")
        assertThat(json.path("returnReasonCode").asText()).isEqualTo("AC04")
        assertThat(json.path("actorId").asText()).isEqualTo("operator-example")
        assertThat(json.path("actorType").asText()).isEqualTo("ROLE_OPERATOR")
        assertThat(json.path("correlationId").asText()).isEqualTo("correlation-example")
        assertThat(json.path("reversalPerformed").asBoolean()).isTrue()
        assertThat(json.path("eventType").asText()).isEqualTo(RETURN_EVIDENCE_EVENT_TYPE)
        assertThat(json.path("sourceService").asText()).isEqualTo("sepa-payment")
    }

    @Test
    fun `return evidence wire record can state that no reversal was performed`() {
        val evidence = SepaPaymentReturnedEvent(
            paymentId = UUID.randomUUID(),
            version = 2,
            originalEndToEndId = "E2E-no-reversal",
            returnReasonCode = null,
            actorId = "operator-example",
            actorType = "ROLE_OPERATOR",
            correlationId = null,
            reversalPerformed = false,
            occurredAt = Instant.parse("2026-01-02T12:00:00Z"),
        )

        val json = mapper.readTree(mapper.writeValueAsString(evidence))

        assertThat(json.path("reversalPerformed").asBoolean()).isFalse()
        assertThat(json.path("returnReasonCode").isNull).isTrue()
        assertThat(json.path("correlationId").isNull).isTrue()
    }
}
