// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.statecontribution

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Why state money goes back to the agency (ZDPS §18(2)/(3)). */
enum class ReturnCause {
    /** Contract ended without transfer: the unused contribution goes back (§18(3), R2). */
    CONTRACT_TERMINATED,

    /** Received for a month the participant was not entitled to, found out later (§18(2), R1). */
    INELIGIBILITY_DISCOVERED,
}

enum class ReturnStatus {
    /** Known and owed; not yet on a report. */
    DUE,

    /** Listed on a filed return report; waiting for the agency's result. */
    REPORTED,

    /** The agency confirmed the amount; the money still has to be paid back. */
    CONFIRMED,

    /** Paid back. Terminal. */
    SETTLED,
}

/**
 * One return obligation (vratka) towards the agency (ADR-0334, #12382). It moves through
 * `DUE → REPORTED → CONFIRMED → SETTLED`. [dueBy] is the statutory deadline for its [cause], from
 * [CzStateContributionCalendar]. [claimId] is set when the return reverses one specific claim
 * (ineligibility). It is null for an exit clawback, which the S5 ledger settles as a total.
 * [sourceKey] is unique: a replayed trigger registers nothing twice.
 */
data class StateContributionReturn(
    val id: UUID,
    val contractId: UUID,
    val claimId: UUID?,
    val cause: ReturnCause,
    val amount: BigDecimal,
    val currency: String,
    val discoveredOn: LocalDate,
    val dueBy: LocalDate,
    val sourceKey: String,
    val status: ReturnStatus,
    val reportId: UUID? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(amount.signum() > 0) { "a return is for a positive amount" }
        require(sourceKey.isNotBlank()) { "a return carries the key of what triggered it" }
        require(status == ReturnStatus.DUE || reportId != null) { "a reported return belongs to a report" }
    }

    fun report(report: UUID, at: Instant): StateContributionReturn {
        check(status == ReturnStatus.DUE) { "return $id is $status, only DUE can be reported" }
        return copy(status = ReturnStatus.REPORTED, reportId = report, updatedAt = at)
    }

    fun confirm(at: Instant): StateContributionReturn {
        check(status == ReturnStatus.REPORTED) { "return $id is $status, only REPORTED can be confirmed" }
        return copy(status = ReturnStatus.CONFIRMED, updatedAt = at)
    }

    /** The agency refused the line (for example, a data error). It goes back to DUE for the next report. */
    fun reopen(at: Instant): StateContributionReturn {
        check(status == ReturnStatus.REPORTED) { "return $id is $status, only REPORTED can be reopened" }
        return copy(status = ReturnStatus.DUE, reportId = null, updatedAt = at)
    }

    fun settle(at: Instant): StateContributionReturn {
        check(status == ReturnStatus.CONFIRMED) { "return $id is $status, only CONFIRMED can be settled" }
        return copy(status = ReturnStatus.SETTLED, updatedAt = at)
    }

    fun overdue(today: LocalDate): Boolean = status != ReturnStatus.SETTLED && today.isAfter(dueBy)
}

/**
 * Agency reason codes for a claim line paid in part or not at all. **PLACEHOLDER**: no official MF
 * code list is public (research T3). The codes mirror the eligibility and data conditions of ZDPS
 * §§13–16. An unknown code is kept verbatim as [OTHER], never dropped.
 */
enum class CzClaimReasonCode(val description: String) {
    OLD_AGE_PENSIONER("participant has been granted an old-age pension (§13(1))"),
    NO_RESIDENCE_OR_INSURANCE("no CZ residence nor EU/EEA residence with CZ insurance (§13(1))"),
    BELOW_MINIMUM("participant contribution below 500 CZK for the month (§14(1))"),
    AMOUNT_RECOMPUTED("agency recomputed the amount (§14(2)-(4))"),
    IDENTITY_MISMATCH("participant identity does not match the population register (§13(2))"),
    DUPLICATE("month already claimed for this participant"),
    DATA_ERROR("application line incomplete or incorrect; correct and re-file (§16(4))"),
    OTHER("code not in the placeholder list"),
    ;

    companion object {
        fun parse(code: String): CzClaimReasonCode = entries.firstOrNull { it.name == code.trim() } ?: OTHER
    }
}

/** One line of the agency's answer to a return report (ZDPS §18(6)). */
sealed interface ReturnResultLine {
    val returnId: UUID

    data class Confirmed(override val returnId: UUID) : ReturnResultLine

    data class Refused(override val returnId: UUID, val reason: CzClaimReasonCode) : ReturnResultLine
}

/** What a return report line carries, before rendering into a wire format. */
data class ReturnReportLine(
    val returnId: UUID,
    val contractReference: String,
    val cause: ReturnCause,
    val amount: BigDecimal,
    val claimMonth: java.time.YearMonth?,
)
