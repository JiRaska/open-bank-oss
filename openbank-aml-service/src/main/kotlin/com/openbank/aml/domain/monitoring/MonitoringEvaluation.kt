// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.monitoring

import java.time.Instant
import java.util.UUID

/**
 * What an evaluation of one party established. There is deliberately no "clear": [NO_FINDING] says
 * only that the pack's rules did not match the counts it was given, and [INCOMPLETE] says the pack
 * could not be evaluated at all. Neither is a statement about the party.
 */
enum class MonitoringEvaluationOutcome { NO_FINDING, FINDING, INCOMPLETE }

/** What became of a hit. SHADOW records it; a case is opened only when the deployment says so. */
enum class MonitoringHitDisposition { SHADOW_RECORDED, CASE_OPENED }

data class PackIdentity(val id: String, val revision: Int, val contentHash: String)

/** One feature's count as it was handed to the pack: the evidence behind a hit. */
data class CounterObservation(
    val featureId: String,
    val currency: String,
    val band: String,
    val direction: MovementDirection?,
    val rail: String?,
    val windowFrom: Instant,
    val windowTo: Instant,
    val count: Long,
)

data class MonitoringHit(
    val partyId: UUID,
    /** The pack rule id: the scenario code. */
    val scenarioCode: String,
    val reasonCode: String,
    val severityCode: String,
    val windowStart: Instant,
    val observation: CounterObservation,
)

data class MonitoringEvaluationRecord(
    val partyId: UUID,
    val pack: PackIdentity,
    val evaluatedAt: Instant,
    val outcome: MonitoringEvaluationOutcome,
    /** Why the evaluation is [MonitoringEvaluationOutcome.INCOMPLETE]; `null` otherwise. */
    val failure: String?,
    val inputHash: String?,
    val hits: List<MonitoringHit>,
)
