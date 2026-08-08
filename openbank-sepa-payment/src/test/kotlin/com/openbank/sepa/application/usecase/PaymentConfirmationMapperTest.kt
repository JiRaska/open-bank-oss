// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.usecase

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PaymentConfirmationMapperTest {

    private fun payment(
        status: SepaPaymentStatus = SepaPaymentStatus.COMPLETED,
        completedAt: Instant? = Instant.parse("2026-08-01T10:15:30Z"),
        remittanceInfo: String? = "Invoice 42",
    ) = SepaPayment(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
        idempotencyKey = "idem-conf-1",
        type = SepaPaymentType.SCT,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR1420041010050500013M02606",
        creditorName = "Bob Creditor",
        creditorBic = "BNPAFRPPXXX",
        amount = BigDecimal("125.50"),
        currency = "EUR",
        remittanceInfo = remittanceInfo,
        endToEndId = "E2E-CONF-0001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = Instant.parse("2026-08-01T10:00:00Z"),
        completedAt = completedAt,
        createdAt = Instant.parse("2026-08-01T09:59:00Z"),
        updatedAt = Instant.parse("2026-08-01T10:15:30Z"),
    )

    @Test
    fun `maps every ADR-0248 field under the document namespace`() {
        val data = payment().toConfirmationData()

        @Suppress("UNCHECKED_CAST")
        val document = data["document"] as Map<String, Any?>

        assertThat(document["paymentReference"]).isEqualTo("00000000-0000-0000-0000-0000000000aa")
        assertThat(document["endToEndId"]).isEqualTo("E2E-CONF-0001")
        assertThat(document["executedAt"]).isEqualTo("2026-08-01T10:15:30Z")
        assertThat(document["amount"]).isEqualTo("125.50")
        assertThat(document["currency"]).isEqualTo("EUR")
        assertThat(document["debtorIban"]).isEqualTo("DE89370400440532013000")
        assertThat(document["creditorIban"]).isEqualTo("FR1420041010050500013M02606")
        assertThat(document["creditorName"]).isEqualTo("Bob Creditor")
        assertThat(document["remittanceInfo"]).isEqualTo("Invoice 42")
        assertThat(document["status"]).isEqualTo("COMPLETED")
        assertThat(document["scaEvidenceRef"]).isNull()
    }

    @Test
    fun `a null remittanceInfo maps to an empty string, not null`() {
        val data = payment(remittanceInfo = null).toConfirmationData()

        @Suppress("UNCHECKED_CAST")
        val document = data["document"] as Map<String, Any?>
        assertThat(document["remittanceInfo"]).isEqualTo("")
    }

    @Test
    fun `a null completedAt maps to an empty string, not null`() {
        val data = payment(completedAt = null).toConfirmationData()

        @Suppress("UNCHECKED_CAST")
        val document = data["document"] as Map<String, Any?>
        assertThat(document["executedAt"]).isEqualTo("")
    }
}
