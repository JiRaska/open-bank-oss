// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import java.math.BigDecimal

/**
 * One FINREP template cell: a (row, column) coordinate with its monetary value.
 * Row and column references use the EBA FINREP notation (e.g., r010_c010).
 */
data class FinrepCell(val rowRef: String, val colRef: String, val value: BigDecimal, val currency: String = "CZK")
