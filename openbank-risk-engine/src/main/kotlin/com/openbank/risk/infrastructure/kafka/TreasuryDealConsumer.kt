// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.kafka

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.finance.schedule.ScheduleGenerator
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.risk.application.port.out.TreasuryCashFlowStore
import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.application.port.out.TreasuryDealEvent
import com.openbank.risk.domain.model.TreasuryDeal
import com.openbank.risk.domain.model.TreasuryInstrumentMapper
import com.openbank.risk.domain.valuation.DealKind
import com.openbank.risk.domain.valuation.StoredFlowSet
import com.openbank.risk.infrastructure.finance.CashFlowDto
import com.openbank.risk.infrastructure.finance.CashFlowJson
import com.openbank.risk.infrastructure.finance.LegBounds
import com.openbank.risk.infrastructure.finance.LegDefaults
import com.openbank.risk.infrastructure.finance.LegDto
import com.openbank.risk.infrastructure.finance.LegSpecJson
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
 * A KNOWN lifecycle event that changes nothing the engine models — `treasury.deal.confirmed.v1`
 * (ADR-0315 D2: the counterparty confirmed the booked terms; rate, dates and on-book status are
 * unchanged) — is acked and counted `ignored`, never `malformed`: counting a legitimate event as a
 * defect would report a producer bug on every confirmation.
 *
 * A well-formed event for a product this engine does not model (e.g. `FX_SPOT`, #10896 — its
 * principal posts to GL-level FX position accounts that stay GL-level, never a contract-level
 * position here, see [TreasuryInstrumentMapper.SUPPORTED_PRODUCTS]) is its own outcome, distinct
 * from malformed: the event was well-formed, the deal is simply out of scope. It is never written
 * to the book and never thrown — an unsupported product must not crash instrument building for
 * every subsequent snapshot.
 *
 * ADR-0344 D8: a BOOKED deal whose published legs cannot be built is a real position, so it is
 * STORED as unvaluable (counted `stored_unvaluable`) and surfaces as a data gap in every valuation
 * read — never acked-and-dropped like a malformed event.
 */
@ApplicationScoped
class TreasuryDealConsumer {

    @Inject
    lateinit var book: TreasuryDealBook

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var cashFlows: TreasuryCashFlowStore

    @Inject
    lateinit var registry: MeterRegistry

    private val log = Logger.getLogger(TreasuryDealConsumer::class.java)

    @Incoming("treasury-deal-in")
    @Suppress("TooGenericExceptionCaught") // count the terminal outcome for ANY write failure, then rethrow
    suspend fun consume(payload: String, metadata: IncomingKafkaRecordMetadata<String, String>) {
        val type = metadata.headers?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)?.value()?.toString(Charsets.UTF_8)
        if (type in IGNORED_TYPES) {
            count(OUTCOME_IGNORED)
            return
        }
        if (type == CASHFLOW_GENERATED) {
            consumeCashFlows(payload)
            return
        }
        val event = parse(type, payload)
        if (event == null) {
            count(OUTCOME_MALFORMED)
            return
        }
        // A product outside the snapshot's instrument set is still VALUED when it publishes legs
        // (ADR-0344 D8); without legs there is nothing this engine can do with it.
        if (event.product !in TreasuryInstrumentMapper.SUPPORTED_PRODUCTS && event.legsJson == null) {
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
        if (event.legsError != null) {
            log.warnf("[treasury-deal-in] deal %s stored as UNVALUABLE: %s", event.dealId, event.legsError)
            count(OUTCOME_STORED_UNVALUABLE)
        }
        count(if (changed) OUTCOME_STORED else OUTCOME_UNCHANGED)
    }

