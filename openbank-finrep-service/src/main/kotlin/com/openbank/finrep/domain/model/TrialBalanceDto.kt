// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import java.math.BigDecimal

/**
 * Lightweight DTO for a single GL account line from the ledger trial balance.
 * accountType corresponds to GlAccountType values: ASSET, LIABILITY, EQUITY, INCOME, EXPENSE.
 */
data class TrialBalanceDto(val code: String, val accountType: String, val net: BigDecimal)
