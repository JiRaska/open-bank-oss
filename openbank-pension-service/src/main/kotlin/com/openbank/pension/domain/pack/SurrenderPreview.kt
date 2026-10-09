// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.pack

import com.openbank.pension.domain.model.Limits
import com.openbank.pension.domain.model.PensionContract
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * What the participant brings to an early-exit preview. The unit register lives in
 * pension-fund-service (ADR-0334 §1) and is not built yet, so the current value and the incentive
 * history are supplied by the caller and echoed back as such — the preview is arithmetic over the
 * pinned pack, not a valuation.
 */
data class SurrenderInputs(
    val currentValue: BigDecimal,
    /** Incentive amounts received so far, per incentive id, per calendar year. */
    val incentivesReceived: Map<String, Map<Int, BigDecimal>>,
)

data class Clawback(val incentiveId: String, val mode: ClawbackMode, val amount: BigDecimal)

data class SurrenderPreview(
    val payoutConditionsMet: Boolean,
    val ageAtExit: Int,
    val durationMonths: Long,
    val earlyWithdrawalAllowed: Boolean,
    val currentValue: BigDecimal,
    val fee: BigDecimal,
    val clawbacks: List<Clawback>,
    val estimatedNetPayout: BigDecimal,
    val notes: List<String>,
)

object SurrenderCalculator {

    fun preview(
        contract: PensionContract,
        pack: JurisdictionPack,
        inputs: SurrenderInputs,
        exitDate: LocalDate,
    ): SurrenderPreview {
        Limits.requireAmount(inputs.currentValue, "currentValue")
        require(inputs.incentivesReceived.size <= Limits.MAX_ENTRIES) { "too many incentive entries" }
        inputs.incentivesReceived.forEach { (id, years) ->
            require(years.size <= Limits.MAX_ENTRIES * 2) { "too many years for incentive $id" }
            years.values.forEach { Limits.requireAmount(it, "incentivesReceived[$id]") }
        }
        val age = Period.between(contract.participantBirthDate, exitDate).years
        val start = contract.startDate ?: exitDate
        val months = ChronoUnit.MONTHS.between(start, exitDate)
        val conditions = pack.payout
        val met = age >= conditions.minAge && months >= conditions.minDurationMonths
        val notes = mutableListOf<String>()
        if (met) {
            notes += "payout conditions are met; no early-exit clawback applies"
        } else {
            notes += "early exit: minimum age ${conditions.minAge} and duration ${conditions.minDurationMonths} " +
                "months not both reached"
        }
        if (pack.legalReview.status == LegalReviewStatus.REQUIRES_LEGAL_REVIEW) {
            notes += "pack ${pack.jurisdiction}/${pack.productLine} v${pack.version} is pending legal review"
        }
        val clawbacks = if (met) emptyList() else clawbacks(pack, inputs, exitDate.year)
        val fee = if (met) {
            BigDecimal.ZERO
        } else {
            PackEvaluator.money(inputs.currentValue.multiply(conditions.earlyExitFeeRate ?: BigDecimal.ZERO))
        }
        val totalClawback = clawbacks.fold(BigDecimal.ZERO) { acc, c -> acc + c.amount }
        val net = (inputs.currentValue - fee - totalClawback).max(BigDecimal.ZERO)
        return SurrenderPreview(
            payoutConditionsMet = met,
            ageAtExit = age,
            durationMonths = months,
            earlyWithdrawalAllowed = conditions.earlyWithdrawalAllowed,
            currentValue = PackEvaluator.money(inputs.currentValue),
            fee = fee,
            clawbacks = clawbacks,
            estimatedNetPayout = PackEvaluator.money(net),
            notes = notes,
        )
    }

    private fun clawbacks(pack: JurisdictionPack, inputs: SurrenderInputs, exitYear: Int): List<Clawback> =
        pack.incentives.mapNotNull { rule ->
            val claw = rule.clawback ?: return@mapNotNull null
            val received = inputs.incentivesReceived[rule.id].orEmpty()
            val relevant = when (claw.mode) {
                ClawbackMode.RETURN_ALL -> received.values
                ClawbackMode.RECAPTURE_YEARS -> received.filterKeys { it > exitYear - claw.recaptureYears!! }.values
            }
            Clawback(rule.id, claw.mode, PackEvaluator.money(relevant.fold(BigDecimal.ZERO, BigDecimal::add)))
        }
}
