// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.model

import java.time.Instant
import java.util.UUID

enum class AmlCaseStatus {
    OPEN,
    UNDER_REVIEW,
    CLEARED,
    BLOCKED,
    ESCALATED,
}

enum class ScreeningType {
    CUSTOMER_ONBOARDING,
    TRANSACTION_MONITORING,
    PERIODIC_REVIEW,
    MANUAL_INVESTIGATION,
}

enum class AmlRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

data class AmlCase(
    val id: UUID,
    val idempotencyKey: String,
    val partyId: UUID,
    val accountId: UUID?,
    val transactionId: UUID?,
    val customerReference: String,
    val screeningType: ScreeningType,
    val riskLevel: AmlRiskLevel,
    val status: AmlCaseStatus,
    val alertCode: String,
    val alertDetail: String?,
    val matchedEntity: String?,
    val decisionReason: String?,
    val assignedAnalyst: String?,
    val decidedBy: String?,
    val screenedAt: Instant,
    val decidedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun transitionTo(
        targetStatus: AmlCaseStatus,
        decisionReason: String?,
        assignedAnalyst: String?,
        decidedBy: String,
        // No default. It used to be `Instant.EPOCH`, and the one caller that took the default was
        // production (`AmlCaseService.updateDecision`), so every terminal AML decision — an
        // analyst's and the sandbox auto-clear alike — recorded `decidedAt`/`updatedAt` as
        // 1970-01-01, straight into the DB columns and onto `aml.case.status_changed.v1`. Both
        // unit-test callers already passed a real value, so nothing observed it (#5837).
        now: Instant,
    ): AmlCase {
        require(canTransitionTo(targetStatus)) { "Invalid AML case status transition: $status -> $targetStatus" }
        require(decidedBy.isNotBlank()) { "decidedBy is required" }
        require(targetStatus != AmlCaseStatus.BLOCKED || !decisionReason.isNullOrBlank()) {
            "decisionReason is required when blocking an AML case"
        }

        return copy(
            status = targetStatus,
            decisionReason = decisionReason?.trim()?.ifBlank { null },
            assignedAnalyst = assignedAnalyst?.trim()?.ifBlank { null },
            decidedBy = decidedBy.trim(),
            decidedAt = now,
            updatedAt = now,
        )
    }

    fun canTransitionTo(targetStatus: AmlCaseStatus): Boolean = when (status) {
        AmlCaseStatus.OPEN -> targetStatus in setOf(
            AmlCaseStatus.UNDER_REVIEW,
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
            AmlCaseStatus.ESCALATED,
        )

        AmlCaseStatus.UNDER_REVIEW -> targetStatus in setOf(
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
            AmlCaseStatus.ESCALATED,
        )

        AmlCaseStatus.ESCALATED -> targetStatus in setOf(
            AmlCaseStatus.UNDER_REVIEW,
            AmlCaseStatus.CLEARED,
            AmlCaseStatus.BLOCKED,
        )

        AmlCaseStatus.CLEARED,
        AmlCaseStatus.BLOCKED,
        -> false
    }
}
