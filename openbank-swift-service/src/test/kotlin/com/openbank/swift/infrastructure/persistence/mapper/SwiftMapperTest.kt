// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.infrastructure.persistence.mapper

import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.domain.model.SwiftMessageType
import com.openbank.swift.domain.model.SwiftPriority
import com.openbank.swift.domain.model.SwiftStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class SwiftMapperTest {

    @Test
    fun `toEntity copies every field from the domain model`() {
        val domain = message()

        val entity = domain.toEntity()

        assertThat(entity.id).isEqualTo(domain.id)
        assertThat(entity.idempotencyKey).isEqualTo(domain.idempotencyKey)
        assertThat(entity.messageType).isEqualTo(domain.messageType)
        assertThat(entity.senderBic).isEqualTo(domain.senderBic)
        assertThat(entity.receiverBic).isEqualTo(domain.receiverBic)
        assertThat(entity.transactionReference).isEqualTo(domain.transactionReference)
        assertThat(entity.relatedReference).isEqualTo(domain.relatedReference)
        val expectedDate = LocalDate.parse(domain.valueDate, DateTimeFormatter.ofPattern("yyyyMMdd"))
        assertThat(entity.valueDate).isEqualTo(expectedDate)
        assertThat(entity.currency).isEqualTo(domain.currency)
        assertThat(entity.amountMinorUnits).isEqualTo(domain.amountMinorUnits)
        assertThat(entity.orderingCustomerAccount).isEqualTo(domain.orderingCustomerAccount)
        assertThat(entity.orderingCustomerAccountId).isEqualTo(domain.orderingCustomerAccountId)
        assertThat(entity.orderingCustomerName).isEqualTo(domain.orderingCustomerName)
        assertThat(entity.beneficiaryAccount).isEqualTo(domain.beneficiaryAccount)
        assertThat(entity.beneficiaryName).isEqualTo(domain.beneficiaryName)
        assertThat(entity.remittanceInfo).isEqualTo(domain.remittanceInfo)
        assertThat(entity.chargeCode).isEqualTo(domain.chargeCode)
        assertThat(entity.priority).isEqualTo(domain.priority)
        assertThat(entity.status).isEqualTo(domain.status)
        assertThat(entity.rawMt).isEqualTo(domain.rawMt)
        assertThat(entity.ackReceivedAt).isEqualTo(domain.ackReceivedAt)
        assertThat(entity.rejectionReason).isEqualTo(domain.rejectionReason)
        assertThat(entity.createdAt).isEqualTo(domain.createdAt)
        assertThat(entity.updatedAt).isEqualTo(domain.updatedAt)
    }

    @Test
    fun `entity to domain round-trips back to an equal model`() {
        val original = message(
            relatedReference = "REL-9",
            orderingCustomerAccount = "DE89370400440532013000",
            orderingCustomerAccountId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            orderingCustomerName = "Alice",
            remittanceInfo = "Invoice 1",
            rawMt = "{1:F01ABCDEFGHAXXX0000000000}",
            ackReceivedAt = Instant.parse("2026-05-28T10:00:00Z"),
            rejectionReason = "n/a",
            status = SwiftStatus.ACKNOWLEDGED,
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped).isEqualTo(original)
    }

    @Test
    fun `round-trip preserves null optional fields`() {
        val original = message(
            relatedReference = null,
            orderingCustomerAccount = null,
            orderingCustomerAccountId = null,
            orderingCustomerName = null,
            remittanceInfo = null,
            rawMt = null,
            ackReceivedAt = null,
            rejectionReason = null,
        )

        val roundTripped = original.toEntity().toDomain()

        assertThat(roundTripped).isEqualTo(original)
    }

    private fun message(
        relatedReference: String? = "REL-1",
        orderingCustomerAccount: String? = "DE89370400440532013000",
        orderingCustomerAccountId: UUID? = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        orderingCustomerName: String? = "Alice",
        remittanceInfo: String? = "Invoice 1",
        rawMt: String? = null,
        ackReceivedAt: Instant? = null,
        rejectionReason: String? = null,
        status: SwiftStatus = SwiftStatus.VALIDATED,
    ) = SwiftMessage(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        idempotencyKey = "idem-1",
        messageType = SwiftMessageType.MT103,
        senderBic = "ABCDEFGH",
        receiverBic = "IJKLMNOP",
        transactionReference = "TRX-001",
        relatedReference = relatedReference,
        valueDate = "20260527",
        currency = "EUR",
        amountMinorUnits = 1000,
        orderingCustomerAccount = orderingCustomerAccount,
        orderingCustomerAccountId = orderingCustomerAccountId,
        orderingCustomerName = orderingCustomerName,
        beneficiaryAccount = "GB33BUKB20201555555555",
        beneficiaryName = "Bob",
        remittanceInfo = remittanceInfo,
        chargeCode = "SHA",
        priority = SwiftPriority.NORMAL,
        status = status,
        rawMt = rawMt,
        ackReceivedAt = ackReceivedAt,
        rejectionReason = rejectionReason,
        createdAt = Instant.parse("2026-05-27T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-27T00:00:00Z"),
    )
}
