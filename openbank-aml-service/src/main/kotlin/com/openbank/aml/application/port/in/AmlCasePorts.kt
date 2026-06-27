// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.aml.application.port.`in`

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import java.util.UUID

data class CreateAmlCaseCommand(
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID?,
    val transactionId: UUID?,
    val customerReference: String,
    val screeningType: ScreeningType,
    val riskLevel: AmlRiskLevel,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
)

data class ListAmlCasesQuery(
    val status: AmlCaseStatus? = null,
    val partyId: UUID? = null,
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
)

interface AmlCaseUseCase {
    suspend fun createCase(command: CreateAmlCaseCommand): AmlCase
    suspend fun getCase(caseId: UUID): AmlCase
    suspend fun listCases(query: ListAmlCasesQuery): List<AmlCase>
    suspend fun updateDecision(command: UpdateAmlDecisionCommand): AmlCase
}
