// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.risk.application.port.out.CnbPolicyRateFactRepository
import com.openbank.risk.application.port.out.CnbPolicyRateFactRow
import com.openbank.risk.application.port.out.FactUpsert
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Consumes fx-service's `fx.cnb-policy-rate.published.v1` (`openbank.fx.cnb-policy-rate.published`,
 * a COMPACTED topic keyed by (instrument, effectiveFrom); contract in
 * `openbank-contracts/openbank-fx-service/asyncapi.yaml`) into `cnb_policy_rate_fact`, where the
 * minimum-reserve requirement reads the ratio and remuneration in effect on its as-of date.
 *
 * Same two failure classes as [FxFixingConsumer]: a MALFORMED event (unparseable, a missing field,
 * a rate outside [0, 1]) is logged, counted `malformed` and acked — it fails identically on every
 * replay. A failed WRITE is retried a bounded number of times, counted `write_error` and RETHROWN;
 * the channel's configured `failure-strategy` (dead-letter-queue) decides what follows.
 *
 * Idempotent on (instrument, effectiveFrom): a redelivery is `duplicate`, a different rate for a
 * stored key is fx-service's revision and is applied as `revised`.
 */
@ApplicationScoped
class CnbPolicyRateConsumer {

    @Inject
    lateinit var repository: CnbPolicyRateFactRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var registry: MeterRegistry

    private val log = Logger.getLogger(CnbPolicyRateConsumer::class.java)

    @Incoming("cnb-policy-rate-in")
    @Suppress("TooGenericExceptionCaught") // count the terminal outcome for ANY write failure, then rethrow
    suspend fun consume(payload: String) {
        val fact = parse(payload)
        if (fact == null) {
            count(OUTCOME_MALFORMED)
            return
        }
        val result = try {
            EventRetry.withRetry(log, "cnb policy-rate fact upsert", "${fact.instrument}:${fact.effectiveFrom}") {
                repository.upsert(fact)
            }
        } catch (e: Exception) {
            count(OUTCOME_WRITE_ERROR)
            throw e
        }
        if (result == FactUpsert.REVISED) {
            log.warnf(
                "[cnb-policy-rate-in] %s effective %s revised to %s",
                fact.instrument,
                fact.effectiveFrom,
                fact.rate,
            )
        }
        count(
            when (result) {
                FactUpsert.INSERTED -> OUTCOME_STORED
                FactUpsert.REVISED -> OUTCOME_REVISED
                FactUpsert.DUPLICATE -> OUTCOME_DUPLICATE
            },
        )
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // any parse defect is the same malformed outcome
    private fun parse(payload: String): CnbPolicyRateFactRow? {
        return try {
            val node = objectMapper.readTree(payload)
            val instrument = node.text("instrument") ?: return malformed("instrument", payload)
            val effectiveFrom = node.text("effectiveFrom")?.let(LocalDate::parse)
                ?: return malformed("effectiveFrom", payload)
            val rate = node.get("rate")?.takeIf { it.isNumber }?.decimalValue()
                ?.takeIf { it.signum() >= 0 && it <= BigDecimal.ONE }
                ?: return malformed("rate", payload)
            val sourceUrl = node.text("sourceUrl") ?: return malformed("sourceUrl", payload)
            val fetchedAt = node.text("fetchedAt")?.let(Instant::parse) ?: return malformed("fetchedAt", payload)
            val sha =
                node.text("contentSha256")?.takeIf { SHA256.matches(it) } ?: return malformed("contentSha256", payload)
            CnbPolicyRateFactRow(
                instrument = instrument,
                effectiveFrom = effectiveFrom,
                rate = rate,
                sourceUrl = sourceUrl,
                fetchedAt = fetchedAt,
                contentSha256 = sha,
                note = node.text("note"),
                revised = node.get("revised")?.asBoolean() ?: false,
                receivedAt = clock.instant(),
            )
        } catch (e: Exception) {
            log.errorf(e, "[cnb-policy-rate-in] malformed policy-rate event, acked: %.200s", payload)
            null
        }
    }

    private fun malformed(field: String, payload: String): CnbPolicyRateFactRow? {
        log.warnf("[cnb-policy-rate-in] policy-rate event without a valid '%s', acked: %.200s", field, payload)
        return null
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.ifBlank { null }

    private fun count(outcome: String) {
        registry.counter(METRIC, "outcome", outcome).increment()
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
        const val METRIC = "openbank_risk_cnb_policy_rate_events"
        const val OUTCOME_STORED = "stored"
        const val OUTCOME_REVISED = "revised"
        const val OUTCOME_DUPLICATE = "duplicate"
        const val OUTCOME_MALFORMED = "malformed"
        const val OUTCOME_WRITE_ERROR = "write_error"
    }
}
