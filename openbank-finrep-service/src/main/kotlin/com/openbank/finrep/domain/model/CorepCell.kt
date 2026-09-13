// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import java.math.BigDecimal

/**
 * One COREP template cell: a (row, column) coordinate with its monetary value.
 * Row references use the EBA COREP C 01.00 notation (e.g., r010, r020).
 *
 * [isDataGap] marks a cell whose value could NOT be honestly derived from data available in
 * the source trial balance (e.g. no recognised capital account has a balance) — it is reported as an
 * explicit, flagged zero rather than a computed value, and MUST NOT be confused with a real
 * attested zero balance. Regulatory reports must never have silent gaps (ADR-0097): a missing
 * input is always a visible, documented cell, never an omitted row.
 */
data class CorepCell(
    val rowRef: String,
    val colRef: String,
    val label: String,
    val value: BigDecimal,
    val currency: String = "CZK",
    val isDataGap: Boolean = false,
    val gapReason: String? = null,
)
