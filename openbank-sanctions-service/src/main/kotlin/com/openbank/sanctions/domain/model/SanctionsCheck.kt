// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sanctions.domain.model

import java.time.Instant; import java.util.UUID

enum class SanctionsListType { OFAC_SDN, EU_CONSOLIDATED, UN_CONSOLIDATED, HM_TREASURY, FATF_HIGH_RISK, PEP_GLOBAL, CNB_DOMESTIC }
enum class MatchType { EXACT, FUZZY, PHONETIC, ALIAS }
enum class SanctionsCheckStatus { CLEAR, HIT, POTENTIAL_HIT, WHITELISTED, ESCALATED }
enum class EntityType { INDIVIDUAL, ORGANIZATION, VESSEL, AIRCRAFT }

data class SanctionsMatch(
    val listType: SanctionsListType, val matchType: MatchType,
    val matchScore: Double,          // 0.0 - 1.0
    val matchedName: String, val matchedId: String?,
    val listEntryDate: String?, val programs: List<String>
)

data class SanctionsCheck(
    val id: UUID, val idempotencyKey: String,
    val entityType: EntityType,
    val name: String, val aliases: List<String>,
    val dateOfBirth: String?, val nationality: String?,
    val identifiers: Map<String, String>,  // passport, taxId, etc.
    val status: SanctionsCheckStatus,
    val matches: List<SanctionsMatch>,
    val overallScore: Double,
    val checkedLists: List<SanctionsListType>,
    val reviewedBy: String?,
    val reviewNote: String?,
    val checkedAt: Instant, val reviewedAt: Instant?
) {
    fun isHighRisk() = status == SanctionsCheckStatus.HIT ||
        (status == SanctionsCheckStatus.POTENTIAL_HIT && overallScore > 0.85)
}
