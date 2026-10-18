// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.clearing.application.port.out.NetSettlementPosting
import com.openbank.clearing.application.usecase.ClearingService
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingCycleResult
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.SettlementType
import com.openbank.clearing.infrastructure.client.NetSettlementJournalFactory
import com.openbank.clearing.infrastructure.client.PostJournalRequest
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Money-path clearing adoption: one step of the `clearing-service` cycle, modelled directly
 * against the **real clearing domain aggregates** (`ClearingItem`/`ClearingBatch`/
 * `ClearingCycleResult`), the **real `NetSettlementJournalFactory`** (the settleable-currency set
 * and the seeded cash-clearing/scheme-settlement GL pairs from ledger migrations V3/V14/V26), the
 * **real `NetSettlementPosting`** command validation, and the **real
 * `ClearingService.cycleIdFor`** cycle-id derivation (#12005 bound check) — not a re-model of any
 * of them. The domain package is framework-free (ADR-0002), so only the domain POJOs and the pure
 * factory land on the classpath.
 *
 * A step mirrors `ClearingService.triggerClearingCycle` + `openCurrencyBatch` + `settleBatch` for
 * one rail: seed PENDING items (one seeded fraction in a currency with NO settlement GL pair — the
 * #11974 reported-not-dropped path; one seeded fraction on a DIFFERENT rail — the #12004
 * rail-isolation path), then sweep this rail's pending items: settleable currencies are batched
 * one per currency (the real kernel-`Money` totals fold, NET bilateral totals), unsettleable
 * currencies are counted into the real `ClearingCycleResult.unsettleablePending` and stay PENDING
 * — reported, never dropped.
 *
 * Each opened batch is settled in the same step with its real net-settlement journal:
 *  - an injected write fault at the batch open leaves the selected items FAILED — the enum's
 *    designed terminal for "could not clear", carried with errorCode/errorMessage, reported not
 *    dropped. Production instead leaves them PENDING for the next cycle's sweep; the DST collapses
 *    that unbounded retry horizon into the step so every seeded fault is terminal-complete, the
 *    same collapse [SctInstPaymentScenario] makes for its settlement write fault;
 *  - an injected write fault at the settlement post fails the batch + its items with NO journal
 *    (money must not move for a batch whose settlement leg could not commit — production would
 *    DLQ + alert via `NetSettlementPostingConsumer`'s retry/rethrow, never book the leg);
 *  - a seeded duplicate delivery of the net-settlement command collapses onto the one booked
 *    journal — the real deterministic `clearing-net-settlement-{batchId}` idempotency key
 *    (`LedgerState.post` idempotency, the ADR-0281 at-least-once guarantee);
 *  - a post-commit conflict after the journal is durable unwinds it through the real
 *    `JournalEntry.reverse()` and takes the batch + its items to REVERSED — the status
 *    `ReconciliationReport` explicitly treats as "settled, then backed out";
 *  - the happy path settles the batch + its items and posts exactly one balanced GL-to-GL journal
 *    (DEBIT Customer Cash Clearing, CREDIT Scheme Settlement — the real factory's legs), which
 *    carries no customer dimension, so the balance projection never sees it.
 *
 * `MoneyPathInvariants.clearingCompleteness` and `clearingBatchConservation` assert the whole
 * book after every step.
 */
object ClearingCycleScenario {

    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private const val IBAN_PREFIX_LENGTH = 24
    private const val UNSETTLEABLE_CCY = "JPY" // no settlement GL pair (factory covers CZK/EUR/USD/GBP only)
    private const val UNSETTLEABLE_ITEM_RATE = 0.25
    private const val OTHER_RAIL_ITEM_RATE = 0.30
    private const val MAX_EXTRA_SETTLEABLE_ITEMS = 3
    private const val BATCH_OPEN_FAILED_CODE = "BATCH_OPEN_FAILED"
    private const val SETTLEMENT_POST_FAILED_CODE = "SETTLEMENT_POST_FAILED"
    private const val SYSTEM_ACTOR_LSB = 14L

    private val SYSTEM_ACTOR: UUID = UUID(0L, SYSTEM_ACTOR_LSB)
    private val RAILS = listOf(PaymentRail.SEPA_SCT, PaymentRail.SWIFT, PaymentRail.DOMESTIC)

    // Production's "assigned during clearing" placeholder (ClearingService.submit): the cycle
    // re-parents swept items onto their batch, exactly like the real sweep.
    private val UNBATCHED: UUID = UUID(0L, 0L)

    fun step(world: World) {
        val rail = world.context.random.pick(RAILS)
        seedItems(world, rail)
        runCycle(world, rail)
    }

    private fun seedItems(world: World, rail: PaymentRail) {
        val random = world.context.random
        val now = now(world)
        val settleableItems = 1 + random.nextInt(MAX_EXTRA_SETTLEABLE_ITEMS)
        repeat(settleableItems) {
            world.clearing.recordItem(newItem(world, rail, world.currency, now))
        }
        if (random.chance(UNSETTLEABLE_ITEM_RATE)) {
            world.clearing.recordItem(newItem(world, rail, UNSETTLEABLE_CCY, now))
        }
        if (random.chance(OTHER_RAIL_ITEM_RATE)) {
            world.clearing.recordItem(newItem(world, random.pick(RAILS.filter { it != rail }), world.currency, now))
        }
    }

    private fun newItem(world: World, rail: PaymentRail, currency: String, now: OffsetDateTime): ClearingItem {
        val random = world.context.random
        val accounts = world.customerAccounts
        val debtor = random.pick(accounts)
        var creditor = random.pick(accounts)
        if (creditor == debtor) {
            creditor = accounts[(accounts.indexOf(debtor) + 1) % accounts.size]
        }
        return ClearingItem(
            id = random.nextUuid(),
            batchId = UNBATCHED,
            paymentId = random.nextUuid(),
            paymentReference = "clr-${random.nextUuid()}",
            debtorIban = iban(debtor),
            creditorIban = iban(creditor),
            amount = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2),
            currency = currency,
            rail = rail,
            status = ClearingStatus.PENDING,
            revision = 0L,
            valueDate = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC),
            endToEndId = "e2e-${random.nextUuid()}",
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Mirrors `ClearingService.triggerClearingCycle`: select THIS rail's PENDING items in
     * settleable currencies only (#11974), batch one per currency in stable order, count the
     * unsettleable currencies into the cycle result's `unsettleablePending` — and settle every
     * opened batch before the step ends.
     */
    private fun runCycle(world: World, rail: PaymentRail) {
        val today = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        val cycleId = ClearingService.cycleIdFor(rail, today, world.context.clock.instant().toEpochMilli())
        val settleable = NetSettlementJournalFactory.settleableCurrencies
        val pending = world.clearing.items.filter { it.rail == rail && it.status == ClearingStatus.PENDING }
        val unsettleablePending = pending
            .filter { it.currency !in settleable }
            .groupingBy { it.currency }
            .eachCount()
            .mapValues { it.value.toLong() }
        val batches = pending
            .filter { it.currency in settleable }
            .groupBy { it.currency }
            .toSortedMap()
            .mapNotNull { (currency, group) -> openBatch(world, cycleId, rail, currency, today, group) }
        world.clearing.recordCycle(ClearingCycleResult(cycleId, rail, batches, unsettleablePending))
        batches.forEach { settleBatch(world, it, today) }
    }

    /**
     * Opens the IN_CLEARING batch for one currency group, with the real `openCurrencyBatch`
     * totals fold (kernel `Money.plus` is the mixed-currency safety net; NET bilateral totals).
     * An injected write fault means the batch could not commit: the selected items are marked
     * FAILED — the enum's designed terminal for "could not clear", reported (with
     * errorCode/errorMessage), not dropped (see the object KDoc for the production-collapse note).
     */
    private fun openBatch(
        world: World,
        cycleId: String,
        rail: PaymentRail,
        currency: String,
        today: LocalDate,
        items: List<ClearingItem>,
    ): ClearingBatch? {
        val now = now(world)
        if (world.context.faults.shouldFailWrite()) {
            items.forEach { item ->
                world.clearing.replaceItem(
                    item.copy(
                        status = ClearingStatus.FAILED,
                        revision = item.revision + 1L,
                        errorCode = BATCH_OPEN_FAILED_CODE,
                        errorMessage = "batch open write fault in cycle $cycleId",
                        updatedAt = now,
                    ),
                )
            }
            world.audit.append(
                "clearing cycle $cycleId $rail: batch open write fault, ${items.size} item(s) FAILED",
            )
            return null
        }
        val total = items.fold(Money.zero(currency)) { acc, item -> acc + Money.of(item.amount, item.currency) }
        val batch = ClearingBatch(
            id = world.context.random.nextUuid(),
            batchReference = "$cycleId-$currency",
            rail = rail,
            settlementType = SettlementType.NET,
            status = ClearingStatus.IN_CLEARING,
            totalDebit = total.amount,
            totalCredit = total.amount,
            // NET bilateral: totalCredit is the same fold by construction, so net exposure is zero.
            netPosition = BigDecimal.ZERO,
            currency = currency,
            itemCount = items.size,
            cycleId = cycleId,
            settlementDate = today,
            createdAt = now,
            updatedAt = now,
        )
        world.clearing.recordBatch(batch)
        items.forEach { item ->
            world.clearing.replaceItem(
                item.copy(
                    batchId = batch.id,
                    status = ClearingStatus.IN_CLEARING,
                    revision = item.revision + 1L,
                    updatedAt = now,
                ),
            )
        }
        world.audit.append(
            "clearing cycle $cycleId $rail: opened batch ${batch.id} ($currency, ${items.size} item(s))",
        )
        return batch
    }

    /**
     * Settles one opened batch (batch + items SETTLED) and posts its real net-settlement journal.
     * Outcomes under the fault injector: write fault → batch + items FAILED with no journal;
     * duplicate delivery → collapses onto the one booked journal; post-commit conflict → the real
     * `JournalEntry.reverse()` unwinds the journal and the batch + items go to REVERSED; happy
     * path → SETTLED with exactly one balanced GL-to-GL journal.
     */
    private fun settleBatch(world: World, batch: ClearingBatch, today: LocalDate) {
        val now = now(world)
        if (world.context.faults.shouldFailWrite()) {
            world.clearing.replaceBatch(batch.copy(status = ClearingStatus.FAILED, updatedAt = now))
            world.clearing.itemsOf(batch.id).forEach { item ->
                world.clearing.replaceItem(
                    item.copy(
                        status = ClearingStatus.FAILED,
                        revision = item.revision + 1L,
                        errorCode = SETTLEMENT_POST_FAILED_CODE,
                        errorMessage = "batch ${batch.id} settlement leg could not post",
                        updatedAt = now,
                    ),
                )
            }
            world.audit.append("clearing batch ${batch.id}: settlement post write fault, batch + items FAILED")
            return
        }
        world.clearing.replaceBatch(batch.copy(status = ClearingStatus.SETTLED, settledAt = now, updatedAt = now))
        advanceItems(world, batch.id, now, ClearingStatus.SETTLED)

        val posting = NetSettlementPosting(
            batchId = batch.id,
            batchReference = batch.batchReference,
            cycleId = batch.cycleId ?: error("opened batch ${batch.id} has no cycleId"),
            idempotencyKey = "clearing-net-settlement-${batch.id}",
            currency = batch.currency,
            settlementAmount = batch.totalDebit,
            valueDate = batch.settlementDate ?: today,
        )
        val request = NetSettlementJournalFactory.build(posting)
        val entry = toJournalEntry(world, request)
        world.ledger.post(posting.idempotencyKey, entry)
        world.clearing.recordPosting(batch.id, entry.id, posting.settlementAmount)

        if (world.context.faults.shouldDuplicateEvent()) {
            // at-least-once redelivery: the same deterministic key collapses onto the one journal
            world.ledger.post(posting.idempotencyKey, entry)
            world.audit.append("clearing batch ${batch.id}: duplicate net-settlement delivery collapsed")
        }
        if (world.context.faults.shouldConflict()) {
            world.ledger.reverse(
                entry.id,
                world.context.random.nextUuid(),
                SYSTEM_ACTOR,
                lineIdProvider = { world.context.random.nextUuid() },
            )
            world.clearing.markReversed(batch.id)
            world.clearing.replaceBatch(
                world.clearing.batches.first { it.id == batch.id }
                    .copy(status = ClearingStatus.REVERSED, updatedAt = now),
            )
            advanceItems(world, batch.id, now, ClearingStatus.REVERSED)
            world.audit.append("clearing batch ${batch.id}: post-commit conflict, journal reversed, batch REVERSED")
            return
        }
        world.audit.append(
            "clearing batch ${batch.id}: SETTLED, net settlement ${posting.settlementAmount} ${batch.currency}",
        )
    }

    private fun advanceItems(world: World, batchId: UUID, now: OffsetDateTime, to: ClearingStatus) {
        world.clearing.itemsOf(batchId).forEach { item ->
            world.clearing.replaceItem(
                item.copy(status = to, revision = item.revision + 1L, updatedAt = now),
            )
        }
    }

    /** Maps the real factory's PostJournalRequest onto the real JournalEntry aggregate. */
    private fun toJournalEntry(world: World, request: PostJournalRequest): JournalEntry {
        val journalId = world.context.random.nextUuid()
        val lines = request.lines.mapIndexed { index, line ->
            JournalLine(
                id = UUID(journalId.mostSignificantBits, (index + 1).toLong()),
                journalId = journalId,
                glAccountId = line.glAccountId,
                side = JournalSide.valueOf(line.side),
                amount = Money.of(line.amount, line.currencyCode),
                fxRate = null,
                baseAmount = Money.of(line.baseAmount, line.baseCurrencyCode),
                sequence = index + 1,
                subAccountId = null, // GL-to-GL leg: no customer dimension, so no balance projection
            )
        }
        return JournalEntry(
            id = journalId,
            entryNumber = null,
            transactionId = request.transactionId,
            entryDate = LocalDate.parse(request.entryDate),
            valueDate = LocalDate.parse(request.valueDate),
            description = request.description,
            status = JournalStatus.PENDING,
            lines = lines,
            createdAt = world.context.clock.instant(),
            createdBy = request.createdBy,
            version = 0L,
        ).post()
    }

    private fun now(world: World): OffsetDateTime =
        OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)

    private fun iban(accountId: UUID): String = "CZ55OPEN0000$accountId".take(IBAN_PREFIX_LENGTH)
}
