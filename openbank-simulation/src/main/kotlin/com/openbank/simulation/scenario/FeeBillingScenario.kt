// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.billing.domain.AssessedFee
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.domain.FeeReversalCommand
import com.openbank.billing.domain.PostingStatus
import com.openbank.libs.product.WaiveReason
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.adapters.AccountBookedChanged
import com.openbank.simulation.model.AccountCurrency
import com.openbank.simulation.model.BillingFeeKey
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * ADR-0143 phase 2d/2e: one step of the billing fee-charge (and, for a seeded fraction of runs,
 * reversal) path, modelled directly against the **real `openbank-billing-service` domain value
 * objects** (`AssessedFee`, `FeeJournalCommand`, `FeeReversalCommand`, `PostingStatus`) and the
 * **real `openbank-ledger-service` `JournalEntry`** aggregate — not a re-model of either, mirroring
 * [PaymentScenario]/[SepaSettlementScenario]'s "build on the real system" convention (ADR-0100).
 *
 * Before this scenario existed, [World.billingFees] was never populated by any scenario, so
 * `MoneyPathInvariants.billingFeeConservation` (ADR-0143 phase 2d) held VACUOUSLY on every seeded
 * run — `world.billingFees.keys()` was always empty, so `check()` unconditionally returned `null`.
 * This scenario is what makes the invariant load-bearing: it assesses a fee, posts the balanced
 * charge journal (DEBIT customer fee-receivable GL `subAccountId = accountId`, CREDIT fee-income
 * GL — the exact ADR-0143 step 2 shape), records both sides into [World.billingFees], and for a
 * seeded fraction of steps also posts the compensating reversal journal (ADR-0143 phase 2e: CREDIT
 * fee-receivable, DEBIT fee-income — the exact reverse), exercising both the charge and reversal
 * posting paths this DST run.
 *
 * A fee amount is deliberately small relative to [World.config]'s opening booked balance — a
 * billing fee is a modest periodic charge in production too, not a payment-sized transfer — but
 * across enough steps on the same account even small charges can accumulate past the overdraft
 * floor, so this scenario also guards against that itself: it never posts a charge that would
 * drive [World.balances]' available amount below the account's floor, instead assessing (and
 * recording) the fee as waived — mirroring ADR-0143's own fail-closed posture, extended here to
 * "insufficient room" rather than only "context unresolved" — so `MoneyPathInvariants
 * .noNegativeBalance` stays a signal for a REAL money-path defect, not an artifact of this
 * scenario's own unconditional charging.
 */
object FeeBillingScenario {

    private const val MAX_FEE_MINOR_UNITS = 500L // up to 5.00 in a 2-dp currency — a modest fee, not a payment
    private const val REVERSAL_RATE = 0.25 // seeded fraction of steps whose charge is also reversed this run
    private val ZONE = ZoneId.of("Europe/Prague")

    // Mirrors BillingLedgerConfig.Gl's two leaf GL accounts (billing-service's own fixed chart-of-
    // accounts config) — dedicated ids so this scenario's journals are trivially distinguishable
    // from PaymentScenario's DEPOSIT_CONTROL_GL / SepaSettlementScenario's settlement legs in a
    // trace dump.
    private val FEE_RECEIVABLE_GL: UUID = UUID(0L, 3L)
    private val FEE_INCOME_GL: UUID = UUID(0L, 4L)
    private val SYSTEM_ACTOR: UUID = UUID(0L, 5L)

