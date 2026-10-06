// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.limits

import com.openbank.risk.domain.model.SnapshotRun
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Wire payloads of openbank.risk.limit.events (ADR-0313 D9). The event type travels in the Kafka
 * `ce-type` header (the outbox relay moves the row's eventType there), so the body carries no
 * discriminator. `openbank-contracts/openbank-risk-engine/asyncapi.yaml` names each EVENT_TYPE and
 * lists these constructor properties; check-event-contract-code-agreement.py compares the two. Add an
 * event here AND there.
 */

/** A limit figure crossed its early-warning threshold but not the limit itself. */
data class RiskLimitEarlyWarning(
    val runId: UUID,
    val asOf: LocalDate,
    val provenance: String,
    val limitSetId: String,
    val limitSetVersion: String,
    val limitId: String,
    val metric: String,
    val bound: String,
    val limit: BigDecimal,
    val earlyWarning: BigDecimal,
    val value: BigDecimal,
    val explanation: String,
    val citation: String,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "risk.limit.early-warning.v1"
    }
}

/** A limit figure is on the wrong side of the limit. */
data class RiskLimitBreach(
    val runId: UUID,
    val asOf: LocalDate,
    val provenance: String,
    val limitSetId: String,
    val limitSetVersion: String,
    val limitId: String,
    val metric: String,
    val bound: String,
    val limit: BigDecimal,
    val earlyWarning: BigDecimal,
    val value: BigDecimal,
    val explanation: String,
    val citation: String,
    val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) {
    companion object {
        const val EVENT_TYPE = "risk.limit.breach.v1"
    }
}

const val SOURCE_SERVICE = "risk-engine"

/** One limit event ready for the outbox: its type, the limit it is about, and the payload object. */
data class RiskLimitEvent(val eventType: String, val limitId: String, val payload: Any)

object RiskLimitEvents {

    /**
     * The events [evaluations] give rise to: one per EARLY_WARNING or BREACH. OK and NOT_EVALUABLE
     * emit nothing — a NOT_EVALUABLE limit is visible on the limits read, and an event claiming a
     * breach nobody measured would be worse than none.
     */
    fun of(
        run: SnapshotRun,
        set: LimitSet,
        evaluations: List<LimitEvaluation>,
        occurredAt: Instant,
    ): List<RiskLimitEvent> = evaluations.mapNotNull { e ->
        val value = e.value ?: return@mapNotNull null
        val d = e.definition
        when (e.status) {
            LimitStatus.BREACH -> RiskLimitEvent(
                RiskLimitBreach.EVENT_TYPE,
                d.id,
                RiskLimitBreach(
                    run.id, run.asOf, run.provenance.wire, set.id, set.version, d.id, d.metric.wire,
                    d.metric.bound.name, d.limit, d.earlyWarning, value, e.explanation, d.citation, occurredAt,
                ),
            )
            LimitStatus.EARLY_WARNING -> RiskLimitEvent(
                RiskLimitEarlyWarning.EVENT_TYPE,
                d.id,
                RiskLimitEarlyWarning(
                    run.id, run.asOf, run.provenance.wire, set.id, set.version, d.id, d.metric.wire,
                    d.metric.bound.name, d.limit, d.earlyWarning, value, e.explanation, d.citation, occurredAt,
                ),
            )
            LimitStatus.OK, LimitStatus.NOT_EVALUABLE -> null
        }
    }
}
