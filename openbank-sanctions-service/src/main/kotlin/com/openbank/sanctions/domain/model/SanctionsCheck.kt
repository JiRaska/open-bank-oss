// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.domain.model

import com.openbank.libs.fincrime.ScreeningPolicy
import java.time.Instant
import java.util.UUID

enum class SanctionsListType {
    OFAC_SDN,
    EU_CONSOLIDATED,
    UN_CONSOLIDATED,
    HM_TREASURY,
    FATF_HIGH_RISK,
    PEP_GLOBAL,
    CNB_DOMESTIC,
}
enum class MatchType { EXACT, FUZZY, PHONETIC, ALIAS }

/**
 * sanctions-service's name for the shared check status (ADR-0353 D4), declared once in
 * `openbank-libs-fincrime` so every rail reads the same value set it publishes.
 */
typealias SanctionsCheckStatus = com.openbank.libs.fincrime.ScreeningMatchStatus

enum class EntityType { INDIVIDUAL, ORGANIZATION, VESSEL, AIRCRAFT }

data class SanctionsMatch(
    val listType: SanctionsListType,
    val matchType: MatchType,
    val matchScore: Double, // 0.0 - 1.0
    val matchedName: String,
    val matchedId: String?,
    val listEntryDate: String?,
    val programs: List<String>,
)

data class SanctionsCheck(
    val id: UUID,
    val idempotencyKey: String,
    val entityType: EntityType,
    val name: String,
    val aliases: List<String>,
    val dateOfBirth: String?,
    val nationality: String?,
    val identifiers: Map<String, String>, // passport, taxId, etc.
    val status: SanctionsCheckStatus,
    val matches: List<SanctionsMatch>,
    val overallScore: Double,
    val checkedLists: List<SanctionsListType>,
    val reviewedBy: String?,
    val reviewNote: String?,
    val checkedAt: Instant,
    val reviewedAt: Instant?,
    /** Stable caller-owned identity (for account opening, the party UUID), never parsed from the key. */
    val subjectId: String? = null,
    /** A WHITELISTED review may cover later fresh screens only until this instant. */
    val clearanceExpiresAt: Instant? = null,
) {
    fun isHighRisk() = status == SanctionsCheckStatus.HIT ||
        (status == SanctionsCheckStatus.POTENTIAL_HIT && overallScore > ScreeningPolicy.POTENTIAL_HIT_BLOCK_THRESHOLD)
}
