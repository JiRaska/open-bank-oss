// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest.dto

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class DomesticPaymentDtosTest {

    private fun createRequest(transferScope: String? = null, technicalAccountCode: String? = null) =
        CreateDomesticPaymentRequest(
            debtorAccountId = UUID.randomUUID(),
            debtorAccountNumber = "19-2000145399",
            debtorBankCode = "0800",
            debtorName = "Jan Novak",
            creditorAccountNumber = "123456789",
            creditorBankCode = "0100",
            creditorName = "Brno Utility",
            amount = BigDecimal("1234.56"),
            currency = "CZK",
            variableSymbol = "2026001",
            specificSymbol = "55",
            constantSymbol = "0308",
            messageForPayee = "Utility bill",
            priority = "URGENT",
            transferScope = transferScope,
            technicalAccountCode = technicalAccountCode,
            statementLabel = "Monthly settlement",
            endToEndId = "DOMU123",
        )

    @Test
    fun `toCommand projects every request field and parses priority`() {
        val request = createRequest()

        val command = request.toCommand("idem-1")

        assertThat(command.idempotencyKey).isEqualTo("idem-1")
        assertThat(command.debtorAccountId).isEqualTo(request.debtorAccountId)
        assertThat(command.debtorAccountNumber).isEqualTo("19-2000145399")
        assertThat(command.debtorBankCode).isEqualTo("0800")
        assertThat(command.debtorName).isEqualTo("Jan Novak")
        assertThat(command.creditorAccountNumber).isEqualTo("123456789")
        assertThat(command.creditorBankCode).isEqualTo("0100")
        assertThat(command.creditorName).isEqualTo("Brno Utility")
        assertThat(command.amount).isEqualByComparingTo(BigDecimal("1234.56"))
        assertThat(command.currency).isEqualTo("CZK")
        assertThat(command.variableSymbol).isEqualTo("2026001")
        assertThat(command.specificSymbol).isEqualTo("55")
        assertThat(command.constantSymbol).isEqualTo("0308")
        assertThat(command.messageForPayee).isEqualTo("Utility bill")
        assertThat(command.priority).isEqualTo(DomesticPaymentPriority.URGENT)
        assertThat(command.statementLabel).isEqualTo("Monthly settlement")
        assertThat(command.endToEndId).isEqualTo("DOMU123")
    }

    @Test
    fun `toCommand does not map transferScope — scope is derived server-side`() {
        val command = createRequest(transferScope = null).toCommand("idem-2")

        // transferScope is derived server-side from creditorBankCode + account lookup;
        // the DTO field is accepted for API compatibility but never forwarded to the command.
        assertThat(command.transferScope).isNull()
        assertThat(command.technicalAccountCode).isNull()
    }

    @Test
    fun `toCommand passes technicalAccountCode when present`() {
        val command = createRequest(
            transferScope = "TECHNICAL_ACCOUNT",
            technicalAccountCode = "TECH-9",
        ).toCommand("idem-3")

        assertThat(command.technicalAccountCode).isEqualTo("TECH-9")
    }

    @Test
    fun `toCommand carries the authenticated actor scope for idempotency binding`() {
        val actorId = UUID.randomUUID()

        val command = createRequest().toCommand(
            idempotencyKey = "idem-actor",
            actorId = actorId,
            actorScope = "https://issuer.example\u001f$actorId",
        )

        assertThat(command.actorId).isEqualTo(actorId)
        assertThat(command.actorScope).isEqualTo("https://issuer.example\u001f$actorId")
    }

    @Test
    fun `transition request toCommand parses target status and reject reason`() {
        val paymentId = UUID.randomUUID()
        val request = TransitionDomesticPaymentStatusRequest(
            targetStatus = "REJECTED",
            rejectReason = "SANCTIONS_HIT",
            rejectDetail = "creditor on list",
        )

        val command = request.toCommand(paymentId)

        assertThat(command.paymentId).isEqualTo(paymentId)
        assertThat(command.targetStatus).isEqualTo(DomesticPaymentStatus.REJECTED)
        assertThat(command.rejectReason).isEqualTo(DomesticRejectReason.SANCTIONS_HIT)
        assertThat(command.rejectDetail).isEqualTo("creditor on list")
    }

    @Test
    fun `transition request toCommand leaves reject reason null when absent`() {
        val command = TransitionDomesticPaymentStatusRequest(targetStatus = "VALIDATED")
            .toCommand(UUID.randomUUID())

        assertThat(command.targetStatus).isEqualTo(DomesticPaymentStatus.VALIDATED)
        assertThat(command.rejectReason).isNull()
        assertThat(command.rejectDetail).isNull()
    }

    @Test
    fun `toResponse projects every domain field`() {
        val payment = DomesticPayment(
            id = UUID.randomUUID(),
            idempotencyKey = "idem-resp",
            status = DomesticPaymentStatus.SETTLED,
            debtorAccountId = UUID.randomUUID(),
            debtorAccountNumber = "1000",
            debtorBankCode = "0800",
            debtorName = "Payer",
            creditorAccountNumber = "2000",
            creditorBankCode = "0100",
            creditorName = "Payee",
            amount = BigDecimal("42.000000"),
            currency = "CZK",
            variableSymbol = "VS",
            specificSymbol = "SS",
            constantSymbol = "CS",
            messageForPayee = "msg",
            priority = DomesticPaymentPriority.STANDARD,
            transferScope = DomesticTransferScope.OWN_ACCOUNTS,
            technicalAccountCode = "TECH",
            statementLabel = "label",
            endToEndId = "DOMS1",
            rejectReason = null,
            rejectDetail = null,
            submittedAt = Instant.parse("2026-06-01T10:00:00Z"),
            settledAt = Instant.parse("2026-06-01T11:00:00Z"),
            createdAt = Instant.parse("2026-06-01T09:00:00Z"),
            updatedAt = Instant.parse("2026-06-01T11:00:00Z"),
        )

        val response = payment.toResponse()

        assertThat(response.id).isEqualTo(payment.id)
        assertThat(response.idempotencyKey).isEqualTo("idem-resp")
        assertThat(response.status).isEqualTo(DomesticPaymentStatus.SETTLED)
        assertThat(response.debtorAccountId).isEqualTo(payment.debtorAccountId)
        assertThat(response.debtorAccountNumber).isEqualTo("1000")
        assertThat(response.debtorBankCode).isEqualTo("0800")
        assertThat(response.debtorName).isEqualTo("Payer")
        assertThat(response.creditorAccountNumber).isEqualTo("2000")
        assertThat(response.creditorBankCode).isEqualTo("0100")
        assertThat(response.creditorName).isEqualTo("Payee")
        assertThat(response.amount).isEqualByComparingTo(BigDecimal("42.000000"))
        assertThat(response.currency).isEqualTo("CZK")
        assertThat(response.variableSymbol).isEqualTo("VS")
        assertThat(response.specificSymbol).isEqualTo("SS")
        assertThat(response.constantSymbol).isEqualTo("CS")
        assertThat(response.messageForPayee).isEqualTo("msg")
        assertThat(response.priority).isEqualTo(DomesticPaymentPriority.STANDARD)
        assertThat(response.transferScope).isEqualTo(DomesticTransferScope.OWN_ACCOUNTS)
        assertThat(response.technicalAccountCode).isEqualTo("TECH")
        assertThat(response.statementLabel).isEqualTo("label")
        assertThat(response.endToEndId).isEqualTo("DOMS1")
        assertThat(response.rejectReason).isNull()
        assertThat(response.rejectDetail).isNull()
        assertThat(response.submittedAt).isEqualTo(payment.submittedAt)
        assertThat(response.settledAt).isEqualTo(payment.settledAt)
        assertThat(response.createdAt).isEqualTo(payment.createdAt)
        assertThat(response.updatedAt).isEqualTo(payment.updatedAt)
    }
}
