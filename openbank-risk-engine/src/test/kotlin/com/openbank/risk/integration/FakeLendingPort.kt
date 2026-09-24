// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import com.openbank.risk.application.port.out.LendingPort
import com.openbank.risk.domain.model.LoanContract
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.LocalDate

/**
 * Replaces the lending REST adapter in @QuarkusTest, like [FakeLedgerPort] for the ledger: the test
 * sets [loans] and the snapshot reads exactly that book. Defaults to an EMPTY book, which is what
 * the ledger-only fixtures need (they hold no Loans Receivable), so the pre-D4 ITs are unchanged.
 */
@Alternative
@Priority(1)
@ApplicationScoped
class FakeLendingPort : LendingPort {
    @Volatile
    var loans: List<LoanContract> = emptyList()

    /** How often the snapshot actually asked — lets a test prove the switched-off read is never made. */
    val reads = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun readLoanBook(asOf: LocalDate): List<LoanContract> {
        reads.incrementAndGet()
        return loans
    }
}
