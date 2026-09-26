// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * SHA-256 over a canonical serialisation of both ledger reads and, when read, lending's loan book
 * (ADR-0314 D2, D4).
 *
 * Canonical means: one line per record, fields in a fixed order, amounts as
 * `stripTrailingZeros().toPlainString()` (so a scale change the ledger's JSON may introduce is not
 * a different input), and the lines SORTED — the ledger promises no ordering, so hashing arrival
 * order would make two identical ledgers look like different knowledge and defeat the replay key.
 *
 * The loan book adds a `LENDING` marker line plus one `LN` line per loan (every field the engine
 * reads) and one `LI` line per remaining installment. With [loans] null — the lending read
 * disabled — nothing is added, so a ledger-only run keeps the hash it had before loans existed;
 * an EMPTY book still adds the marker, because "read, and empty" builds different positions
 * (no GL-level Loans Receivable) from "not read".
 */
object InputHash {

    fun of(inputs: LedgerInputs, loans: List<LoanContract>? = null): String {
        val tb = inputs.trialBalance.map {
            listOf(
                "TB",
                it.glAccountCode,
                it.glAccountType,
                it.currency,
                it.totalDebit.c(),
                it.totalCredit.c(),
                it.net.c(),
            )
                .joinToString("|")
        }
        val sl = inputs.subLedger.map {
            listOf("SL", it.subAccountId.toString(), it.currency, it.totalDebit.c(), it.totalCredit.c())
                .joinToString("|")
        }
        val ln = loans.orEmpty().flatMap { loan ->
            listOf(loanLine(loan)) + loan.remainingInstallments.map { installmentLine(loan, it) }
        }
        val marker = if (loans == null) emptyList() else listOf("LENDING|loan-book")
        val canonical = (listOf("asOf|${inputs.asOf}") + marker + (tb + sl + ln).sorted()).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loanLine(l: LoanContract): String = listOf(
        "LN",
        l.loanId.toString(),
        l.counterpartyRef.toString(),
        l.status,
        l.currency,
        l.glAccountCode.orEmpty(),
        l.outstandingPrincipal.c(),
        l.nominalAnnualRate.c(),
        l.rateType,
        l.rateIndex.orEmpty(),
        l.spread?.c().orEmpty(),
        l.resetFrequencyMonths?.toString().orEmpty(),
        l.nextResetDate?.toString().orEmpty(),
        l.method,
        l.periodsPerYear.toString(),
        l.disbursedOn.toString(),
        l.maturityDate?.toString().orEmpty(),
        l.ifrs9Stage.orEmpty(),
    ).joinToString("|")

    private fun installmentLine(l: LoanContract, i: ScheduledInstallment): String =
        listOf("LI", l.loanId.toString(), i.number.toString(), i.dueDate.toString(), i.principal.c(), i.interest.c())
            .joinToString("|")

    private fun BigDecimal.c(): String = if (signum() == 0) "0" else stripTrailingZeros().toPlainString()
}
