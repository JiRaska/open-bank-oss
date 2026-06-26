// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.infrastructure.rest.dto

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SepaPaymentDtosTest {

    @Test
    fun `CreateSepaPaymentRequest toCommand maps every field and parses the type`() {
        val accountId = UUID.randomUUID()
        val request = CreateSepaPaymentRequest(
            type = "SCT_INST",
            debtorAccountId = accountId,
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Example",
            creditorIban = "FR7630006000011234567890189",
            creditorName = "Bob Example",
            creditorBic = "DEUTDEFF",
            amount = BigDecimal("205.45"),
            currency = "EUR",
            remittanceInfo = "Invoice 1",
            endToEndId = "E2E-1",
        )

        val command = request.toCommand("idem-key")

        assertThat(command.idempotencyKey).isEqualTo("idem-key")
        assertThat(command.type).isEqualTo(SepaPaymentType.SCT_INST)
        assertThat(command.debtorAccountId).isEqualTo(accountId)
        assertThat(command.debtorIban).isEqualTo("DE89370400440532013000")
        assertThat(command.creditorBic).isEqualTo("DEUTDEFF")
        assertThat(command.amount).isEqualByComparingTo(BigDecimal("205.45"))
        assertThat(command.remittanceInfo).isEqualTo("Invoice 1")
        assertThat(command.endToEndId).isEqualTo("E2E-1")
    }

    @Test
    fun `CreateSepaPaymentRequest toCommand carries null optionals through`() {
        val request = CreateSepaPaymentRequest(
            type = "SCT",
            debtorAccountId = UUID.randomUUID(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Example",
            creditorIban = "FR7630006000011234567890189",
            creditorName = "Bob Example",
            creditorBic = null,
            amount = BigDecimal.ONE,
            currency = "EUR",
            remittanceInfo = null,
            endToEndId = null,
        )

        val command = request.toCommand("idem-2")

        assertThat(command.type).isEqualTo(SepaPaymentType.SCT)
        assertThat(command.creditorBic).isNull()
        assertThat(command.remittanceInfo).isNull()
        assertThat(command.endToEndId).isNull()
    }

    @Test
    fun `TransitionSepaPaymentStatusRequest toCommand parses status and reject reason`() {
        val paymentId = UUID.randomUUID()
        val request = TransitionSepaPaymentStatusRequest(
            targetStatus = "REJECTED",
            rejectReason = "INVALID_IBAN",
            rejectDetail = "bad iban",
        )

        val command = request.toCommand(paymentId)

        assertThat(command.paymentId).isEqualTo(paymentId)
        assertThat(command.targetStatus).isEqualTo(SepaPaymentStatus.REJECTED)
        assertThat(command.rejectReason).isEqualTo(SepaRejectReason.INVALID_IBAN)
        assertThat(command.rejectDetail).isEqualTo("bad iban")
    }

    @Test
    fun `TransitionSepaPaymentStatusRequest toCommand leaves null reason null`() {
        val command = TransitionSepaPaymentStatusRequest(targetStatus = "VALIDATED").toCommand(UUID.randomUUID())

        assertThat(command.targetStatus).isEqualTo(SepaPaymentStatus.VALIDATED)
        assertThat(command.rejectReason).isNull()
        assertThat(command.rejectDetail).isNull()
    }

    @Test
    fun `toResponse projects every domain field onto the response`() {
        val payment = SepaPayment(
            id = UUID.randomUUID(),
            idempotencyKey = "idem-resp",
            type = SepaPaymentType.SCT,
            status = SepaPaymentStatus.COMPLETED,
            debtorAccountId = UUID.randomUUID(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Example",
            creditorIban = "FR7630006000011234567890189",
            creditorName = "Bob Example",
            creditorBic = "DEUTDEFF",
            amount = BigDecimal("42.00"),
            currency = "EUR",
            remittanceInfo = "ref",
            endToEndId = "E2E-resp",
            rejectReason = null,
            rejectDetail = null,
            submittedAt = Instant.parse("2026-01-02T10:15:30Z"),
            completedAt = Instant.parse("2026-01-02T10:16:00Z"),
            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
            updatedAt = Instant.parse("2026-01-02T10:16:00Z"),
        )

        val response = payment.toResponse()

        assertThat(response.id).isEqualTo(payment.id)
        assertThat(response.idempotencyKey).isEqualTo("idem-resp")
        assertThat(response.type).isEqualTo(SepaPaymentType.SCT)
        assertThat(response.status).isEqualTo(SepaPaymentStatus.COMPLETED)
        assertThat(response.debtorAccountId).isEqualTo(payment.debtorAccountId)
        assertThat(response.debtorIban).isEqualTo(payment.debtorIban)
        assertThat(response.creditorBic).isEqualTo("DEUTDEFF")
        assertThat(response.amount).isEqualByComparingTo(BigDecimal("42.00"))
        assertThat(response.remittanceInfo).isEqualTo("ref")
        assertThat(response.submittedAt).isEqualTo(payment.submittedAt)
        assertThat(response.completedAt).isEqualTo(payment.completedAt)
        assertThat(response.createdAt).isEqualTo(payment.createdAt)
        assertThat(response.updatedAt).isEqualTo(payment.updatedAt)
    }
}
