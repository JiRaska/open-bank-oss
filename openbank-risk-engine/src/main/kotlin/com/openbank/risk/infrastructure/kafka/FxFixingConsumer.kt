// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.risk.application.port.out.FxFixingRate
import com.openbank.risk.application.port.out.FxFixingRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Consumes fx-service's `fx.fixing.published.v1` (`openbank.fx.fixing.published`, contract in
 * `openbank-contracts/openbank-fx-service/asyncapi.yaml`) into `fx_fixing_rate` — the reference
 * data a snapshot converts currencies with (ADR-0314 D1/D5).
 *
 * **Two failure classes, handled differently (#5698/#5745).** A MALFORMED event — unparseable,
 * or missing a required field — fails identically on every replay, so it is logged, counted as
 * `malformed` and acked; parking it would only move the same poison elsewhere. A failed WRITE is
 * the database, not the event: it is retried a bounded number of times and then RETHROWN, so the
 * record is not acknowledged as done. What happens next is the channel's configured
 * `failure-strategy` (see `application.yaml`), not a property of this code.
 *
 * Idempotent: the insert is keyed by (source, fixingDate, currency), so a redelivery is a no-op
 * counted as `duplicate`.
 */
@ApplicationScoped
class FxFixingConsumer {

    @Inject
    lateinit var repository: FxFixingRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var registry: MeterRegistry

    private val log = Logger.getLogger(FxFixingConsumer::class.java)

    @Incoming("fx-fixing-in")
    @Suppress("TooGenericExceptionCaught") // count the terminal outcome for ANY write failure, then rethrow
    suspend fun consume(payload: String) {
        val rates = parse(payload)
        if (rates == null) {
            count(OUTCOME_MALFORMED)
            return
        }
        val inserted = try {
            EventRetry.withRetry(log, "fx fixing insert", rates.first().fixingDate) {
                repository.insertIfAbsent(rates)
            }
        } catch (e: Exception) {
            count(OUTCOME_WRITE_ERROR)
            throw e
        }
        count(if (inserted == 0) OUTCOME_DUPLICATE else OUTCOME_STORED)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // any parse defect is the same malformed outcome
    private fun parse(payload: String): List<FxFixingRate>? {
        return try {
            val node = objectMapper.readTree(payload)
            val source = node.text("source") ?: return malformed("source", payload)
            val fixingDate = node.text("fixingDate")?.let(LocalDate::parse) ?: return malformed("fixingDate", payload)
            val quote = node.text("quoteCurrency") ?: return malformed("quoteCurrency", payload)
            val validFrom = node.text("validFrom")?.let(Instant::parse) ?: return malformed("validFrom", payload)
            val validTo = node.text("validTo")?.let(Instant::parse) ?: return malformed("validTo", payload)
            val rates = node.path("rates")
            if (!rates.isArray || rates.isEmpty) return malformed("rates", payload)
            val receivedAt = clock.instant()
            rates.map { r ->
                FxFixingRate(
                    source = source,
                    fixingDate = fixingDate,
                    currency = requireNotNull(r.text("currency")) { "rate without currency" },
                    quoteCurrency = quote,
                    ratePerUnit = requireNotNull(
                        r.get("ratePerUnit")?.takeIf {
                            it.isNumber
                        },
                    ) { "rate without ratePerUnit" }.decimalValue(),
                    rateId = UUID.fromString(requireNotNull(r.text("rateId")) { "rate without rateId" }),
                    validFrom = validFrom,
                    validTo = validTo,
                    receivedAt = receivedAt,
                )
            }
        } catch (e: Exception) {
            log.errorf(e, "[fx-fixing-in] malformed fixing event, acked: %.200s", payload)
            null
        }
    }

    private fun malformed(field: String, payload: String): List<FxFixingRate>? {
        log.warnf("[fx-fixing-in] fixing event without a valid '%s', acked: %.200s", field, payload)
        return null
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.ifBlank { null }

    private fun count(outcome: String) {
        registry.counter(METRIC, "outcome", outcome).increment()
    }

    private companion object {
        const val METRIC = "openbank_risk_fx_fixing_events"
        const val OUTCOME_STORED = "stored"
        const val OUTCOME_DUPLICATE = "duplicate"
        const val OUTCOME_MALFORMED = "malformed"
        const val OUTCOME_WRITE_ERROR = "write_error"
    }
}
