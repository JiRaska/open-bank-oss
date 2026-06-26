// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.application.port.out

import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import java.util.UUID

/** Outbound persistence port for four-eyes identity-verification cases (ADR-0072 §1). */
interface VerificationCaseRepository {
    suspend fun findById(id: UUID): VerificationCase?

    /** The single ACTIVE (status != DECIDED) case for a dedup key, if any (uq_ivc_active_dedup). */
    suspend fun findActiveByDedupKey(dedupKey: String): VerificationCase?

    /** The most recently DECIDED case for a dedup key — the adjudication cache consulted by /resolve. */
    suspend fun findLatestDecidedByDedupKey(dedupKey: String): VerificationCase?

    /** Cockpit queue, newest-first. */
    suspend fun listByStatuses(statuses: List<VerificationCaseStatus>): List<VerificationCase>

    suspend fun save(case: VerificationCase): VerificationCase

    suspend fun update(case: VerificationCase): VerificationCase
}
