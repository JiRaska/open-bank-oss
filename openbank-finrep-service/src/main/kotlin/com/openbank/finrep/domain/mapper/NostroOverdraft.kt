// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import java.math.BigDecimal

/**
 * Nostro accounts 1001/1002 are assets, but a credit balance (net < 0) is an overdraft: a deposit
 * from a credit institution. Netting it against positive nostros would understate both sides.
 */
internal object NostroOverdraft {
    private val codes = setOf("1001", "1002")

    /** Overdrawn amount as a positive number, summed per ledger line (per account and currency). */
    fun of(lines: List<TrialBalanceLineDto>): BigDecimal = lines
        .filter { it.code in codes && it.net.signum() < 0 }
        .fold(BigDecimal.ZERO) { acc, line -> acc.subtract(line.net) }
}
