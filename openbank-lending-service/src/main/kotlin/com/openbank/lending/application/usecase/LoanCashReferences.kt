// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.libs.domain.identifiers.LoanId

/**
 * Idempotency references of a loan's CUSTOMER cash legs — the bookings on the borrower's own account
 * through transaction-service, as opposed to the loan book's GL journals.
 *
 * Held in one place because two writers must agree on them byte for byte: the live flow
 * ([LendingService.disburse], [LendingService.recordRepayment]) and the four-eyes reconstruction of
 * those legs for loans booked before they existed (#11487). transaction-service collapses a repeated
 * reference onto the transaction already booked, so a shared key is what makes the reconstruction unable
 * to charge or pay a borrower twice for the same economic event.
 */
object LoanCashReferences {
    fun disbursementCredit(loanId: LoanId): String = "loan:${loanId.value}:disbursement-credit"

    fun repaymentDebit(loanId: LoanId, installmentNumber: Int): String =
        "loan:${loanId.value}:inst:$installmentNumber:repayment-debit"
}
