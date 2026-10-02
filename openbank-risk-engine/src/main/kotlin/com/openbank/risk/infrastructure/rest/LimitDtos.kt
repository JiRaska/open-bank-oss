// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.LimitAnalysis
import com.openbank.risk.domain.limits.LimitEvaluation
import com.openbank.risk.domain.limits.LimitStatus
import java.math.BigDecimal
import java.util.UUID

data class LimitSetDto(val id: String, val version: String, val source: String)

data class LimitEvaluationDto(
    val limitId: String,
    val metric: String,
    val metricDescription: String,
    /** `MIN` (a floor) or `MAX` (a ceiling). */
    val bound: String,
    val limit: BigDecimal,
    val earlyWarning: BigDecimal,
    /** `OK`, `EARLY_WARNING`, `BREACH` or `NOT_EVALUABLE`. */
    val status: String,
    /** The measured figure; null exactly when [status] is `NOT_EVALUABLE`. */
    val value: BigDecimal?,
    /** How [value] was obtained; null when not evaluable. */
    val basis: String?,
    /** Why the limit could not be evaluated (the gap in its input); null otherwise. */
    val reason: String?,
    val citation: String,
)

data class LimitsResponse(
    val runId: UUID,
    val asOf: String,
    val provenance: String,
    val limitSet: LimitSetDto,
    /** The curve set the IRRBB limit was priced on (latest one as of the run's date), or null. */
    val curveSetId: UUID?,
    val limits: List<LimitEvaluationDto>,
    /** Count per status, every status present (zero included), so a consumer never infers OK from absence. */
    val summary: Map<String, Int>,
    val notes: List<String>,
)

fun LimitAnalysis.toResponse() = LimitsResponse(
    runId = run.id,
    asOf = run.asOf.toString(),
    provenance = run.provenance.wire,
    limitSet = LimitSetDto(set.id, set.version, set.source),
    curveSetId = curveSetId,
    limits = evaluations.map { it.toDto() },
    summary = LimitStatus.entries.associate { s -> s.name to evaluations.count { it.status == s } },
    notes = listOf(
        "Limits are declared as code in openbank.risk.limits (ADR-0313 D9); a change to any limit bumps the set's version.",
        "NOT_EVALUABLE means an input of the limit had a gap (unclassified balance, missing fixing, curve set or " +
            "counterparty): the limit is not evaluated on a partial figure, and it is NOT an OK.",
        "The total capital ratio uses credit-risk RWA only (no market or operational risk), so it is an upper bound.",
        "An open-FX-position limit is not declared: the snapshot carries no FX derivative legs and the ledger's FX " +
            "revaluation accounts are not modelled, so a net open position would not be supported by the data.",
    ),
)

private fun LimitEvaluation.toDto(): LimitEvaluationDto {
    val evaluable = status != LimitStatus.NOT_EVALUABLE
    return LimitEvaluationDto(
        limitId = definition.id,
        metric = definition.metric.wire,
        metricDescription = definition.metric.description,
        bound = definition.metric.bound.name,
        limit = definition.limit,
        earlyWarning = definition.earlyWarning,
        status = status.name,
        value = value,
        basis = explanation.takeIf { evaluable },
        reason = explanation.takeUnless { evaluable },
        citation = definition.citation,
    )
}
