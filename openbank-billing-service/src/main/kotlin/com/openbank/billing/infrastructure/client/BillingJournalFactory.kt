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
 * Fees are single-currency (no FX in phase 2, ADR-0143): DEBIT the customer's deposit-control
 * leg with `subAccountId = accountId` for the sub-ledger tie-out (ADR-0039 Phase B — the only GL
 * account class that accepts `subAccountId`), CREDIT the bank fee-income leg, both in the fee's
 * own currency (`baseAmount == amount`). Legs name an accounting class and role, never a GL
 * account (ADR-0341 D3): the ledger resolves them with the leg's currency.
 */
object BillingJournalFactory {

    /**
     * The customer leg's class comes from the pinned revision; the ledger maps (class, PRINCIPAL,
     * currency) to that currency's deposit-control GL (ledger V32). In CZK that is 2100, the very
     * account billing always debited; `FeeJournalsUnchangedTest` proves CZK is byte-identical. A
     * non-CZK fee debits its own control account (2101-2103) -- the old fixed CZK account was refused
     * by the ledger for any other currency, so no non-CZK fee ever posted through it.
     */
    private const val CUSTOMER_ROLE = "PRINCIPAL"

    /**
     * ROLLOUT SHIM (`openbank.billing.ledger.post-by-class` = false, the default): the two accounts the
     * previous release named by id, `a0..0002` (2100) and `a0..4003` (4003), for every currency exactly as
     * before. It exists so deploying billing before the ledger that serves accounting classes cannot make
     * the ledger refuse every fee. Flip the switch once that ledger is live, then delete these two.
     */
    private val FEE_RECEIVABLE_GL: UUID = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    private val FEE_INCOME_GL: UUID = UUID.fromString("a0000000-0000-0000-0000-000000004003")

    /** The bank's fee income, a class of its own that no product revision carries (ADR-0341 D3). */
    private const val FEE_INCOME_CLASS = "FEE_INCOME_ACCOUNT"
    private const val FEE_INCOME_ROLE = "FEE_INCOME"

    fun buildRequest(
        command: FeeJournalCommand,
        systemActorId: UUID,
        date: LocalDate,
        postByClass: Boolean = true,
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
        lines = buildLines(command, postByClass),
    )

    fun buildLines(command: FeeJournalCommand, postByClass: Boolean = true): List<LedgerJournalLineRequest> {
        val ccy = command.currency
        val value = command.amount
        return listOf(
            LedgerJournalLineRequest(
                glAccountId = FEE_RECEIVABLE_GL.takeUnless { postByClass },
                accountingClass = command.accountingClass.takeIf { postByClass },
                accountingRole = CUSTOMER_ROLE.takeIf { postByClass },
                side = "DEBIT",
                amount = value,
                currencyCode = ccy,
                baseAmount = value,
                baseCurrencyCode = ccy,
                subAccountId = accountIdAsUuidOrNull(command.accountId),
            ),
            LedgerJournalLineRequest(
                glAccountId = FEE_INCOME_GL.takeUnless { postByClass },
                accountingClass = FEE_INCOME_CLASS.takeIf { postByClass },
                accountingRole = FEE_INCOME_ROLE.takeIf { postByClass },
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
     * [buildRequest]/[buildLines] — CREDIT the customer's deposit-control GL (`subAccountId =
     * accountId`), DEBIT the bank fee-income GL — same amount/currency, own
     * [FeeReversalCommand.idempotencyKey]
     * (distinct from the original charge's) so the ledger's idempotency store treats it as a new,
     * independent posting rather than a replay of the charge.
     */
    fun buildReversalRequest(
        command: FeeReversalCommand,
        systemActorId: UUID,
        date: LocalDate,
        postByClass: Boolean = true,
    ): LedgerPostJournalRequest = LedgerPostJournalRequest(
        idempotencyKey = command.idempotencyKey,
        transactionId = UUID.nameUUIDFromBytes(command.idempotencyKey.toByteArray(Charsets.UTF_8)),
        entryDate = date.toString(),
        valueDate = date.toString(),
        description = "Reversal of fee charge (${command.reason})",
        createdBy = systemActorId,
        lines = buildReversalLines(command, postByClass),
    )

    fun buildReversalLines(command: FeeReversalCommand, postByClass: Boolean = true): List<LedgerJournalLineRequest> {
        val ccy = command.currency
        val value = command.amount
        return listOf(
            LedgerJournalLineRequest(
                glAccountId = FEE_RECEIVABLE_GL.takeUnless { postByClass },
                accountingClass = command.accountingClass.takeIf { postByClass },
                accountingRole = CUSTOMER_ROLE.takeIf { postByClass },
                side = "CREDIT",
                amount = value,
                currencyCode = ccy,
                baseAmount = value,
                baseCurrencyCode = ccy,
                subAccountId = accountIdAsUuidOrNull(command.accountId),
            ),
            LedgerJournalLineRequest(
                glAccountId = FEE_INCOME_GL.takeUnless { postByClass },
                accountingClass = FEE_INCOME_CLASS.takeIf { postByClass },
                accountingRole = FEE_INCOME_ROLE.takeIf { postByClass },
                side = "DEBIT",
                amount = value,
                currencyCode = ccy,
                baseAmount = value,
                baseCurrencyCode = ccy,
            ),
        )
    }
}
