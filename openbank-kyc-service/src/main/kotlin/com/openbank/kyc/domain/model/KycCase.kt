// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.kyc.domain.model

import java.time.Instant
import java.util.UUID

enum class KycCaseStatus {
    OPEN,
    DOCUMENTS_REQUIRED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    EXPIRED,
    ;

    /** Terminal states: the case is closed and a fresh case may be opened for the party. */
    val isTerminal: Boolean get() = this == APPROVED || this == REJECTED || this == EXPIRED

    /** Active = not terminal: the party currently has an in-flight KYC case. */
    val isActive: Boolean get() = !isTerminal

    companion object {
        /** Terminal status names — used by the partial-active query in the persistence layer. */
        val TERMINAL_NAMES: List<String> = entries.filter { it.isTerminal }.map { it.name }
    }
}
enum class RiskLevel { LOW, MEDIUM, HIGH, VERY_HIGH }
enum class CheckType { IDENTITY, ADDRESS, PEP_SCREENING, SANCTIONS_SCREENING, ADVERSE_MEDIA }
enum class CheckStatus { PENDING, PASSED, FAILED, MANUAL_REVIEW }

data class KycCase(
    val id: UUID,
    val partyId: UUID,
    val status: KycCaseStatus,
    val riskLevel: RiskLevel,
    val assignedTo: String?,
    val checks: List<KycCheck>,
    val notes: String?,
    val reviewedBy: String?,
    val reviewedAt: Instant?,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class KycCheck(
    val id: UUID,
    val caseId: UUID,
    val checkType: CheckType,
    val status: CheckStatus,
    val result: String?,
    val provider: String?,
    val performedAt: Instant?,
    val createdAt: Instant,
)
