// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.event

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.domain.model.AccountStatus
import com.openbank.account.domain.model.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Serialization round-trip for account-service's Kafka events (issue #3994/#5256, fleet
 * follow-up to #5255's domestic-payment fix).
 *
 * `sourceService` is the strongest (EVENT-sourced) attribution `AuditConsumer` reads
 * (`node.textOrNull("sourceService")`) — before this field these four event types carried no
 * such key and every audit row for account-service fell back to the `TopicAttribution` table
 * (correct but TOPIC-sourced, not the producer's own claim) or, for a topic the table did not
 * cover, the `"unknown"` sentinel.
 *
 * Unlike #5255's `DomesticPaymentEvents`, these classes already carried a load-bearing
 * `eventType` via [DomainEvent][com.openbank.libs.domain.event.DomainEvent] — "AccountCreated"
 * etc. is read verbatim by balance-service, document-service, statement-service and
 * campaign-service, so it is NOT renamed to the audit fleet's SCREAMING_SNAKE_CASE convention
 * here. Only `sourceService` — a field nothing else on the fleet reads — is new.
 */
class AccountEventsTest {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private val now = Instant.parse("2026-08-16T10:00:00Z")

    @Test
    fun `AccountCreatedEvent carries eventType and sourceService for AuditConsumer attribution`() {
        val event = AccountCreatedEvent(
            aggregateId = UUID.randomUUID(),
            version = 0L,
            accountNumber = "CZ6508000000192000145399",
            accountType = AccountType.CURRENT,
            partyId = UUID.randomUUID(),
            productId = UUID.randomUUID(),
            currency = "CZK",
            occurredAt = now,
        )

        assertThat(event.eventType).isEqualTo("AccountCreated")
        assertThat(event.sourceService).isEqualTo("account-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("AccountCreated")
        assertThat(node.get("sourceService").asText()).isEqualTo("account-service")
    }

    @Test
    fun `AccountStatusChangedEvent carries eventType and sourceService for AuditConsumer attribution`() {
        val event = AccountStatusChangedEvent(
            aggregateId = UUID.randomUUID(),
            version = 1L,
            previousStatus = AccountStatus.PENDING_ACTIVATION,
            newStatus = AccountStatus.ACTIVE,
            reason = "KYC + AML cleared (ADR-0267)",
            occurredAt = now,
        )

        assertThat(event.sourceService).isEqualTo("account-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("AccountStatusChanged")
        assertThat(node.get("sourceService").asText()).isEqualTo("account-service")
    }

    @Test
    fun `AccountClosedEvent carries eventType and sourceService for AuditConsumer attribution`() {
        val event = AccountClosedEvent(
            aggregateId = UUID.randomUUID(),
            version = 2L,
            reason = "customer request",
            occurredAt = now,
        )

        assertThat(event.sourceService).isEqualTo("account-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("AccountClosed")
        assertThat(node.get("sourceService").asText()).isEqualTo("account-service")
    }

    @Test
    fun `SavingsWithdrawalApproved carries eventType and sourceService for AuditConsumer attribution`() {
        val event = SavingsWithdrawalApproved(
            aggregateId = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            delegatePartyId = UUID.randomUUID(),
            amountMinor = 4_000_00,
            currency = "CZK",
            approvalId = "approval-1",
            scaSessionId = UUID.randomUUID(),
            occurredAt = now,
        )

        assertThat(event.sourceService).isEqualTo("account-service")

        val node = objectMapper.readTree(objectMapper.writeValueAsString(event))
        assertThat(node.get("eventType").asText()).isEqualTo("SavingsWithdrawalApproved")
        assertThat(node.get("sourceService").asText()).isEqualTo("account-service")
    }
}