    /**
     * `treasury.cashflow.generated.v1` (ADR-0344 D4): the stored ledger of one deal version. Kept at
     * the latest version per deal; malformed → acked and counted, a write failure → retried, rethrown.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun consumeCashFlows(payload: String) {
        val set = parseCashFlows(payload)
        if (set == null) {
            count(OUTCOME_MALFORMED)
            return
        }
        val changed = try {
            EventRetry.withRetry(log, "treasury cash-flow upsert", set.dealId) { cashFlows.apply(set) }
        } catch (e: Exception) {
            count(OUTCOME_WRITE_ERROR)
            throw e
        }
        count(if (changed) OUTCOME_STORED else OUTCOME_UNCHANGED)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    internal fun parseCashFlows(payload: String): StoredFlowSet? = try {
        val node = objectMapper.readTree(payload)
        val flows = node.get("flows")?.takeIf { it.isArray } ?: return malformedFlows("flows", payload)
        StoredFlowSet(
            dealId = UUID.fromString(node.text("dealId") ?: return malformedFlows("dealId", payload)),
            dealVersion = node.get("dealVersion")?.takeIf { it.canConvertToInt() }?.intValue()
                ?: return malformedFlows("dealVersion", payload),
            scheduleHash = node.text("scheduleHash") ?: return malformedFlows("scheduleHash", payload),
            flows = objectMapper.convertValue(flows, object : TypeReference<List<CashFlowDto>>() {})
                .mapIndexed(CashFlowJson::toDomain),
        )
    } catch (e: Exception) {
        log.errorf(e, "[treasury-deal-in] malformed cash-flow event, acked: %.200s", payload)
        null
    }

    private fun malformedFlows(field: String, payload: String): StoredFlowSet? {
        log.warnf("[treasury-deal-in] cash-flow event without a valid %s, acked: %.200s", field, payload)
        return null
    }

    /** Published legs as stored JSON, plus why they cannot be built (null when they can). */
    internal data class PublishedLegs(val json: String, val error: String?)

    /**
     * The legs as canonical JSON, validated with libs-finance's types under [LegBounds.BOOKED] (no
     * pricing-edge bound) and generated once so the per-leg period cap applies. Legs that cannot be
     * built do NOT make the event malformed: the deal is a real position, so it is stored with the
     * raw legs and the reason, and every valuation read reports it as a data gap — never dropped.
     */
    @Suppress("TooGenericExceptionCaught") // every leg defect is the same "unvaluable" outcome
    private fun publishedLegs(node: JsonNode, header: LegDefaults): PublishedLegs? {
        val legs = node.get("legs")?.takeIf { it.isArray && it.size() > 0 } ?: return null
        return try {
            val dtos = objectMapper.convertValue(legs, object : TypeReference<List<LegDto>>() {})
            LegSpecJson.toLegs(dtos, header, LegBounds.BOOKED).forEach { ScheduleGenerator.generate(it) }
            val resolved = dtos.map {
                it.copy(
                    paymentCalendars = it.paymentCalendars ?: header.calendars,
                    businessDayConvention = it.businessDayConvention ?: header.businessDayConvention,
                )
            }
            PublishedLegs(objectMapper.writeValueAsString(resolved), null)
        } catch (e: Exception) {
            PublishedLegs(legs.toString(), "legs cannot be built: ${e.message}")
        }
    }

    /** The event as the book needs it, or null (malformed: logged, counted and acked). */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // any parse defect is the same malformed outcome
    internal fun parse(type: String?, payload: String): TreasuryDealEvent? {
        val state = STATE_BY_TYPE[type] ?: return malformed("ce-type '$type'", payload)
        return try {
            val node = objectMapper.readTree(payload)
            val published = publishedLegs(
                node,
                LegDefaults(
                    calendars = node.get("calendars")?.takeIf { it.isArray }?.map { it.asText() },
                    businessDayConvention = node.text("businessDayConvention"),
                ),
            )
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
                kind = DealKind.parse(node.text("kind")),
                bookId = node.text("bookId"),
                legsJson = published?.json,
                scheduleHash = node.text("scheduleHash"),
                legsError = published?.error,
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
        const val OUTCOME_IGNORED = "ignored"

        /** A deal whose published legs cannot be built: stored, and a data gap in every valuation read. */
        const val OUTCOME_STORED_UNVALUABLE = "stored_unvaluable"

        /**
         * ADR-0344 D4: treasury's stored ledger of one deal version. Same topic as the deal events
         * (one producer, one ordering per deal), told apart by `ce-type` like every other type.
         */
        const val CASHFLOW_GENERATED = "treasury.cashflow.generated.v1"

        /** Known treasury types that move nothing in the book (see the class KDoc). */
        val IGNORED_TYPES: Set<String> = setOf(
            "treasury.deal.confirmed.v1",
            // ADR-0315 D7: a nostro reconciliation break passed its alert threshold. Same topic,
            // not a deal: nothing the book models, so acked as ignored rather than malformed.
            "treasury.nostro.break-aged.v1",
        )

        /** The four state-moving types in treasury's asyncapi; anything else not ignored is malformed. */
        val STATE_BY_TYPE: Map<String, String> = mapOf(
            "treasury.deal.booked.v1" to TreasuryDeal.BOOKED,
            "treasury.deal.settled.v1" to TreasuryDeal.SETTLED,
            "treasury.deal.matured.v1" to TreasuryDeal.MATURED,
            "treasury.deal.reversed.v1" to TreasuryDeal.REVERSED,
        )
    }
}
