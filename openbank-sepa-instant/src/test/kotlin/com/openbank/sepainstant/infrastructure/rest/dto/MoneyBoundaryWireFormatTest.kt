// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.rest.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.libs.domain.money.InvalidMoneyException
import com.openbank.libs.domain.money.Money
import com.openbank.sepainstant.domain.event.SctInstPaymentSubmitted
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.infrastructure.persistence.mapper.SctInstMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * #11604: for valid input the bytes that leave the service — the DB column value, the
 * `SctInstPaymentSubmitted` event and the outbound adapters' BigDecimal — are what they were when
 * the domain carried a bare BigDecimal at the request's own scale whenever that scale already
 * equals the currency's (the 2dp form every caller sends today).
 */
class MoneyBoundaryWireFormatTest {

    private val json = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val mapper = SctInstMapper()
    private val now = OffsetDateTime.parse("2026-10-03T10:00:00Z")

    @ParameterizedTest(name = "{0} {1} -> column {2}, event {2}")
    @CsvSource("99.99, EUR, 99.99", "0.01, EUR, 0.01", "100000.00, EUR, 100000.00", "10.5, eur, 10.50")
    fun `valid input reaches the column and the event at the currency scale`(
        amount: String,
        currency: String,
        expected: String,
    ) {
        val request = json.readValue(requestJson(amount, currency), SubmitSctInstRequest::class.java)
        val money = Money.parseInbound(request.amount, request.currency)
        val payment = payment(money)

        val entity = mapper.toEntity(payment)
        assertThat(entity.amount).isEqualTo(BigDecimal(expected))
        assertThat(entity.currency).isEqualTo("EUR")
        assertThat(mapper.toDomain(entity)).isEqualTo(payment)

        val event = json.writeValueAsString(
            SctInstPaymentSubmitted(
                paymentId = payment.paymentId,
                debtorIban = payment.debtorIban,
                creditorIban = payment.creditorIban,
                amount = payment.amount.amount,
                currency = payment.currency,
                endToEndId = payment.endToEndId,
                occurredAt = now,
            ),
        )
        assertThat(event).contains("\"amount\":$expected,\"currency\":\"EUR\"")
    }

    @Test
    fun `a legacy row Money cannot represent is an internal fault naming the row, not a 400`() {
        val entity = mapper.toEntity(payment(Money.of("1.00", "EUR"))).also { it.amount = BigDecimal("1.005000") }
        assertThatThrownBy { mapper.toDomain(entity) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(entity.paymentId.toString())
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
        "1.005, EUR, AMOUNT_SCALE_EXCEEDED",
        "10.00, XYZ, CURRENCY_UNSUPPORTED",
        "10.00, '', CURRENCY_UNSUPPORTED",
        "100000000000000000000, EUR, VALIDATION_ERROR",
    )
    fun `the boundary names why Money refused`(amount: String, currency: String, code: String) {
        assertThatThrownBy { Money.parseInbound(BigDecimal(amount), currency) }
            .isInstanceOfSatisfying(InvalidMoneyException::class.java) {
                assertThat(it.errorCode.code).isEqualTo(code)
                assertThat(it.field).isEqualTo(if (code == "CURRENCY_UNSUPPORTED") "currency" else "amount")
            }
    }

    private fun payment(money: Money) = SctInstPayment(
        paymentId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        idempotencyKey = "idem-wire",
        debtorAccountId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice",
        creditorIban = "FR7630006000011234567890189",
        creditorName = "Bob",
        creditorBic = null,
        amount = money,
        remittanceInfo = null,
        endToEndId = "E2E-WIRE",
        executionTimeoutAt = null,
        settledAt = null,
        recalledAt = null,
        recallReason = null,
        rejectReason = null,
        rejectDetail = null,
        submittedAt = now,
        createdAt = now,
        updatedAt = now,
    )

    private fun requestJson(amount: String, currency: String) =
        """
        {"idempotencyKey":"k","debtorAccountId":"22222222-2222-2222-2222-222222222222",
         "debtorIban":"DE89370400440532013000","debtorName":"Alice",
         "creditorIban":"FR7630006000011234567890189","creditorName":"Bob","creditorBic":null,
         "amount":$amount,"currency":"$currency","remittanceInfo":null,"endToEndId":"E2E-WIRE"}
        """.trimIndent()
}
