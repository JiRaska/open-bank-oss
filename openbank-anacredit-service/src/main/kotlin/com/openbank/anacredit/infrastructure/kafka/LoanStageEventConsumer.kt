// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.anacredit.application.port.out.AnaCreditMetricsPort
import com.openbank.anacredit.application.port.out.LoanStageEventOutcome
import com.openbank.anacredit.application.port.out.LoanStageProjectionRepository
import com.openbank.anacredit.domain.model.LoanStageProjection
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
 * [LoanStageProjectionRepository] runs correctly), poison-pill safe (a parse/domain failure is caught,
 * logged, and the message is acked — one malformed event must not wedge the consumer group; the
 * canonical lending stream can be replayed).
 *
 * Every consumption reports its terminal outcome to [AnaCreditMetricsPort]. That is the point of the
 * meter rather than a nicety: acking a bad event is the correct behaviour, so a broken producer, a
 * schema change or a wedged projection write leaves the stage projection frozen with nothing but an
 * INFO/ERROR line to show for it. `outcome=parse_error|malformed|apply_error` is that population.
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
     * Write the projection and report the terminal outcome. A write failure is logged, counted and
     * swallowed — the message is acked so one bad row cannot wedge the consumer group, and the
     * canonical lending stream can be replayed.
     */
    @Suppress("TooGenericExceptionCaught") // any write failure means the same thing: ack and count it
    private suspend fun apply(projection: LoanStageProjection) {
        try {
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
        } catch (e: Exception) {
            log.errorf(e, "[lending-events-in] Failed to apply stage projection for loan %s", projection.loanId)
            metrics.loanStageEvent(LoanStageEventOutcome.APPLY_ERROR)
        }
    }
}
