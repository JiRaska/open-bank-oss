// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.`in`

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.CaseOrigin
import com.openbank.aml.domain.model.HoldDisposition
import com.openbank.aml.domain.model.ScreeningType
import java.util.UUID

data class CreateAmlCaseCommand(
    val idempotencyKey: String,
    /** No default on purpose: every entry point states what it is, and none can inherit another's. */
    val origin: CaseOrigin,
    /** `null` when the opener does not know the party yet; the resolution sweep fills it in. */
    val partyId: UUID?,
    val accountId: UUID?,
    val transactionId: UUID?,
    val customerReference: String,
    val screeningType: ScreeningType,
    val riskLevel: AmlRiskLevel,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
    /**
     * Fingerprint of the HTTP request this command came from (#10916), persisted with the case so
     * a replay by [idempotencyKey] can tell a retry from a key reused for a different case after
     * the Redis record is gone. `null` for callers with no HTTP request (onboarding screening).
     */
    val requestHash: String? = null,
)

data class ListAmlCasesQuery(
    val status: AmlCaseStatus? = null,
    val partyId: UUID? = null,
    /** `true`/`false` narrows to cases whose owning party is / is not established; `null` does not filter. */
    val partyResolved: Boolean? = null,
    val screeningType: ScreeningType? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

data class UpdateAmlDecisionCommand(
    val caseId: UUID,
    val targetStatus: AmlCaseStatus,
    val decisionReason: String?,
    val assignedAnalyst: String?,
    val decidedBy: String,
    /**
     * The approved four-eyes proposal this decision executes (ADR-0371 §5). A decision that closes
     * or escalates a transaction-monitoring case is refused without one, so no single caller — an
     * analyst, the sandbox auto-clear, a future job — can decide such a case alone.
     */
    val fourEyesProposalId: UUID? = null,
)

/** One analyst's proposal, or a different analyst's confirmation, of a held payment's case decision. */
data class HoldDecisionCommand(
    val caseId: UUID,
    val outcome: HoldDisposition,
    val reason: String,
    /** The authenticated caller, never a field of the request. */
    val analyst: String,
)

interface AmlCaseUseCase {
    suspend fun createCase(command: CreateAmlCaseCommand): AmlCase
    suspend fun getCase(caseId: UUID): AmlCase
    suspend fun listCases(query: ListAmlCasesQuery): List<AmlCase>
    suspend fun updateDecision(command: UpdateAmlDecisionCommand): AmlCase
}

interface HoldDecisionUseCase {
    /** ADR-0374 point 3: proposes a held payment's decision. Changes no status and emits no event. */
    suspend fun proposeHoldDecision(command: HoldDecisionCommand): AmlCase

    /** Confirms the pending proposal as a different analyst; the only way such a case is decided. */
    suspend fun confirmHoldDecision(command: HoldDecisionCommand): AmlCase
}