    fun step(world: World) {
        val random = world.context.random
        val accounts = world.customerAccounts

        val accountId = random.pick(accounts)
        // A fresh cycleId per step (not just per seed): a real billing cycle only ever assesses
        // a given (account, fee, currency) ONCE per cycle (ADR-0143 step 1, idempotent assess) —
        // reusing one cycleId across every step in a seed would mean two steps that happen to
        // pick the same account collide on the SAME idempotencyKey, which the real service
        // resolves via idempotent replay (returns the existing assessment untouched) but this
        // scenario has no persisted-assessment lookup to replay from. A per-step cycleId keeps
        // every step's key space-disjoint, matching PaymentScenario's per-step-fresh-saga /
        // SepaSettlementScenario's per-step-fresh-payment convention.
        val cycleId = "sim-cycle-${world.context.seed}-${random.nextLong()}"
        val feeId = "maintenance"
        val amount = BigDecimal(random.nextLong(1L, MAX_FEE_MINOR_UNITS)).movePointLeft(2)
        val currency = world.currency
        val accountCurrency = AccountCurrency(accountId, currency)

        // Fail-closed guard (mirrors ADR-0143's own "skip and flag rather than charge on absent
        // context" posture, extended here to insufficient funds): a real waiver/fee-context
        // engine is out of this scenario's scope, but charging blindly enough times WOULD
        // legitimately drive an account through its overdraft floor over many steps — a
        // fee-charging defect distinct from anything MoneyPathInvariants.noNegativeBalance is
        // meant to catch. Skipping (assessed but waived to 0, exactly like a real waived fee)
        // when there isn't room keeps this scenario's own choices from tripping an unrelated
        // invariant, while still exercising assess -> [waive] -> (maybe) post -> (maybe) reverse.
        val floor = world.balances.get(accountCurrency).arrangedOverdraftLimit.negate()
        val hasRoom = world.balances.get(accountCurrency).available() - amount >= floor

        val assessedFee = AssessedFee(
            cycleId = cycleId,
            accountId = accountId.toString(),
            feeId = feeId,
            name = "Maintenance fee",
            currency = currency,
            chargedAmount = if (hasRoom) amount else BigDecimal.ZERO,
            waived = !hasRoom,
            reason = if (hasRoom) WaiveReason.NOT_WAIVABLE else WaiveReason.WAIVED_BY_CONDITION,
        )
        val key = billingFeeKey(assessedFee, accountId)
        world.billingFees.recordAssessed(key, assessedFee.chargedAmount)

        if (!hasRoom) {
            world.audit.append("fee ${assessedFee.idempotencyKey} waived: insufficient available balance")
            return
        }

        // Post the charge journal — the real BillingJournalFactory shape (DEBIT fee-receivable
        // subAccountId=accountId, CREDIT fee-income), built against the real JournalEntry
        // aggregate so validateBalance()/bookedDeltas() run production code, not a re-model.
        val chargeCommand = FeeJournalCommand(
            idempotencyKey = assessedFee.idempotencyKey,
            cycleId = cycleId,
            accountId = assessedFee.accountId,
            feeId = feeId,
            amount = amount,
            currency = currency,
            description = "Fee charge: ${assessedFee.name}",
        )
        val chargeEntry = buildChargeJournal(world, chargeCommand, accountId)
        world.ledger.post(assessedFee.idempotencyKey, chargeEntry)
        publishBooked(world, chargeEntry)
        world.billingFees.recordPosted(key, amount)
        world.audit.append("fee ${assessedFee.idempotencyKey} posted: journal ${chargeEntry.id}")

        var fee = assessedFee.copy(postingStatus = PostingStatus.POSTED, journalId = chargeEntry.id)

        // ADR-0143 phase 2e: for a seeded fraction of charges, also reverse it this same step —
        // exercises the compensating-journal posting path so a seed can catch a defect there too
        // (e.g. a broken GL-side swap would unbalance the reversal and fail validateBalance(),
        // or a mis-keyed idempotency would collide with the charge and silently skip the reversal).
        if (world.context.random.chance(REVERSAL_RATE)) {
            fee = reverseFee(world, fee, accountId)
        }
    }

