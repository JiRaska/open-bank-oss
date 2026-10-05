// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.application.port.out.LedgerReadPort
import com.openbank.treasury.domain.model.LedgerNostroLine
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The ledger's read surface, scripted per test (#10896). Records every query it answered. */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeLedgerRead : LedgerReadPort {
    val lines = CopyOnWriteArrayList<Pair<String, LedgerNostroLine>>()

    /** Native-currency balances keyed by (glCode, currency, asOf); absent = zero. */
    val balances = ConcurrentHashMap<Triple<String, String, LocalDate>, BigDecimal>()
    var defaultBalance: BigDecimal = BigDecimal.ZERO

    /** GL codes the fake ledger answers 404 for (the one "not stated" case). */
    val unknownAccounts = java.util.concurrent.CopyOnWriteArraySet<String>()
    val queries = CopyOnWriteArrayList<String>()

    /** GL codes whose balance read fails upstream (e.g. a ledger not serving the route yet). */
    val unavailableAccounts = java.util.concurrent.CopyOnWriteArraySet<String>()

    override suspend fun nostroLines(glCode: String, from: LocalDate, to: LocalDate): List<LedgerNostroLine> {
        queries += "lines:$glCode:$from..$to"
        return lines.filter { it.first == glCode && it.second.entryDate in from..to }.map { it.second }
    }

    override suspend fun accountBalance(glCode: String, currency: String, asOf: LocalDate): BigDecimal? {
        queries += "balance:$glCode:$currency:$asOf"
        if (glCode in unavailableAccounts) {
            throw com.openbank.treasury.application.port.out.LedgerUnavailableException(
                "ledger answered 404 for $glCode",
            )
        }
        if (glCode in unknownAccounts) return null
        return balances[Triple(glCode, currency, asOf)] ?: defaultBalance
    }

    fun reset() {
        lines.clear()
        balances.clear()
        defaultBalance = BigDecimal.ZERO
        unknownAccounts.clear()
        unavailableAccounts.clear()
        queries.clear()
    }
}
