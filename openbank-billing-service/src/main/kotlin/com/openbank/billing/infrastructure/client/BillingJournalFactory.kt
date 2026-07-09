// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.client

import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.domain.FeeReversalCommand
import java.time.LocalDate
import java.util.UUID

/**
 * Turns a fee [FeeJournalCommand] into a balanced double-entry [LedgerPostJournalRequest] for
 * ledger-service (ADR-0143 step 2). Pure and side-effect-free so the accounting is fully
 * unit-tested without any HTTP — mirrors `openbank-lending-service`'s `LendingJournalFactory`.
 *
 * Fees are single-currency (no FX in phase 2, ADR-0143): DEBIT the customer fee-receivable GL
 * with `subAccountId = accountId` for the sub-ledger tie-out (ADR-0039 Phase B), CREDIT the bank
 * fee-income GL, both in the fee's own currency (`baseAmount == amount`).
 */
object BillingJournalFactory {

    fun buildRequest(
        command: FeeJournalCommand,
        accounts: BillingLedgerConfig.Gl,
        systemActorId: UUID,
        date: LocalDate,
    ): LedgerPostJournalRequest = LedgerPostJournalRequest(
        // The idempotency key is already unique per (cycleId, accountId, feeId, currency)
        // (ADR-0143 step 3), so it doubles as the ledger's own dedup key — a redrive replays
        // to the same journal rather than posting a second one.
        idempotencyKey = command.idempotencyKey,
        transactionId = UUID.nameUUIDFromBytes(command.idempotencyKey.toByteArray(Charsets.UTF_8)),
        entryDate = date.toString(),
        valueDate = date.toString(),
        description = command.description,
        createdBy = systemActorId,
        lines = buildLines(command, accounts),
    )

    fun buildLines(command: FeeJournalCommand, accounts: BillingLedgerConfig.Gl): List<LedgerJournalLineRequest> {
        val ccy = command.currency
        val value = command.amount
        return listOf(
            LedgerJournalLineRequest(
                glAccountId = accounts.feeReceivable(),
                side = "DEBIT",
                amount = value,
                currencyCode = ccy,
                baseAmount = value,
                baseCurrencyCode = ccy,
                subAccountId = accountIdAsUuidOrNull(command.accountId),
            ),
            LedgerJournalLineRequest(
                glAccountId = accounts.feeIncome(),
                side = "CREDIT",
                amount = value,
                currencyCode = ccy,
                baseAmount = value,
                baseCurrencyCode = ccy,
            ),
        )
    }

    /**
     * `subAccountId` on the ledger is a `UUID`; billing's `accountId` (from account-service reads)
     * is carried as a plain `String` end-to-end (ADR service-per-bounded-context: billing doesn't
     * depend on account-service's id type). Parse when it already is a UUID (the fleet convention);
     * fall back to a deterministic name-based UUID so the sub-ledger tie-out is still stable and
     * collision-free per account rather than silently dropping the tie-out.
     */
    private fun accountIdAsUuidOrNull(accountId: String): UUID = runCatching { UUID.fromString(accountId) }
        .getOrElse { UUID.nameUUIDFromBytes(accountId.toByteArray(Charsets.UTF_8)) }

    /**
     * The compensating journal for a wrongly-charged fee (ADR-0143 phase 2e): the EXACT reverse of
     * [buildRequest]/[buildLines] — CREDIT the customer fee-receivable GL (`subAccountId = accountId`),
     * DEBIT the bank fee-income GL — same amount/currency, own [FeeReversalCommand.idempotencyKey]
     * (distinct from the original charge's) so the ledger's idempotency store treats it as a new,
     * independent posting rather than a replay of the charge.
     */
    fun buildReversalRequest(
        command: FeeReversalCommand,
        accounts: BillingLedgerConfig.Gl,
        systemActorId: UUID,
        date: LocalDate,
    ): LedgerPostJournalRequest = LedgerPostJournalRequest(
        idempotencyKey = command.idempotencyKey,
        transactionId = UUID.nameUUIDFromBytes(command.idempotencyKey.toByteArray(Charsets.UTF_8)),
        entryDate = date.toString(),
        valueDate = date.toString(),
        description = "Reversal of fee charge (${command.reason})",
        createdBy = systemActorId,
        lines = buildReversalLines(command, accounts),
    )

    fun buildReversalLines(
        command: FeeReversalCommand,
        accounts: BillingLedgerConfig.Gl,
    ): List<LedgerJournalLineRequest> {
        val ccy = command.currency
        val value = command.amount
        return listOf(
            LedgerJournalLineRequest(
                glAccountId = accounts.feeReceivable(),
                side = "CREDIT",
                amount = value,
                currencyCode = ccy,
                baseAmount = value,
                baseCurrencyCode = ccy,
                subAccountId = accountIdAsUuidOrNull(command.accountId),
            ),
            LedgerJournalLineRequest(
                glAccountId = accounts.feeIncome(),
                side = "DEBIT",
                amount = value,
                currencyCode = ccy,
                baseAmount = value,
                baseCurrencyCode = ccy,
            ),
        )
    }
}
