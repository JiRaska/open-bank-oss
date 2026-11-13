// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import com.openbank.libs.domain.error.Unavailable
import java.time.Instant
import java.util.UUID

/**
 * A subject a caller asked this service to keep screening after onboarding (ADR-0376).
 *
 * It holds what the CUSTOMER screening profile reads and nothing else: no document identifiers,
 * no contact data. [subjectRef] is the same value the owning service sends as `subjectId` on a
 * live screen, which is what lets an analyst's clearance of that subject be honoured here.
 */
data class MonitoredSubject(
    val id: UUID,
    val ownerService: String,
    val subjectRef: String,
    val entityType: EntityType,
    val name: String,
    val aliases: List<String>,
    val dateOfBirth: String?,
    val nationality: String?,
    val country: String?,
    val registeredAt: Instant,
    val updatedAt: Instant,
    /** Null until the first full evaluation; set back to null when the attributes change. */
    val lastSweptAt: Instant?,
)

/** How a finding was detected. A consumer may treat a baseline differently from a later change. */
enum class MonitoringDetection {
    /** A list entry was added or changed and matched the subject. */
    LIST_DELTA,

    /** The subject's first full evaluation after registration or an attribute change. */
    BASELINE_SWEEP,

    /** A later full evaluation: the safety net for a delta that was missed. */
    PERIODIC_SWEEP,
}

/**
 * One match between a monitored subject and one version of a list entry. Carries the subject's
 * reference, never its name: the owning service resolves the reference to a person.
 */
data class MonitoringFinding(
    val id: UUID,
    val subjectId: UUID,
    val ownerService: String,
    val subjectRef: String,
    val entityType: EntityType,
    val matchKind: MatchKind,
    val listType: SanctionsListType,
    /** `id:<external id>`, `name:<listed name>` for an entry without one, or the jurisdiction row id. */
    val listEntryRef: String,
    val listEntryVersion: String,
    val matchScore: Double,
    val matchStatus: SanctionsCheckStatus,
    val detection: MonitoringDetection,
    val detectedAt: Instant,
)

/**
 * What one scheduled run did. [DISABLED] and [RESWEEP_REQUESTED] are their own values so that a
 * run which matched nothing because it was switched off can never be counted as a completed one.
 */
enum class MonitoringRunOutcome { COMPLETED, RESWEEP_REQUESTED, DISABLED, FAILED }

/** Registration was refused because ongoing monitoring is switched off: 503, nothing stored. */
class SanctionsMonitoringDisabled :
    Unavailable(clientMessage = "Ongoing sanctions monitoring is not enabled; the subject was not registered")
