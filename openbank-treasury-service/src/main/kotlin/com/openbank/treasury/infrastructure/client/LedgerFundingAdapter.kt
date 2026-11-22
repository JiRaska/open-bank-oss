// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.client

import com.openbank.treasury.application.port.out.FundingPort
import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.application.port.out.LedgerUnavailableException
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.LocalDate

/**
 * #12115: the funding check's read of the contra (nostro) account, over the ledger's own read
 * surface. The account comes from the ledger's accountingClass map (ADR-0341 D3) — treasury names
 * no GL code itself. A currency with no account in the map, or an account the ledger does not hold,
 * is a [LedgerUnavailableException] (502), never a zero: a booking is refused only by a real
 * figure, and never waved through on a guessed one.
 */
@ApplicationScoped
class LedgerFundingAdapter(private val ledger: LedgerReadPort) : FundingPort {
    override suspend fun balance(currency: String, asOf: LocalDate): BigDecimal {
        val account = ledger.nostroAccounts()[currency]
            ?: throw LedgerUnavailableException(
                "the ledger's accounting-class map holds no nostro account for $currency; " +
                    "funding cannot be verified",
            )
        return ledger.accountBalance(account.glCode, currency, asOf)
            ?: throw LedgerUnavailableException(
                "the ledger does not hold the $currency contra account ${account.glCode}; " +
                    "funding cannot be verified",
            )
    }
}
