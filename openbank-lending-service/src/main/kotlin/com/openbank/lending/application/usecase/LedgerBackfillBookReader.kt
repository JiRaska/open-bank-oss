// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanProvisioningRecord
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped

/**
 * Read side of the ledger backfill (#10746): the in-scope loans with their full schedule and
 * provisioning history. Read-only — it never writes lending's tables.
 */
@ApplicationScoped
class LedgerBackfillBookReader(
    private val loans: LoanRepository,
    private val installments: InstallmentRepository,
    private val provisioning: ProvisioningRepository,
) {
    fun load(scope: BackfillScope) = loans.findRecent(LedgerBackfillService.MAX_LOANS + 1).flatMap { all ->
        check(all.size <= LedgerBackfillService.MAX_LOANS) {
            "more than $LedgerBackfillService.MAX_LOANS loans: the backfill refuses rather than truncating"
        }
        val inScope = all.filter {
            it.disbursedAt.atZoneSameInstant(
                LedgerBackfillService.BUSINESS_ZONE,
            ).toLocalDate().isBefore(scope.disbursedBefore)
        }
        if (inScope.isEmpty()) {
            Uni.createFrom().item(emptyList())
        } else {
            installments.findByLoans(inScope.map { it.id.value }).flatMap { rows ->
                val byLoan = rows.groupBy { it.loanId }
                Multi.createFrom().iterable(inScope)
                    .onItem().transformToUniAndConcatenate { loan ->
                        provisioningHistory(loan).map { history ->
                            Triple(loan, byLoan[loan.id].orEmpty(), history)
                        }
                    }
                    .collect().asList()
            }
        }
    }

    /** Every provisioning record of [loan], walked back from the latest the way the live delta is computed. */
    private fun provisioningHistory(loan: Loan): Uni<List<LoanProvisioningRecord>> =
        provisioning.findLatestByLoan(loan.id).flatMap { latest -> walkBack(loan, latest, emptyList()) }

    private fun walkBack(
        loan: Loan,
        current: LoanProvisioningRecord?,
        acc: List<LoanProvisioningRecord>,
    ): Uni<List<LoanProvisioningRecord>> = if (current == null) {
        Uni.createFrom().item(acc)
    } else {
        provisioning.findLatestBefore(loan.id, current.period).flatMap { walkBack(loan, it, listOf(current) + acc) }
    }
}
