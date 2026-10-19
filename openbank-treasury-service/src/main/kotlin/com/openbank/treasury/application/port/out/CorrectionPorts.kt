// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.treasury.domain.model.CorrectionStatus
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealCorrection
import java.util.UUID

/**
 * Everything an approved correction commits, in ONE transaction under the deal-row lock (the row a
 * payment release and a fixing lock too): the base schedule must still be exactly what the preview
 * was computed from ([baseFingerprint]); a payment whose status blocks a deal change and covers one
 * of [changedFlowIds] refuses the whole change (409); the PROPOSED payments covering a changed flow
 * are withdrawn (scoped — payments of unchanged flows stay); then the new deal version, its schedule,
 * the correction row and the outbox events commit together.
 */
data class CorrectionCommit(
    val correction: DealCorrection,
    val deal: Deal,
    val schedule: ScheduleWrite?,
    val baseFingerprint: Long,
    val changedFlowIds: Set<UUID>,
    val events: List<DealEvent>,
    val command: CommandKey,
    /** The delta journals the approval commits to post, stored with the correction row. */
    val deltasJson: String,
    /** Re-measured under the lock: the others' limit-consuming exposure may not exceed [ExposureGuard.maxOthers]. */
    val exposureGuard: ExposureGuard? = null,
)

/**
 * The counterparty headroom the approval was vetted against: the limit (or the senior override's
 * bound) less the corrected deal's own amount. A deal booked meanwhile that eats into it refuses
 * the approval (422 LIMIT_BREACHED) instead of committing a breach.
 */
data class ExposureGuard(val counterpartyId: String, val currency: String, val maxOthers: java.math.BigDecimal)

/** The counterparty exposure grew past the vetted headroom between the vetting and the lock. */
class CorrectionLimitBreachedException(message: String) : RuntimeException(message)

interface CorrectionRepository {
    /** A new PENDING correction (at most one PENDING per deal; a second is a 409). */
    suspend fun propose(correction: DealCorrection, preview: String, command: CommandKey)

    suspend fun find(correctionId: UUID): DealCorrection?

    /** The stored preview JSON of [correctionId] (what the reviewer saw). */
    suspend fun preview(correctionId: UUID): String?

    /** The delta journals an APPROVED correction committed to post (JSON). */
    suspend fun deltas(correctionId: UUID): String?

    suspend fun forDeal(dealId: UUID): List<DealCorrection>

    suspend fun byStatus(status: CorrectionStatus, limit: Int): List<DealCorrection>

    /** A decision that changes no deal (a rejection); the row must still be PENDING. */
    suspend fun decide(correction: DealCorrection, command: CommandKey)

    /** See [CorrectionCommit]. */
    suspend fun approve(commit: CorrectionCommit)

    /** APPROVED → POSTED with where each delta journal landed, plus its journal references. */
    suspend fun markPosted(correction: DealCorrection, journals: List<LedgerJournalRef>)

    /** Σ row_version + row count of [scheduleVersion]'s flows: any write to them changes it. */
    suspend fun fingerprint(dealId: UUID, scheduleVersion: Int): Long
}
