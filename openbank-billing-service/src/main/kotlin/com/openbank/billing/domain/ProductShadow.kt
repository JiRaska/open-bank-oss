// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.domain

import java.math.BigDecimal

/** One fee as the legacy path decided it: what the shadow comparator holds the product IR to. */
data class ShadowLegacyFee(val feeId: String, val chargedAmount: BigDecimal, val waived: Boolean)

/**
 * Everything the product IR needs to re-decide an assessment, captured at assessment time and
 * committed in the same transaction (ADR-0352 §6: the legacy decision is published to an outbox and
 * compared OFF the posting path). The inputs are frozen here because the account's balance and
 * turnover move; re-reading them later would compare the IR against a different account.
 */
data class ProductShadowInput(
    val cycleId: String,
    val accountId: String,
    val currency: String,
    val productRevisionId: String,
    val balance: BigDecimal?,
    val monthlyTurnover: BigDecimal?,
    val aggregatePocketBalance: BigDecimal?,
    val segment: String?,
    val legacyFees: List<ShadowLegacyFee>,
)
