// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.monitoring

import com.openbank.aml.application.port.out.MovementCountQuery
import com.openbank.aml.application.port.out.MovementCounterReader
import com.openbank.aml.domain.monitoring.CounterObservation
import com.openbank.aml.domain.monitoring.CounterScheme
import com.openbank.aml.domain.monitoring.MonitoringEvaluationOutcome
import com.openbank.aml.domain.monitoring.MonitoringEvaluationRecord
import com.openbank.aml.domain.monitoring.MonitoringHit
import com.openbank.libs.fincrime.pack.CountEvaluation
import com.openbank.libs.fincrime.pack.CountObservation
import java.time.Instant
import java.util.UUID

/**
 * Evaluates the monitoring pack for one party over its counters (ADR-0371 §3, §4).
 *
 * Reads through [MovementCounterReader] only and hands exact counts to the ADR-0354 interpreter.
 * No scenario logic lives here: the interpreter decides what matched, and whatever it could not
 * evaluate comes back as [MonitoringEvaluationOutcome.INCOMPLETE] with the interpreter's own
 * failure code — never as "nothing found".
 */
class MonitoringEvaluator(
    private val pack: MonitoringPack,
    private val scheme: CounterScheme,
    private val reader: MovementCounterReader,
) {
    suspend fun evaluate(partyId: UUID, at: Instant): MonitoringEvaluationRecord {
        val observations = pack.features.values.map { feature ->
            // Buckets whose start lies in the window ending now; the current bucket is included.
            val from = scheme.bucketStart(at.minusSeconds(feature.windowSeconds))
            val to = at.plusMillis(1)
            val count = reader.countMovements(
                MovementCountQuery(
                    partyId = partyId,
                    currency = feature.currency,
                    bandCode = feature.band,
                    schemeRevision = feature.schemeRevision,
                    fromInclusive = from,
                    toExclusive = to,
                    direction = feature.direction,
                    rail = feature.rail,
                ),
            )
            CounterObservation(feature.id, feature.currency, feature.band, feature.direction, feature.rail, from, at, count)
        }
        val byFeature = observations.associateBy { it.featureId }
        val result = pack.compiled.evaluate(
            pack.compiled.reference,
            at,
            observations.map {
                CountObservation(MonitoringPack.referenceOf(pack.features.getValue(it.featureId)), it.count, at, at)
            },
        )
        return when (result) {
            is CountEvaluation.Incomplete -> MonitoringEvaluationRecord(
                partyId = partyId,
                pack = pack.identity,
                evaluatedAt = at,
                outcome = MonitoringEvaluationOutcome.INCOMPLETE,
                failure = listOfNotNull(result.failure.name, result.featureId).joinToString(":"),
                inputHash = null,
                hits = emptyList(),
            )
            is CountEvaluation.Evaluated -> {
                val hits = result.findings.map { finding ->
                    val rule = pack.rules.first { it.id == finding.ruleId }
                    val feature = pack.features.getValue(rule.feature.id)
                    MonitoringHit(
                        partyId = partyId,
                        scenarioCode = finding.ruleId,
                        reasonCode = rule.reasonCode,
                        severityCode = rule.severityCode,
                        windowStart = feature.windowStart(at),
                        observation = byFeature.getValue(feature.id),
                    )
                }
                MonitoringEvaluationRecord(
                    partyId = partyId,
                    pack = pack.identity,
                    evaluatedAt = at,
                    outcome = if (hits.isEmpty()) MonitoringEvaluationOutcome.NO_FINDING else MonitoringEvaluationOutcome.FINDING,
                    failure = null,
                    inputHash = result.inputHash,
                    hits = hits,
                )
            }
        }
    }
}
