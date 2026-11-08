// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The fee approved with a NAV; a correction carries only its signed change from the replaced NAV. */
data class ManagementFeeAccrual(
    val navId: UUID,
    val fundId: UUID,
    val valuationDate: LocalDate,
    val currency: String,
    val amount: BigDecimal,
    val correctsNavId: UUID?,
    val approvedBy: String,
) {
    val idempotencyKey: String get() = "pension-management-fee:$navId"

    companion object {
        fun from(nav: NavRecord, currency: String, original: NavRecord? = null): ManagementFeeAccrual {
            require(nav.status == NavStatus.PUBLISHED) { "management fee requires a published NAV" }
            val approver = nav.approvedBy
            require(!approver.isNullOrBlank() && approver != nav.calculatedBy) {
                "management fee requires an independent NAV approver"
            }
            require(Regex("[A-Z]{3}").matches(currency)) { "management fee currency must be an ISO code" }
            require((original != null) == nav.isCorrection) {
                "a NAV correction requires its original; an ordinary NAV must not supply one"
            }
            if (original != null) {
                require(
                    original.id == nav.correctsNavId &&
                        original.fundId == nav.fundId &&
                        original.valuationDate == nav.valuationDate,
                ) {
                    "the original NAV must match the correction's reference, fund and valuation date"
                }
            }
            val amount = nav.figures.accruedManagementFee - (original?.figures?.accruedManagementFee ?: BigDecimal.ZERO)
            return ManagementFeeAccrual(
                navId = nav.id,
                fundId = nav.fundId,
                valuationDate = nav.valuationDate,
                currency = currency,
                amount = amount,
                correctsNavId = nav.correctsNavId,
                approvedBy = approver,
            )
        }
    }
}
