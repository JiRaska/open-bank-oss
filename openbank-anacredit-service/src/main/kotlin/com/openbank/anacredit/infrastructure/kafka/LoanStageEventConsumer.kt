// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.anacredit.application.port.out.AnaCreditMetricsPort
import com.openbank.anacredit.application.port.out.LoanStageEventOutcome
import com.openbank.anacredit.application.port.out.LoanStageProjectionRepository
import com.openbank.anacredit.domain.model.LoanStageProjection
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Consumes `openbank-lending-service`'s `loan.stage_changed` event and updates the durable
 * "last known IFRS 9 stage per loan" projection (ADR-0037 event-ingestion follow-up, issue #638), so
 * AnaCredit's eventual `LOAN`-instrument-type exposures are no longer blind to lending's real overdue
 * bucketing.
 *
 * Same shape as `kyc-service`/`aml-service`'s `PartyEventConsumer`: `suspend @Incoming` (Quarkus
 * dispatches on the Vert.x event loop with a duplicated context, so the reactive-Panache write inside
 * [LoanStageProjectionRepository] runs correctly).
 *
 * **Failure handling separates two things this consumer used to conflate (#5698/#5745).**
 *
 * A **malformed event** — unparseable JSON, no `loanId`, no `newStage` — is unretryable: replaying it
 * fails identically forever. It is logged, counted and acked. That is the genuine poison pill, and it
 * is the only case that may be acked on failure.
 *
 * A **failed projection write** is the opposite: the event is fine, the database is not, and the work
 * must happen once it recovers. Acking there froze the loan's IFRS 9 stage and DPD at their previous
 * values with nothing but an ERROR line to show for it — and AnaCredit exposure is a *regulatory*
 * return, so a stage that silently stops advancing is a wrong number on a filed report, not a stale
 * dashboard. `applyIfNewer` is idempotent and ordering-safe, so a retry or a redelivery is free.
 * Those failures now go through [EventRetry.withRetry] and are RETHROWN.
 *
 * **What the rethrow does, and what it deliberately does not promise.** The mechanism this handler
 * controls is the rethrow: the record is not acknowledged as done. What follows is the connector's
 * `failure-strategy` for `lending-events-in` — `dead-letter-queue` parks the record in the configured
 * topic, SmallRye's default `fail` stops the channel. That is configuration, not a property of this
 * code, so this KDoc does not state today's value: #5745 section B found the whole family of #5698
 * fixes asserting a dead-letter that only four channels fleet-wide actually had, and #5751 is wiring
 * this one to `openbank.dlq.anacredit.lending-events-in`. Read the channel's config, or the
 * `check-incoming-dlq-wiring` gate, for the current answer.
 *
 * Every consumption still reports its terminal outcome to [AnaCreditMetricsPort], `apply_error`
 * included — the counter is incremented *before* the rethrow, so the population survives whichever
 * failure-strategy the channel is configured with.
 *
 * Idempotent + ordering-safe: [LoanStageProjectionRepository.applyIfNewer] only writes when the
 * incoming event's timestamp is strictly newer than the projection's current value, so an out-of-order
 * redelivery or a duplicate can never regress the projection to an older stage.
 */
@ApplicationScoped
class LoanStageEventConsumer {

    @Inject
    lateinit var projections: LoanStageProjectionRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var metrics: AnaCreditMetricsPort

    private val log = Logger.getLogger(LoanStageEventConsumer::class.java)

    @Incoming("lending-events-in")
    @Suppress("TooGenericExceptionCaught") // a consumer must never die on a single bad/foreign event
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[lending-events-in] Failed to parse JSON payload: %.200s", payload)
            metrics.loanStageEvent(LoanStageEventOutcome.PARSE_ERROR)
            return
        }

        val eventType = node.path("eventType").asText()
        if (eventType != "loan.stage_changed") {
            metrics.loanStageEvent(LoanStageEventOutcome.IGNORED)
            return
        }

        val loanId = runCatching { UUID.fromString(node.path("loanId").asText()) }.getOrNull()
        if (loanId == null) {
            log.warnf("[lending-events-in] loan.stage_changed without a valid loanId, skipping: %.200s", payload)
            metrics.loanStageEvent(LoanStageEventOutcome.MALFORMED)
            return
        }
        val newStage = node.path("newStage").asText("").ifBlank { null }
        if (newStage == null) {
            log.warnf("[lending-events-in] loan.stage_changed without a newStage, skipping: %.200s", payload)
            metrics.loanStageEvent(LoanStageEventOutcome.MALFORMED)
            return
        }
        val daysPastDue = node.path("daysPastDue").asInt(0)
        // asOf is the business date the transition was assessed as-of; eventTimestamp additionally falls
        // back to "now" if the payload omits it, so a malformed/older schema never crashes the consumer.
        val eventTimestamp = runCatching {
            OffsetDateTime.parse(node.path("asOf").asText())
        }.getOrDefault(OffsetDateTime.now(clock))

        apply(
            LoanStageProjection(
                loanId = loanId,
                stage = newStage,
                daysPastDue = daysPastDue,
                eventTimestamp = eventTimestamp,
                updatedAt = OffsetDateTime.now(clock),
            ),
        )
    }

    /**
     * Write the projection and report the terminal outcome.
     *
     * A write failure is a dependency being down, not a bad event: it is retried a bounded number of
     * times and then RETHROWN, so the platform sees a failure instead of an ack that did nothing. The
     * `apply_error` counter is still incremented first, so the metric records every terminal failure
     * regardless of which failure-strategy the channel is configured with.
     */
    @Suppress("TooGenericExceptionCaught") // count the terminal outcome for ANY failure, then rethrow
    private suspend fun apply(projection: LoanStageProjection) {
        try {
            EventRetry.withRetry(log, "loan.stage_changed projection", projection.loanId) {
                applyOnce(projection)
            }
        } catch (e: Exception) {
            metrics.loanStageEvent(LoanStageEventOutcome.APPLY_ERROR)
            throw e
        }
    }

    private suspend fun applyOnce(projection: LoanStageProjection) {
        if (projections.applyIfNewer(projection)) {
            log.infof(
                "[lending-events-in] Applied stage projection for loan %s: %s (DPD %d)",
                projection.loanId,
                projection.stage,
                projection.daysPastDue,
            )
            metrics.loanStageEvent(LoanStageEventOutcome.APPLIED)
        } else {
            log.infof(
                "[lending-events-in] Ignored stale/duplicate loan.stage_changed for loan %s " +
                    "(existing projection is already at least as recent)",
                projection.loanId,
            )
            metrics.loanStageEvent(LoanStageEventOutcome.STALE)
        }
    }
}