    private fun reverseFee(world: World, fee: AssessedFee, accountId: UUID): AssessedFee {
        val reversalCommand = FeeReversalCommand(
            idempotencyKey = fee.reversalIdempotencyKey,
            originalIdempotencyKey = fee.idempotencyKey,
            cycleId = fee.cycleId,
            accountId = fee.accountId,
            feeId = fee.feeId,
            amount = fee.chargedAmount,
            currency = fee.currency,
            reason = "simulated reversal (DST)",
        )
        val reversalEntry = buildReversalJournal(world, reversalCommand, accountId)
        // Own idempotency key, own journal — a DISTINCT posting from the charge, per ADR-0143
        // phase 2e (never collapses into a replay of the charge's own idempotencyKey).
        world.ledger.post(reversalCommand.idempotencyKey, reversalEntry)
        publishBooked(world, reversalEntry)
        world.audit.append("fee ${fee.idempotencyKey} reversed: journal ${reversalEntry.id} (${reversalCommand.reason})")
        // Deliberately NOT world.billingFees.recordPosted/recordAssessed again: the reversal is an
        // ADDITIONAL, independent compensating journal, not an un-post of the original charge —
        // billing-fee-conservation only asserts the CHARGE leg balances; conservationOfMoney
        // (Σ debit == Σ credit across the whole ledger) is what proves the reversal itself is a
        // correctly-balanced journal, exercised by chargeEntry/reversalEntry both landing in
        // world.ledger.
        return fee.copy(
            postingStatus = PostingStatus.REVERSED,
            reversalJournalId = reversalEntry.id,
            reversalReason = reversalCommand.reason,
        )
    }

    private fun billingFeeKey(fee: AssessedFee, accountId: UUID) =
        BillingFeeKey(cycleId = fee.cycleId, accountId = accountId, feeId = fee.feeId, currency = fee.currency)

    /** DEBIT customer fee-receivable (subAccountId=accountId), CREDIT fee-income — ADR-0143 step 2. */
    private fun buildChargeJournal(world: World, command: FeeJournalCommand, accountId: UUID): JournalEntry {
        val journalId = world.context.random.nextUuid()
        val money = Money.of(command.amount, command.currency)
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZONE)
        val debit = JournalLine(
            id = lineId(journalId, 1L),
            journalId = journalId,
            glAccountId = FEE_RECEIVABLE_GL,
            side = JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
            subAccountId = accountId,
        )
        val credit = JournalLine(
            id = lineId(journalId, 2L),
            journalId = journalId,
            glAccountId = FEE_INCOME_GL,
            side = JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = world.context.random.nextUuid(),
            entryDate = today,
            valueDate = today,
            description = command.description,
            status = JournalStatus.PENDING,
            lines = listOf(debit, credit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    /** The exact reverse of [buildChargeJournal]: CREDIT fee-receivable, DEBIT fee-income — ADR-0143 phase 2e. */
    private fun buildReversalJournal(world: World, command: FeeReversalCommand, accountId: UUID): JournalEntry {
        val journalId = world.context.random.nextUuid()
        val money = Money.of(command.amount, command.currency)
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZONE)
        val credit = JournalLine(
            id = lineId(journalId, 1L),
            journalId = journalId,
            glAccountId = FEE_RECEIVABLE_GL,
            side = JournalSide.CREDIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 1,
            subAccountId = accountId,
        )
        val debit = JournalLine(
            id = lineId(journalId, 2L),
            journalId = journalId,
            glAccountId = FEE_INCOME_GL,
            side = JournalSide.DEBIT,
            amount = money,
            fxRate = null,
            baseAmount = money,
            sequence = 2,
        )
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = world.context.random.nextUuid(),
            entryDate = today,
            valueDate = today,
            description = "Reversal of fee charge (${command.reason})",
            status = JournalStatus.PENDING,
            lines = listOf(credit, debit),
            createdAt = world.context.clock.instant(),
            createdBy = SYSTEM_ACTOR,
            version = 0L,
        ).post()
    }

    private fun lineId(journalId: UUID, sequence: Long): UUID = UUID(journalId.mostSignificantBits, sequence)

    /** Project the entry's real `bookedDeltas()` onto the bus — same as [PaymentScenario.publishBooked]. */
    private fun publishBooked(world: World, entry: JournalEntry) {
        entry.bookedDeltas().forEach { delta ->
            world.bus.publish(
                AccountBookedChanged(entry.id, AccountCurrency(delta.accountId, delta.currency), delta.delta),
            )
        }
    }
}
