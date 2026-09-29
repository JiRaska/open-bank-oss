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
    val balances = ConcurrentHashMap<Pair<String, LocalDate>, BigDecimal>()
    val queries = CopyOnWriteArrayList<String>()

    override suspend fun nostroLines(glCode: String, from: LocalDate, to: LocalDate): List<LedgerNostroLine> {
        queries += "lines:$glCode:$from..$to"
        return lines.filter { it.first == glCode && it.second.entryDate in from..to }.map { it.second }
    }

    override suspend fun glBalance(glCode: String, asOf: LocalDate): BigDecimal {
        queries += "balance:$glCode:$asOf"
        return balances[glCode to asOf] ?: BigDecimal.ZERO
    }

    fun reset() {
        lines.clear()
        balances.clear()
        queries.clear()
    }
}
