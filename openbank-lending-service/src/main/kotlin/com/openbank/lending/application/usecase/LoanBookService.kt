// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.`in`.LoanBookUseCase
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.domain.model.LoanBook
import com.openbank.lending.domain.model.LoanBookAssembler
import com.openbank.lending.domain.model.LoanBookTooLargeException
import com.openbank.lending.infrastructure.client.LendingGlChart
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate

/**
 * The loan book for the risk engine (ADR-0314 D4). READ-ONLY BY CONSTRUCTION: three repository
 * reads, no write, no posting, no event. The IFRS 9 stage is the latest provisioning record's, read
 * back, never recomputed here.
 *
 * The book is returned whole or not at all: a prefix of the book would tie out against nothing, so
 * over [MAX_LOANS] the read fails ([LoanBookTooLargeException]) rather than truncating silently.
 */
@ApplicationScoped
class LoanBookService(
    private val loans: LoanRepository,
    private val installments: InstallmentRepository,
    private val provisioning: ProvisioningRepository,
) : LoanBookUseCase {

    override fun loanBook(asOf: LocalDate): Uni<LoanBook> =
        loans.findOnBook(LoanBookAssembler.OFF_BOOK, MAX_LOANS + 1).flatMap { book ->
            if (book.size > MAX_LOANS) throw LoanBookTooLargeException(MAX_LOANS)
            installments.findByLoans(book.map { it.id.value }).flatMap { schedule ->
                provisioning.findLatestPerLoan().map { latest ->
                    LoanBookAssembler.assemble(
                        asOf = asOf,
                        loans = book,
                        installments = schedule,
                        latestStage = latest.associate { it.loanId to it.stage.name },
                        glCode = LendingGlChart::loansReceivableCode,
                    )
                }
            }
        }

    companion object {
        const val MAX_LOANS = 20_000
    }
}
