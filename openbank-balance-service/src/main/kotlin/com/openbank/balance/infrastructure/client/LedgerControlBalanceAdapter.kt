// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.client

import com.openbank.balance.application.port.out.LedgerControlBalancePort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Adapter implementing [LedgerControlBalancePort] over the ledger trial-balance REST API (ADR-0039
 * Phase A). Keeps only the *deposit-control* GL accounts (one per currency, seeded by the ledger:
 * 2100 CZK, 2101 EUR, 2102 USD, 2103 GBP) and returns their credit-normal (liability) balance —
 * `credit − debit` — keyed by currency, directly comparable to the sum of customer booked balances.
 */
@ApplicationScoped
class LedgerControlBalanceAdapter(@RestClient private val client: LedgerTrialBalanceClient) : LedgerControlBalancePort {

    override suspend fun depositControlBalanceByCurrency(asOf: LocalDate): Map<String, BigDecimal> {
        val response = client.trialBalance(asOf.toString()).awaitSuspending()
        return response.lines
            .asSequence()
            .filter { it.code in DEPOSIT_CONTROL_CODES }
            .mapNotNull { line ->
                val ccy = line.currency ?: return@mapNotNull null
                val credit = line.totalCredit ?: BigDecimal.ZERO
                val debit = line.totalDebit ?: BigDecimal.ZERO
                ccy to credit.subtract(debit)
            }
            // One deposit-control account per currency, but fold defensively just in case.
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.fold(BigDecimal.ZERO, BigDecimal::add) }
    }

    private companion object {
        /** Deposit-control GL account codes, one per currency (ledger V3 / V5 seeds). */
        val DEPOSIT_CONTROL_CODES = setOf("2100", "2101", "2102", "2103")
    }
}
