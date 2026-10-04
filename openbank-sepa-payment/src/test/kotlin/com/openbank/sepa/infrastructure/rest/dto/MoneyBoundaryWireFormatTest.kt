// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.domain.error.PlatformErrorCode
import com.openbank.libs.domain.money.InvalidMoneyException
import com.openbank.libs.domain.money.Money
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.infrastructure.kafka.KafkaSepaPaymentEventPublisher
import com.openbank.sepa.infrastructure.persistence.mapper.toDomain
import com.openbank.sepa.infrastructure.persistence.mapper.toEntity
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * #11642 pilot: the inbound boundary builds a kernel [Money]. For an amount already written at the
 * currency's scale (`250.00 EUR`, the shape every pact and client in this repo sends) the bytes on
 * every outbound surface — the 201 body, the `sepa.payment.created` payload and the entity handed to
 * Hibernate — are exactly what the raw `BigDecimal` produced before, because `Money` holds that same
 * value at that same scale. The tests write the pre-change bytes as LITERALS, never derived from the
 * code under test, so a change of representation cannot move both sides at once.
 */
class MoneyBoundaryWireFormatTest {

    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @Test
    fun `a canonical amount reaches the response, the created event and the entity byte-identical`() {
        val payment = paymentFrom(requestJson(amount = "250.00", currency = "EUR"))

        val responseJson = objectMapper.writeValueAsString(payment.toResponse())
        val eventJson = KafkaSepaPaymentEventPublisher(
            mockk(relaxed = true),
            objectMapper,
        ).paymentCreatedPayload(payment)
        val entity = payment.toEntity()

        assertThat(responseJson).contains(""""amount":250.00,"currency":"EUR"""")
        assertThat(eventJson).contains(""""amount":250.00,"currency":"EUR"""")
        assertThat(entity.amount.toPlainString()).isEqualTo("250.00")
        assertThat(entity.currency).isEqualTo("EUR")
    }

    @Test
    fun `a row read back at the column scale is held as Money at the currency scale`() {
        // NUMERIC(20,6) pads every stored amount to six places. Money drops the padding, so the
        // read path renders 250.00 where the raw BigDecimal rendered 250.000000 (numerically equal).
        val stored = paymentFrom(requestJson(amount = "250.00", currency = "EUR")).toEntity()
            .also { it.amount = BigDecimal("250.000000") }

        val readBack = stored.toDomain()

        assertThat(readBack.amount).isEqualTo(Money.of("250.00", "EUR"))
        assertThat(objectMapper.writeValueAsString(readBack.toResponse())).contains(""""amount":250.00,""")
    }

    @Test
    fun `a stored amount Money cannot represent is an internal fault - never a 400 on read`() {
        val stored = paymentFrom(requestJson(amount = "250.00", currency = "EUR")).toEntity()
            .also { it.amount = BigDecimal("1.005000") }

        assertThatThrownBy { stored.toDomain() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(stored.paymentId.toString())
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
        "1.005, EUR, AMOUNT_SCALE_EXCEEDED",
        "1.5, JPY, AMOUNT_SCALE_EXCEEDED",
        "1, XYZ, CURRENCY_UNSUPPORTED",
        "1, XAU, CURRENCY_UNSUPPORTED",
        "1, EURO, CURRENCY_UNSUPPORTED",
        "1, '', CURRENCY_UNSUPPORTED",
        "100000000000000000000, EUR, VALIDATION_ERROR",
    )
    fun `the boundary names why Money refused`(amount: String, currency: String, code: String) {
        assertThatThrownBy { Money.parseInbound(BigDecimal(amount), currency) }
            .isInstanceOfSatisfying(InvalidMoneyException::class.java) {
                assertThat(it.errorCode.code).isEqualTo(code)
                assertThat(it.field).isEqualTo(if (code == "CURRENCY_UNSUPPORTED") "currency" else "amount")
            }
    }

    @Test
    fun `codes are well-formed VALIDATION codes`() {
        listOf(PlatformErrorCode.AMOUNT_SCALE_EXCEEDED, PlatformErrorCode.CURRENCY_UNSUPPORTED)
            .forEach { assertThat(it.category.name).isEqualTo("VALIDATION") }
        assertThat(PlatformErrorCode.VALIDATION_ERROR.code).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `lower-case and padded currency codes keep meaning what they meant`() {
        assertThat(Money.parseInbound(BigDecimal("10.5"), " eur ")).isEqualTo(Money.of("10.50", "EUR"))
    }

    private fun requestJson(amount: String, currency: String) =
        """
        {"type":"SCT","debtorAccountId":"${UUID.randomUUID()}",
         "debtorIban":"DE89370400440532013000","debtorName":"Alice",
         "creditorIban":"FR1420041010050500013M02606","creditorName":"Bob",
         "creditorBic":null,"amount":$amount,"currency":"$currency",
         "remittanceInfo":null,"endToEndId":"E2E-WIRE"}
        """.trimIndent()

    private fun paymentFrom(json: String): SepaPayment {
        val command = objectMapper.readValue(json, CreateSepaPaymentRequest::class.java).toCommand("idem-wire")
        val now = Instant.parse("2026-10-02T10:00:00Z")
        return SepaPayment(
            id = UUID.randomUUID(),
            idempotencyKey = command.idempotencyKey,
            type = command.type,
            status = SepaPaymentStatus.RECEIVED,
            debtorAccountId = command.debtorAccountId,
            debtorIban = command.debtorIban,
            debtorName = command.debtorName,
            creditorIban = command.creditorIban,
            creditorName = command.creditorName,
            creditorBic = command.creditorBic,
            amount = command.amount,
            remittanceInfo = command.remittanceInfo,
            endToEndId = command.endToEndId!!,
            rejectReason = null,
            rejectDetail = null,
            submittedAt = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
