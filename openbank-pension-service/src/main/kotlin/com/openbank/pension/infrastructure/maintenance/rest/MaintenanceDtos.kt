// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.maintenance.rest

import com.openbank.pension.application.maintenance.ScheduleView
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationHistory
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationVersion
import com.openbank.pension.domain.maintenance.PlannedBeneficiaryChange
import com.openbank.pension.domain.maintenance.PlannedScheduleChange
import com.openbank.pension.domain.maintenance.ScheduleChangeRequest
import com.openbank.pension.domain.maintenance.ScheduleVersion
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.infrastructure.rest.dto.BeneficiaryDto
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Nullable request fields, checked with `requireNotNull` (400 via libs-runtime), as in S1. */
data class ScheduleChangeDto(
    val amount: BigDecimal? = null,
    val frequency: ContributionFrequency? = null,
    val dayOfMonth: Int? = null,
    val startDate: LocalDate? = null,
    val acknowledgeIncentiveReduction: Boolean? = null,
    /** Only on the change, not the preview: the SCA challenge signed over the preview's documentSha256. */
    val scaChallengeId: String? = null,
) {
    fun toDomain() = ScheduleChangeRequest(
        amount = requireNotNull(amount) { "amount is required" },
        frequency = requireNotNull(frequency) { "frequency is required" },
        dayOfMonth = requireNotNull(dayOfMonth) { "dayOfMonth is required" },
        startDate = startDate,
        acknowledgeIncentiveReduction = acknowledgeIncentiveReduction ?: false,
    )
}

/**
 * The full ordered designation. GDPR minimal identity: name, optional partyId, share — the DTO has
 * no other field, and the service's ObjectMapper drops anything else a client sends.
 */
data class BeneficiaryChangeDto(val beneficiaries: List<BeneficiaryDto?>? = null, val scaChallengeId: String? = null) {
    fun designations() = requireNotNull(beneficiaries) { "beneficiaries is required (an empty list removes all)" }
        .mapIndexed { i, b -> requireNotNull(b) { "beneficiaries[$i] must not be null" }.toDomain(i) }
}

internal fun requireChallenge(value: String?): String {
    val id = requireNotNull(value) { "scaChallengeId is required" }
    require(id.isNotBlank() && id.length <= MAX_CHALLENGE_LENGTH) {
        "scaChallengeId must be 1..$MAX_CHALLENGE_LENGTH characters"
    }
    return id
}

private const val MAX_CHALLENGE_LENGTH = 128

data class SchedulePreviewResponse(
    val amount: BigDecimal,
    val employerAmount: BigDecimal,
    val currency: String,
    val frequency: ContributionFrequency,
    val dayOfMonth: Int,
    val effectiveFrom: LocalDate,
    val annualIncentiveBefore: BigDecimal,
    val annualIncentiveAfter: BigDecimal,
    val incentiveWarnings: List<String>,
    /** What the participant signs under SCA; the change must carry a challenge bound to it. */
    val documentSha256: String,
) {
    companion object {
        fun from(p: PlannedScheduleChange) = SchedulePreviewResponse(
            p.amount, p.employerAmount, p.currency, p.frequency, p.dayOfMonth, p.effectiveFrom,
            p.impact.annualBefore, p.impact.annualAfter, p.impact.warnings, p.documentSha256,
        )
    }
}

data class ScheduleVersionResponse(
    val seq: Int,
    val amount: BigDecimal,
    val employerAmount: BigDecimal,
    val currency: String,
    val frequency: ContributionFrequency,
    val dayOfMonth: Int,
    val effectiveFrom: LocalDate,
    val status: String,
    val changedAt: Instant,
) {
    companion object {
        fun from(v: ScheduleVersion) = ScheduleVersionResponse(
            v.seq, v.amount, v.employerAmount, v.currency, v.frequency, v.dayOfMonth, v.effectiveFrom,
            v.status.name, v.changedAt,
        )
    }
}

data class ScheduleViewResponse(
    /** The schedule agreed when the contract was signed; applies until the first change takes effect. */
    val original: OriginalSchedule,
    val inForce: ScheduleVersionResponse?,
    val pending: ScheduleVersionResponse?,
    val history: List<ScheduleVersionResponse>,
) {
    data class OriginalSchedule(
        val amount: BigDecimal,
        val employerAmount: BigDecimal,
        val currency: String,
        val frequency: ContributionFrequency,
    )

    companion object {
        fun from(v: ScheduleView) = ScheduleViewResponse(
            v.contract.schedule.let { OriginalSchedule(it.amount, it.employerAmount, it.currency, it.frequency) },
            v.inForce?.let(ScheduleVersionResponse::from),
            v.pending?.let(ScheduleVersionResponse::from),
            v.history.versions.map(ScheduleVersionResponse::from),
        )
    }
}

data class BeneficiaryPreviewResponse(val beneficiaries: List<BeneficiaryDto>, val documentSha256: String) {
    companion object {
        fun from(p: PlannedBeneficiaryChange) =
            BeneficiaryPreviewResponse(p.beneficiaries.map(BeneficiaryDto::from), p.documentSha256)
    }
}

data class BeneficiaryVersionResponse(val seq: Int, val beneficiaries: List<BeneficiaryDto>, val changedAt: Instant) {
    companion object {
        fun from(v: BeneficiaryDesignationVersion) =
            BeneficiaryVersionResponse(v.seq, v.beneficiaries.map(BeneficiaryDto::from), v.changedAt)
    }
}

data class BeneficiaryViewResponse(val current: List<BeneficiaryDto>, val history: List<BeneficiaryVersionResponse>) {
    companion object {
        fun from(contract: PensionContract, history: BeneficiaryDesignationHistory) = BeneficiaryViewResponse(
            contract.beneficiaries.map(BeneficiaryDto::from),
            history.versions.map(BeneficiaryVersionResponse::from),
        )
    }
}
