// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.origination

/**
 * Pure mapping of the pre-ADR-0211 `ApplicationStatus` rows (`PROPOSED / APPROVED /
 * REJECTED / DISBURSED`, lending-service v0.11.5) onto the canonical origination
 * graph — the ADR-0211 D7 cutover table as a pure, testable function. The service
 * runs it inside a Flyway data migration; `null` means "no lawful mapping — migrate
 * manually", so a corrupt legacy row fails the migration loudly instead of guessing.
 */
object LegacyOriginationMigration {

    /**
     * @param legacyStatus the pre-ADR-0211 status string as persisted
     * @param wasSubmitted whether the legacy row carries a submission timestamp
     *        (distinguishes PROPOSED → DRAFT from PROPOSED → SUBMITTED per D7)
     */
    fun mapLegacyStatus(legacyStatus: String, wasSubmitted: Boolean): OriginationState? = when (legacyStatus) {
        "PROPOSED" -> if (wasSubmitted) OriginationState.SUBMITTED else OriginationState.DRAFT
        "APPROVED" -> OriginationState.OFFERED
        "REJECTED" -> OriginationState.DECLINED
        "DISBURSED" -> OriginationState.DISBURSED
        else -> null
    }

    val MAPPABLE_LEGACY_STATUSES: Set<String> = setOf("PROPOSED", "APPROVED", "REJECTED", "DISBURSED")
}
