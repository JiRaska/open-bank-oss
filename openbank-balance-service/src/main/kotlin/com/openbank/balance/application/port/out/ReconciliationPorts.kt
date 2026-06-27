// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.application.port.out

import com.openbank.balance.domain.reconciliation.ReconciliationReport
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outbound port to read the ledger's per-currency deposit-control balance (ADR-0039 Phase A).
 * Implemented over the ledger trial-balance read API; returns the credit-normal (liability) balance
 * of each deposit-control account, keyed by currency.
 */
interface LedgerControlBalancePort {

    /** Deposit-control credit-normal balance (credit − debit) per currency, as of [asOf]. */
    suspend fun depositControlBalanceByCurrency(asOf: LocalDate): Map<String, BigDecimal>
}

/** Outbound persistence port for the audit trail of reconciliation runs (ADR-0039 Phase A). */
interface ReconciliationRecordRepository {

    suspend fun save(report: ReconciliationReport): ReconciliationReport

    suspend fun findLatest(): ReconciliationReport?
}
