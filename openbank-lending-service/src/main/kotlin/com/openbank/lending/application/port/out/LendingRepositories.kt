// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import io.smallrye.mutiny.Uni
import java.util.UUID

interface LoanApplicationRepository {
    fun save(application: LoanApplication): Uni<LoanApplication>
    fun findById(id: LoanApplicationId): Uni<LoanApplication?>
    fun findByParty(partyId: UUID): Uni<List<LoanApplication>>
    fun update(application: LoanApplication): Uni<LoanApplication>
}

interface LoanRepository {
    fun save(loan: Loan): Uni<Loan>
    fun findById(id: LoanId): Uni<Loan?>
    fun findByParty(partyId: UUID): Uni<List<Loan>>
    fun update(loan: Loan): Uni<Loan>
}

interface InstallmentRepository {
    fun saveAll(installments: List<LoanInstallment>): Uni<List<LoanInstallment>>
    fun findByLoan(loanId: LoanId): Uni<List<LoanInstallment>>
    fun markPaid(installmentId: UUID, paidAt: java.time.OffsetDateTime): Uni<Int>

    /**
     * Installments of ACTIVE loans whose interest is earned but not yet recognized: due on/before
     * [asOf], unpaid, and not yet accrued. Drives the scheduled interest-accrual servicing pass.
     */
    fun findAccruable(asOf: java.time.LocalDate, limit: Int): Uni<List<LoanInstallment>>

    /** Mark an installment's interest as recognized (accrual basis); idempotent guard for the pass. */
    fun markAccrued(installmentId: UUID, accruedAt: java.time.OffsetDateTime): Uni<Int>
}

interface CollateralRepository {
    fun save(collateral: Collateral): Uni<Collateral>
    fun findByLoan(loanId: LoanId): Uni<List<Collateral>>
}
