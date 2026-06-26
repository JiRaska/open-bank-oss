// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.infrastructure.rest.dto

import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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

    private fun message(id: UUID, amountMinorUnits: Long, createdAt: Instant) = SwiftMessage(
        id = id,
        idempotencyKey = "idem-1",
        messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH",
        receiverBic = "IJKLMNOP",
        transactionReference = "TRX-001",
        relatedReference = null,
        valueDate = "20260527",
        currency = "EUR",
        amountMinorUnits = amountMinorUnits,
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
