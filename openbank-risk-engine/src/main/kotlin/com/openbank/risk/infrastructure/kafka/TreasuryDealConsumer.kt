// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.application.port.out.TreasuryDealEvent
import com.openbank.risk.domain.model.TreasuryDeal
import com.openbank.risk.domain.model.TreasuryInstrumentMapper
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.LocalDate
import java.util.UUID

/**
 * Consumes treasury-service's `treasury.deal.*` events (`openbank.treasury.deal.events`, contract
 * in `openbank-contracts/openbank-treasury-service/asyncapi.yaml`) into the engine's deal book, so
 * the bank's own money-market deals enter every snapshot as instruments (ADR-0314 D4, ADR-0315 D6).
 *
 * The event type travels in the `ce-type` header (the outbox's own), not in the payload, so it is
 * read from the record metadata. `booked` carries the rate; `settled`, `matured` and `reversed`
 * advance the state. The book is monotonic per deal, so redelivery and a late older event are
 * no-ops counted as `unchanged`.
 *
 * Failure classes as in [FxFixingConsumer] (#5698/#5745): a MALFORMED event (unknown type, missing
 * field) fails identically on every replay, so it is logged, counted and acked. A failed WRITE is
 * retried a bounded number of times and RETHROWN, and the channel's configured `failure-strategy`
 * (a per-service dead-letter topic, `application.yaml`) decides what follows.
 *
 * A well-formed event for a product this engine does not model (e.g. `FX_SPOT`, #10896 — its
 * principal posts to GL-level FX position accounts that stay GL-level, never a contract-level
 * position here, see [TreasuryInstrumentMapper.SUPPORTED_PRODUCTS]) is its own outcome, distinct
 * from malformed: the event was well-formed, the deal is simply out of scope. It is never written
 * to the book and never thrown — an unsupported product must not crash instrument building for
 * every subsequent snapshot.
 */
@ApplicationScoped
class TreasuryDealConsumer {

    @Inject
    lateinit var book: TreasuryDealBook

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var registry: MeterRegistry

    private val log = Logger.getLogger(TreasuryDealConsumer::class.java)

    @Incoming("treasury-deal-in")
    @Suppress("TooGenericExceptionCaught") // count the terminal outcome for ANY write failure, then rethrow
    suspend fun consume(payload: String, metadata: IncomingKafkaRecordMetadata<String, String>) {
        val type = metadata.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)?.value()?.toString(Charsets.UTF_8)
        val event = parse(type, payload)
        if (event == null) {
            count(OUTCOME_MALFORMED)
            return
        }
        if (event.product !in TreasuryInstrumentMapper.SUPPORTED_PRODUCTS) {
            log.infof(
                "[treasury-deal-in] product '%s' is not modelled by this engine, not stored: %.200s",
                event.product,
                payload,
            )
            count(OUTCOME_UNSUPPORTED_PRODUCT)
            return
        }
        val changed = try {
            EventRetry.withRetry(log, "treasury deal upsert", event.dealId) { book.apply(event) }
        } catch (e: Exception) {
            count(OUTCOME_WRITE_ERROR)
            throw e
        }
        count(if (changed) OUTCOME_STORED else OUTCOME_UNCHANGED)
    }

    /** The event as the book needs it, or null (malformed: logged, counted and acked). */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // any parse defect is the same malformed outcome
    internal fun parse(type: String?, payload: String): TreasuryDealEvent? {
        val state = STATE_BY_TYPE[type] ?: return malformed("ce-type '$type'", payload)
        return try {
            val node = objectMapper.readTree(payload)
            TreasuryDealEvent(
                state = state,
                dealId = UUID.fromString(node.text("dealId") ?: return malformed("dealId", payload)),
                product = node.text("product") ?: return malformed("product", payload),
                counterpartyId = node.text("counterpartyId") ?: return malformed("counterpartyId", payload),
                currency = node.text("currency") ?: return malformed("currency", payload),
                principal = node.get("principal")?.takeIf { it.isNumber }?.decimalValue()
                    ?: return malformed("principal", payload),
                rate = node.get("rate")?.takeIf { it.isNumber }?.decimalValue(),
                valueDate = node.text("valueDate")?.let(LocalDate::parse),
                maturityDate = node.text("maturityDate")?.let(LocalDate::parse),
            )
        } catch (e: Exception) {
            log.errorf(e, "[treasury-deal-in] malformed deal event, acked: %.200s", payload)
            null
        }
    }

    private fun malformed(field: String, payload: String): TreasuryDealEvent? {
        log.warnf("[treasury-deal-in] deal event without a valid %s, acked: %.200s", field, payload)
        return null
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.ifBlank { null }

    private fun count(outcome: String) {
        registry.counter(METRIC, "outcome", outcome).increment()
    }

    internal companion object {
        const val METRIC = "openbank_risk_treasury_deal_events"
        const val OUTCOME_STORED = "stored"
        const val OUTCOME_UNCHANGED = "unchanged"
        const val OUTCOME_MALFORMED = "malformed"
        const val OUTCOME_WRITE_ERROR = "write_error"
        const val OUTCOME_UNSUPPORTED_PRODUCT = "unsupported_product"

        /** The four types in treasury's asyncapi; anything else is malformed, never guessed. */
        val STATE_BY_TYPE: Map<String, String> = mapOf(
            "treasury.deal.booked.v1" to TreasuryDeal.BOOKED,
            "treasury.deal.settled.v1" to TreasuryDeal.SETTLED,
            "treasury.deal.matured.v1" to TreasuryDeal.MATURED,
            "treasury.deal.reversed.v1" to TreasuryDeal.REVERSED,
        )
    }
}
