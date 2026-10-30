// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.usecase

import com.openbank.tax.application.port.out.IncomeCreditRepository
import com.openbank.tax.domain.returns.IncomeCreditTotals
import com.openbank.tax.domain.returns.ObservedIncomeCredit
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate

/**
 * Records the interest credits the fleet announces (ADR-0340 B) and hands the CRS/FATCA assembly
 * its per-account income aggregates. Storage is keyed by the withholding id, so a redelivered
 * event never double-counts into a report line.
 */
@ApplicationScoped
class IncomeCreditService(private val repository: IncomeCreditRepository) {
    /** @return true when newly recorded, false on redelivery. */
    suspend fun record(credit: ObservedIncomeCredit): Boolean = repository.record(credit)

    suspend fun totalsByAccount(from: LocalDate, to: LocalDate): List<IncomeCreditTotals> =
        repository.totalsByAccount(from, to)
}
