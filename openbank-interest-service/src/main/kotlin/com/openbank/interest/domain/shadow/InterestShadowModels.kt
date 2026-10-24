// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.shadow

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Which committed legacy row a shadow input points at (ADR-0352 phase 3). */
enum class ShadowSourceKind { ACCRUAL, CAPITALIZATION }

/**
 * One legacy interest decision, read back from its committed row AFTER the posting transaction
 * (ADR-0352 §6): what the product-IR shadow re-decides and holds the IR to.
 *
 * [productRevisionId] is the catalog revision the account is pinned to (ADR-0341), null when the
 * account has no pin — an operator-managed rate that no revision governs.
 */
sealed interface ShadowLegacyDecision {
    val sourceId: UUID
    val accountId: UUID
    val productId: String
    val currency: String
    val productRevisionId: String?
}

/**
 * A daily accrual as `InterestService.accrueNew` wrote it. [decidedByPin] is false when the rate that
 * produced it was NOT the pinned revision's (an account-specific operator override wins over a pin),
 * in which case the IR of that revision is not the thing that decided it and nothing is compared.
 */
data class ShadowLegacyAccrual(
    override val sourceId: UUID,
    override val accountId: UUID,
    override val productId: String,
    override val currency: String,
    override val productRevisionId: String?,
    val accrualDate: LocalDate,
    val balance: BigDecimal,
    val accruedAmount: BigDecimal,
    val decidedByPin: Boolean,
) : ShadowLegacyDecision

/**
 * A capitalisation as `InterestService.capitalizeSet` wrote it. The IR is held to [grossAmount]
 * only: withholding tax (ADR-0033) has no construct in IR v1, so [taxAmount] is carried for the
 * record and never compared.
 */
data class ShadowLegacyCapitalization(
    override val sourceId: UUID,
    override val accountId: UUID,
    override val productId: String,
    override val currency: String,
    override val productRevisionId: String?,
    val periodTo: LocalDate,
    val totalAccrued: BigDecimal,
    val grossAmount: BigDecimal,
    val taxAmount: BigDecimal,
) : ShadowLegacyDecision
