// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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

/**
 * [REGISTRY_MATCH], [REPRESENTATIVE_AUTHORITY] and [UBO_IDENTIFICATION] are the KYB checks of a
 * BUSINESS subject (ADR-0284 D5): does the entity exist as declared in its public register, are
 * the people who signed listed as able to bind it, and who ultimately owns it (AMLD5 Art. 30).
 */
enum class CheckType {
    IDENTITY,
    ADDRESS,
    PEP_SCREENING,
    SANCTIONS_SCREENING,
    ADVERSE_MEDIA,
    REGISTRY_MATCH,
    REPRESENTATIVE_AUTHORITY,
    UBO_IDENTIFICATION,
}

/** Whether the case is about a natural person or a legal entity; decides the check set (ADR-0284 D5). */
enum class SubjectType {
    INDIVIDUAL,
    BUSINESS,
    ;

    val mandatoryChecks: List<CheckType>
        get() = when (this) {
            INDIVIDUAL -> listOf(
                CheckType.IDENTITY,
                CheckType.ADDRESS,
                CheckType.PEP_SCREENING,
                CheckType.SANCTIONS_SCREENING,
            )
            BUSINESS -> listOf(
                CheckType.REGISTRY_MATCH,
                CheckType.REPRESENTATIVE_AUTHORITY,
                CheckType.UBO_IDENTIFICATION,
                CheckType.SANCTIONS_SCREENING,
                CheckType.ADVERSE_MEDIA,
            )
        }

    companion object {
        /** party-service's `partyType` (INDIVIDUAL | SOLE_TRADER | COMPANY | TRUST) → subject type. */
        fun fromPartyType(partyType: String?): SubjectType = when (partyType?.uppercase()) {
            "SOLE_TRADER", "COMPANY", "TRUST" -> BUSINESS
            else -> INDIVIDUAL
        }
    }
}
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
    val subjectType: SubjectType = SubjectType.INDIVIDUAL,
) {
    /**
     * True when an automated customer screen settled this case clear: the sanctions check was
     * PASSED by the screening provider (not by default, and not while still pending), and the PEP
     * check, where the subject has one, is PASSED too.
     *
     * Deliberately not named `is…`/`get…`: Jackson would publish it as a property of the case on
     * the API, in events and in stored payloads.
     */
    fun hasClearScreen(): Boolean {
        val sanctions = checks.firstOrNull { it.checkType == CheckType.SANCTIONS_SCREENING } ?: return false
        val pep = checks.firstOrNull { it.checkType == CheckType.PEP_SCREENING }
        return sanctions.status == CheckStatus.PASSED &&
            sanctions.provider == CUSTOMER_SCREENING_PROVIDER &&
            (pep == null || pep.status == CheckStatus.PASSED)
    }

    companion object {
        const val CUSTOMER_SCREENING_PROVIDER = "openbank-sanctions-service:CUSTOMER"
    }
}

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
