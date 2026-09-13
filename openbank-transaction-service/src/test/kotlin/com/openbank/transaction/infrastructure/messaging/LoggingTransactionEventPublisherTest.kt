// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.messaging

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.domain.money.Money
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Serialization round-trip for transaction-service's Kafka event payloads (issue #3994/#5256,
 * fleet follow-up to #5255's domestic-payment fix and #5267's account/party-service fix).
 *
 * `sourceService` is the strongest (EVENT-sourced) attribution `AuditConsumer` reads
 * (`node.textOrNull("sourceService")`) — before this field none of transaction-service's four
 * event types carried such a key, and every audit row for transaction-service either fell back
 * to `EventAttribution`'s single `openbank.transactions.transaction.initiated` topic-table entry
 * (correct but TOPIC-sourced for `TransactionInitiatedEvent`, since all four event types share
 * that one outbound topic) or, for the other three event types with no distinct topic entry, the
 * `"unknown"` sentinel.
 *
 * `eventType` ("TransactionInitiated" etc., via `DomainEvent`) is NOT renamed to the audit
 * fleet's SCREAMING_SNAKE_CASE convention here: it is a load-bearing discriminator read verbatim
 * by the fraud feature engine as `VelocityFeatures.TRANSACTION_INITIATED` — same discipline as
 * account-service's #5267 fix. Only `sourceService`, a field nothing else on the fleet reads, is
 * new.
 */
class LoggingTransactionEventPublisherTest {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private val clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC)

    private val publisher = LoggingTransactionEventPublisher(objectMapper, clock)

    private fun transaction() = Transaction(
        id = UUID.randomUUID(),
        referenceNumber = "REF-1",
        type = TransactionType.TRANSFER,
        sourceAccountId = UUID.randomUUID(),
        targetAccountId = UUID.randomUUID(),
        amount = Money.of(BigDecimal("100.50"), "CZK"),
        fxRate = null,
        baseAmount = Money.of(BigDecimal("100.50"), "CZK"),
        status = TransactionStatus.PENDING,
        description = "transfer",
        valueDate = LocalDate.of(2026, 8, 16),
        bookingDate = LocalDate.of(2026, 8, 16),
        initiatedAt = Instant.parse("2026-08-16T10:00:00Z"),
        completedAt = null,
        failedAt = null,
        failureReason = null,
        idempotencyKey = "idem-1",
        version = 1L,
    )

    @Test
    fun `initiatedPayload carries eventType and sourceService for AuditConsumer attribution`() {
        val node = objectMapper.readTree(publisher.initiatedPayload(transaction()))

        assertThat(node.get("eventType").asText()).isEqualTo("TransactionInitiated")
        assertThat(node.get("sourceService").asText()).isEqualTo("transaction-service")
    }

    @Test
    fun `completedPayload carries eventType and sourceService for AuditConsumer attribution`() {
        val node = objectMapper.readTree(publisher.completedPayload(transaction()))

        assertThat(node.get("eventType").asText()).isEqualTo("TransactionCompleted")
        assertThat(node.get("sourceService").asText()).isEqualTo("transaction-service")
    }

    @Test
    fun `failedPayload carries eventType and sourceService for AuditConsumer attribution`() {
        val node = objectMapper.readTree(publisher.failedPayload(transaction(), "insufficient funds"))

        assertThat(node.get("eventType").asText()).isEqualTo("TransactionFailed")
        assertThat(node.get("sourceService").asText()).isEqualTo("transaction-service")
    }

    @Test
    fun `settledPayload carries eventType and sourceService for AuditConsumer attribution`() {
        val node = objectMapper.readTree(publisher.settledPayload(transaction(), UUID.randomUUID()))

        assertThat(node.get("eventType").asText()).isEqualTo("TransactionSettled")
        assertThat(node.get("sourceService").asText()).isEqualTo("transaction-service")
    }
}
