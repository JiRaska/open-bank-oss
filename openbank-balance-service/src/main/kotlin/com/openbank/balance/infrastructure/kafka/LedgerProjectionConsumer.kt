// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.`in`.LedgerProjectionUseCase
import com.openbank.libs.domain.money.Money
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0039 Phase D: consumes the ledger event stream and projects `AccountBookedChanged` events onto
 * the balance read-model.
 *
 * The ledger publishes every journal event (JournalPosted / JournalReversed / AccountBookedChanged)
 * onto a SINGLE topic, so this consumer filters by JSON `eventType` and ignores the rest.
 *
 * **Flag-gated, default OFF** ([projectionEnabled]). While the payment saga still debits balance
 * directly (pre Phase D-2), applying these deltas too would double-count the booked movement; so this
 * PR ships inert. Phase D-2 removes the saga debit and flips this flag ON in one coordinated cutover.
 *
 * Delivery is at-least-once; idempotency lives in [LedgerProjectionUseCase] (dedup on
 * journalEntry+account+currency), so a redelivery is safe.
 */
@ApplicationScoped
class LedgerProjectionConsumer(
    private val projection: LedgerProjectionUseCase,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(name = "openbank.balance.projection.enabled", defaultValue = "false")
    private val projectionEnabled: Boolean,
) {
    @Incoming("ledger-events-in")
    suspend fun consume(payload: String) {
        if (!projectionEnabled) return

        val node: JsonNode = objectMapper.readTree(payload)

        if (node["eventType"]?.asText() != EVENT_TYPE) return

        val change = toChange(node)

        projection.apply(change)
    }

    // The delta is built as kernel Money (#11604) BEFORE anything is persisted: a delta with more
    // decimals than its currency allows, or a currency that is not ISO 4217 with a minor unit, throws
    // InvalidMoneyException here. The method rethrows, so the configured failure-strategy
    // (dead-letter-queue -> openbank.dlq.balance.ledger-events-in) parks the record and the channel
    // moves on; no dedup marker, pocket, balance change or outbox row is written for it.
    // Before: NUMERIC(19,4) silently rounded a 5th decimal, a sub-minor-unit delta was booked as is,
    // and an unknown currency created a phantom pocket.
    private fun toChange(node: JsonNode): AccountBookedChange = AccountBookedChange(
        accountId = UUID.fromString(node["aggregateId"].asText()),
        delta = Money.parseInbound(
            Money.parseAmount(node["delta"].asText()),
            node["currency"]?.asText(),
            amountField = "delta",
        ),
        journalEntryId = UUID.fromString(node["journalEntryId"].asText()),
        transactionId = UUID.fromString(node["transactionId"].asText()),
        entryDate = LocalDate.parse(node["entryDate"].asText()),
        version = node["version"]?.asLong() ?: 0L,
    )

    companion object {
        private const val EVENT_TYPE = "AccountBookedChanged"
    }
}
