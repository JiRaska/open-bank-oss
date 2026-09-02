// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.domestic.domain.event.toStatusChangedEvent
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal object DomesticPaymentStatusPactFixture {
    const val PROVIDER_STATE = "a delegated domestic payment has changed status"
    const val INTERACTION = "a delegated domestic payment status changed event"

    private val occurredAt = Instant.parse("2026-09-01T12:00:00Z")
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun payload(): String {
        val previous = payment(DomesticPaymentStatus.SENT_TO_CLEARING)
        val current = previous.copy(
            status = DomesticPaymentStatus.SETTLED,
            settledAt = occurredAt,
            updatedAt = occurredAt,
        )
        return mapper.writeValueAsString(
            current.toStatusChangedEvent(previous, Clock.fixed(occurredAt, ZoneOffset.UTC)),
        )
    }

    private fun payment(status: DomesticPaymentStatus) = DomesticPayment(
        id = UUID.fromString("10000000-0000-4000-8000-000000000001"),
        idempotencyKey = "delegated-payment-pact-1",
        status = status,
        debtorAccountId = UUID.fromString("10000000-0000-4000-8000-000000000002"),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Delegated payer",
        creditorAccountNumber = "0987654321",
        creditorBankCode = "2010",
        creditorName = "Pact payee",
        amount = BigDecimal("1500.00"),
        currency = "CZK",
        variableSymbol = null,
        specificSymbol = null,
        constantSymbol = null,
        messageForPayee = null,
        priority = DomesticPaymentPriority.STANDARD,
        transferScope = DomesticTransferScope.EXTERNAL,
        technicalAccountCode = null,
        statementLabel = null,
        endToEndId = "DOMS-DELEGATED-PACT",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = occurredAt.minusSeconds(60),
        settledAt = null,
        createdAt = occurredAt.minusSeconds(120),
        updatedAt = occurredAt.minusSeconds(60),
        initiatedByPartyId = UUID.fromString("10000000-0000-4000-8000-000000000003"),
        delegationId = UUID.fromString("10000000-0000-4000-8000-000000000004"),
        reservationId = UUID.fromString("10000000-0000-4000-8000-000000000005"),
    )
}
