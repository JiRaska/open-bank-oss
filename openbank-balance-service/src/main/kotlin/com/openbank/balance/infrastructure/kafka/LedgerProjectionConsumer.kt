// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.balance.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.balance.application.port.`in`.AccountBookedChange
import com.openbank.balance.application.port.`in`.LedgerProjectionUseCase
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
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
    private val log = Logger.getLogger(LedgerProjectionConsumer::class.java)

    @Incoming("ledger-events-in")
    suspend fun consume(payload: String) {
        if (!projectionEnabled) return

        val node: JsonNode = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Unparseable ledger event, skipping: %s", payload.take(200))
            return
        }

        if (node["eventType"]?.asText() != EVENT_TYPE) return

        val change = try {
            toChange(node)
        } catch (e: Exception) {
            log.errorf(e, "Malformed AccountBookedChanged, skipping: %s", payload.take(200))
            return
        }

        projection.apply(change)
    }

    private fun toChange(node: JsonNode): AccountBookedChange = AccountBookedChange(
        accountId = UUID.fromString(node["aggregateId"].asText()),
        currency = node["currency"].asText(),
        delta = BigDecimal(node["delta"].asText()),
        journalEntryId = UUID.fromString(node["journalEntryId"].asText()),
        transactionId = UUID.fromString(node["transactionId"].asText()),
        entryDate = LocalDate.parse(node["entryDate"].asText()),
        version = node["version"]?.asLong() ?: 0L,
    )

    companion object {
        private const val EVENT_TYPE = "AccountBookedChanged"
    }
}
