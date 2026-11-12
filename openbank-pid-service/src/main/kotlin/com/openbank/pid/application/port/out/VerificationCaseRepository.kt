// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.out

import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import java.time.Instant
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

    /**
     * GDPR Art. 17 (#15755): anonymise the applicant snapshot, blind index, dedup key and notes of
     * every not-yet-erased case the party was LINKED to (`first_link_party_id` / `final_link_party_id`)
     * — those applicant attributes are the erased person's own. Cases where the party was merely a
     * candidate describe someone else and are untouched. Idempotent.
     *
     * @return the number of cases anonymised by this call.
     */
    suspend fun anonymizeLinkedTo(partyId: UUID, erasedAt: Instant): Int
}
