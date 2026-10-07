// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.rest.dto

import com.openbank.libs.domain.money.InvalidMoneyException
import com.openbank.libs.domain.money.InvalidMoneyReason
import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SwiftDtosTest {

    @Test
    fun `toResponse projects the domain model and scales minor units to a major-unit amount`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val createdAt = Instant.parse("2026-05-27T00:00:00Z")
        val message = message(id = id, amountMinorUnits = 123_456, createdAt = createdAt)

        val response = message.toResponse()

        assertThat(response.id).isEqualTo(id)
        assertThat(response.messageType).isEqualTo(SwiftMessageType.MT103)
        assertThat(response.senderBic).isEqualTo("ABCDEFGH")
        assertThat(response.receiverBic).isEqualTo("IJKLMNOP")
        assertThat(response.amount).isEqualTo(1234.56)
        assertThat(response.currency).isEqualTo("EUR")
        assertThat(response.status).isEqualTo(SwiftStatus.VALIDATED)
        assertThat(response.createdAt).isEqualTo(createdAt)
        assertThat(response.reference).isEqualTo("TRX-001")
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource("123456, EUR, 1234.56", "1000, JPY, 1000.0", "1500, KWD, 1.5", "1500, BHD, 1.5")
    fun `toResponse reads minor units at the currency's own scale`(minor: Long, currency: String, major: Double) {
        // #11604: main divided every currency by 100, so 1000 yen read as 10.0.
        val response = message(UUID.randomUUID(), minor, Instant.EPOCH, currency).toResponse()

        assertThat(response.amount).isEqualTo(major)
        assertThat(response.currency).isEqualTo(currency)
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
        "150000, EUR, 1500.00",
        "150000.00, EUR, 1500.00",
        "1.5E+5, EUR, 1500.00",
        "1000, JPY, 1000",
        "1000, jpy, 1000",
        "1500, KWD, 1.500",
        "1500, BHD, 1.500",
        "9223372036854775807, JPY, 9223372036854775807",
    )
    fun `inboundMoney accepts any ISO 4217 currency with a minor unit`(minor: String, currency: String, major: String) {
        val money = inboundMoney(BigDecimal(minor), currency)

        assertThat(money.amount.toPlainString()).isEqualTo(major)
        assertThat(money.currency.code).isEqualTo(currency.uppercase())
    }

    @ParameterizedTest(name = "{0} {1} -> {2} on {3}")
    @CsvSource(
        "150.5, EUR, SCALE_EXCEEDED, amountMinorUnits",
        "0.1, JPY, SCALE_EXCEEDED, amountMinorUnits",
        "1000.5, KWD, SCALE_EXCEEDED, amountMinorUnits",
        "9223372036854775808, JPY, AMOUNT_OUT_OF_RANGE, amountMinorUnits",
        "1E+25, EUR, AMOUNT_OUT_OF_RANGE, amountMinorUnits",
        "100, XYZ, CURRENCY_UNSUPPORTED, currency",
        "100, XAU, CURRENCY_UNSUPPORTED, currency",
        "100, EURO, CURRENCY_UNSUPPORTED, currency",
    )
    fun `inboundMoney refuses what Money cannot hold, naming the field`(
        minor: String,
        currency: String,
        reason: InvalidMoneyReason,
        field: String,
    ) {
        assertThatThrownBy { inboundMoney(BigDecimal(minor), currency) }
            .isInstanceOfSatisfying(InvalidMoneyException::class.java) {
                assertThat(it.reason).isEqualTo(reason)
                assertThat(it.field).isEqualTo(field)
                assertThat(it.clientMessage).doesNotContain(minor)
            }
    }

    @Test
    fun `the view keeps the published SwiftMessage members and order`() {
        val view = message(UUID.randomUUID(), 1000, Instant.EPOCH, "JPY").toView()
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
            .registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        val names = mapper.readTree(mapper.writeValueAsString(view)).fieldNames().asSequence().toList()

        assertThat(names).containsExactly(
            "id", "idempotencyKey", "messageType", "senderBic", "receiverBic", "transactionReference",
            "relatedReference", "valueDate", "currency", "amountMinorUnits", "orderingCustomerAccount",
            "orderingCustomerAccountId", "orderingCustomerName", "beneficiaryAccount", "beneficiaryName",
            "remittanceInfo", "chargeCode", "priority", "status", "rawMt", "ackReceivedAt", "rejectionReason",
            "createdAt", "updatedAt", "version",
        )
        assertThat(view.currency).isEqualTo("JPY")
        assertThat(view.amountMinorUnits).isEqualTo(1000L)
    }

    private fun message(id: UUID, amountMinorUnits: Long, createdAt: Instant, currency: String = "EUR") = SwiftMessage(
        id = id,
        idempotencyKey = "idem-1",
        messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH",
        receiverBic = "IJKLMNOP",
        transactionReference = "TRX-001",
        relatedReference = null,
        valueDate = "20260527",
        amount = SwiftMessage.moneyOfMinorUnits(amountMinorUnits, currency),
        orderingCustomerAccount = null,
        orderingCustomerAccountId = null,
        orderingCustomerName = null,
        beneficiaryAccount = "GB33BUKB20201555555555",
        beneficiaryName = "Bob",
        remittanceInfo = null,
        chargeCode = "SHA",
        priority = SwiftPriority.NORMAL,
        status = SwiftStatus.VALIDATED,
        rawMt = null,
        ackReceivedAt = null,
        rejectionReason = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
