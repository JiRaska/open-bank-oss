// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.domain.model

import com.openbank.swift.domain.model.SwiftMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.util.UUID

class SwiftMessageTest {

    @Test
    fun `validate returns error for short BIC`() {
        val message = message(senderBic = "ABC123")

        assertThat(message.validate()).contains("Invalid sender BIC: ABC123")
    }

    @ParameterizedTest
    @CsvSource("ABCDEFG,Invalid sender BIC: ABCDEFG", "ABCDEFGHIJKL,Invalid sender BIC: ABCDEFGHIJKL")
    fun `sender BIC outside the inclusive 8 to 11 character range is rejected`(bic: String, error: String) {
        assertThat(message(senderBic = bic).validate()).contains(error)
    }

    @ParameterizedTest
    @CsvSource("ABCDEFG,Invalid receiver BIC: ABCDEFG", "ABCDEFGHIJKL,Invalid receiver BIC: ABCDEFGHIJKL")
    fun `receiver BIC outside the inclusive 8 to 11 character range is rejected`(bic: String, error: String) {
        assertThat(message(receiverBic = bic).validate()).contains(error)
    }

    @Test
    fun `blank transaction reference is rejected`() {
        assertThat(message(transactionReference = "  ").validate()).contains("Transaction reference required")
    }

    @ParameterizedTest
    @CsvSource("ABCDEFGH,IJKLMNOP", "ABCDEFGHIJK,IJKLMNOPQRS")
    fun `both BIC length boundaries are accepted`(sender: String, receiver: String) {
        assertThat(message(senderBic = sender, receiverBic = receiver).validate()).isEmpty()
    }

    @Test
    fun `validate returns error for non-positive amount`() {
        val message = message(amountMinorUnits = 0)

        assertThat(message.validate()).contains("Amount must be positive")
    }

    @Test
    fun `validate returns error for invalid charge code`() {
        val message = message(chargeCode = "XYZ")

        assertThat(message.validate()).contains("Invalid charge code: XYZ")
    }

    @Test
    fun `validate returns error for non-YYYYMMDD valueDate`() {
        val message = message(valueDate = "2026-05-27")

        assertThat(message.validate()).contains("Invalid valueDate '2026-05-27': must be YYYYMMDD")
    }

    @Test
    fun `valid message returns empty error list`() {
        val message = message()

        assertThat(message.validate()).isEmpty()
    }

    private fun message(
        senderBic: String = "ABCDEFGH",
        receiverBic: String = "IJKLMNOP",
        amountMinorUnits: Long = 1000,
        chargeCode: String = "SHA",
        valueDate: String = "20260527",
        transactionReference: String = "TRX-001",
    ) = SwiftMessage(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        idempotencyKey = "idem-1",
        messageType = SwiftMessageType.MT103,
        senderBic = senderBic,
        receiverBic = receiverBic,
        transactionReference = transactionReference,
        relatedReference = null,
        valueDate = valueDate,
        amount = SwiftMessage.moneyOfMinorUnits(amountMinorUnits, "EUR"),
        orderingCustomerAccount = "DE89370400440532013000",
        orderingCustomerAccountId = null,
        orderingCustomerName = "Alice",
        beneficiaryAccount = "GB33BUKB20201555555555",
        beneficiaryName = "Bob",
        remittanceInfo = "Invoice 1",
        chargeCode = chargeCode,
        priority = SwiftPriority.NORMAL,
        status = SwiftStatus.PENDING,
        rawMt = null,
        ackReceivedAt = null,
        rejectionReason = null,
        createdAt = Instant.parse("2026-05-27T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-27T00:00:00Z"),
    )
}
