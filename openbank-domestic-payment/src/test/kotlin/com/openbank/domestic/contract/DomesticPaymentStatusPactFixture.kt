// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import au.com.dius.pact.provider.MessageAndMetadata
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.domestic.domain.event.toStatusChangedEvent
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// Not `internal`: `providerPactTest` (build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts) is a
// separate Kotlin compilation from `test` that pulls this file's directory in by source-set
// inclusion, not by depending on test's compiled output — `internal` here is invisible from
// *ProviderVerificationTest.kt even though both sit under src/test/kotlin (issue #4414).
object DomesticPaymentStatusPactFixture {
    const val PROVIDER_STATE = "a delegated domestic payment has changed status"
    const val INTERACTION = "a delegated domestic payment status changed event"

    private val occurredAt = Instant.parse("2026-09-01T12:00:00Z")
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /** The outbox `eventType` every status transition is written with, i.e. the `ce-type` header. */
    const val CE_TYPE = "domestic.payment.status-changed"

    fun payload(): String = payload(DomesticPaymentStatus.SENT_TO_CLEARING, DomesticPaymentStatus.SETTLED)

    /**
     * A real transition through the domain (`DomesticPayment.transitionTo` refuses an illegal one)
     * serialised by the same mapper as the outbox payload, with the outbox's `ce-type` header.
     */
    fun message(previousStatus: DomesticPaymentStatus, newStatus: DomesticPaymentStatus): MessageAndMetadata =
        MessageAndMetadata(
            payload(previousStatus, newStatus).toByteArray(StandardCharsets.UTF_8),
            mapOf(OutboxKafkaHeaders.HEADER_EVENT_TYPE to CE_TYPE),
        )

    fun payload(previousStatus: DomesticPaymentStatus, newStatus: DomesticPaymentStatus): String {
        val previous = payment(previousStatus)
        val current = previous.transitionTo(
            newStatus,
            reason = if (newStatus == DomesticPaymentStatus.REJECTED) DomesticRejectReason.INSUFFICIENT_FUNDS else null,
            clock = Clock.fixed(occurredAt, ZoneOffset.UTC),
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
        amount = Money.of(BigDecimal("1500.00"), "CZK"),
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
