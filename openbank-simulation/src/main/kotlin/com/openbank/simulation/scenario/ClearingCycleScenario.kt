// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.SettlementPosition
import com.openbank.clearing.domain.model.SettlementType
import com.openbank.libs.domain.money.Money
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Issue #14366 (Test Intelligence simulation gap, money path): one step of a clearing cycle,
 * modelled directly against the **real `clearing-service` domain aggregates** — `ClearingItem`,
 * `ClearingBatch`, `SettlementPosition` and the `ClearingStatus`/`PaymentRail` vocabularies —
 * not a re-model of either, the same way [SepaSettlementScenario] builds on the real
 * `SepaPayment`/`Settlement` aggregates (ADR-0100). The domain package is framework-free
 * (ADR-0002).
 *
 * A step mirrors the production cycle in `ClearingService.triggerClearingCycle`: items submitted
 * for one rail are partitioned per currency (a batch is per rail+currency, because netting and
 * the net-settlement journal are only meaningful within one currency), the batch totals are
 * computed by the SAME real kernel-Money fold production uses (`totalDebit` folds item amounts,
 * `totalCredit` mirrors it bilaterally, `netPosition` is the offset), and settlement settles the
 * batch, its items, and the rail's settlement position together. An injected write fault at the
 * settlement journal fails the batch and its items together (nothing moved); an injected
 * post-commit conflict reverses the batch and its items after the journals posted (the
 * compensation branch), so every step reaches a terminal state on all three aggregates — the
 * clearing analogue of the "compensation completeness" property
 * [com.openbank.simulation.invariants.MoneyPathInvariants] already asserts for the payment sagas.
 */
object ClearingCycleScenario {

    private const val MAX_MINOR_UNITS = 500_000L // up to 5000.00 in a 2-dp currency
    private const val CLEARING_CURRENCY = "EUR"
    private const val ITEMS_PER_STEP = 3
    private const val IBAN_PREFIX_LENGTH = 24
    private const val PARTICIPANT_BIC = "OPENBACZPXXX"

    fun step(world: World) {
        val batchId = world.context.random.nextUuid()
        val items = openItems(world, batchId)
        val batchIndex = world.clearingBatches.lastIndex + 1

        // 1. Open the batch and move it and its items into clearing.
        var batch = openBatch(world, batchId, items)
        batch = storeBatch(world, batchIndex, batch, ClearingStatus.IN_CLEARING)
        items.forEach { storeItem(world, it.copy(status = ClearingStatus.IN_CLEARING)) }

        // 2. Post the settlement journal. An injected write fault here means the journal never
        //    committed: fail the batch and every item together (nothing moved, no unwind).
        val settled = settleBatch(world, batchIndex, batch, items)
        if (settled == null) return

        // 3. A post-commit conflict means the journals posted and then had to come back:
        //    reverse the batch and every item (the compensation branch).
        if (world.context.faults.shouldConflict()) {
            storeBatch(world, batchIndex, settled, ClearingStatus.REVERSED)
            items.forEach { storeItem(world, it.copy(status = ClearingStatus.REVERSED)) }
            world.audit.append("clearing batch $batchId reversed: post-commit conflict")
            return
        }

        // 4. Happy path: settle the batch, its items, and the rail's settlement position.
        val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        storeBatch(world, batchIndex, settled.copy(settledAt = now), ClearingStatus.SETTLED)
        items.forEach { storeItem(world, it.copy(status = ClearingStatus.SETTLED)) }
        world.settlementPositions.add(
            SettlementPosition(
                participantBic = PARTICIPANT_BIC,
                currency = CLEARING_CURRENCY,
                cycleId = settled.batchReference,
                grossDebit = settled.totalDebit,
                grossCredit = settled.totalCredit,
                netPosition = settled.netPosition,
                settled = true,
                settledAt = now,
                createdAt = now,
            ),
        )
        world.audit.append("clearing batch $batchId settled: ${settled.totalDebit} $CLEARING_CURRENCY")
    }

    private fun settleBatch(
        world: World,
        batchIndex: Int,
        batch: ClearingBatch,
        items: List<ClearingItem>,
    ): ClearingBatch? = try {
        if (world.context.faults.shouldFailWrite()) {
            throw SimulatedWriteFailure("settlement journal failed for batch ${batch.id}")
        }
        batch
    } catch (e: SimulatedWriteFailure) {
        val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        storeBatch(world, batchIndex, batch, ClearingStatus.FAILED)
        items.forEach {
            storeItem(
                world,
                it.copy(
                    status = ClearingStatus.FAILED,
                    errorCode = "TECHNICAL_ERROR",
                    errorMessage = e.message,
                    updatedAt = now,
                ),
            )
        }
        world.audit.append("clearing batch ${batch.id} failed: ${e.message}")
        null
    }

    private fun openItems(world: World, batchId: UUID): List<ClearingItem> {
        val random = world.context.random
        val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        // Same selection shape as SwiftScenario: an inline index draw plus an equality skip.
        val debtorAccount = world.customerAccounts[random.nextInt(world.customerAccounts.size)]

        return (1..ITEMS_PER_STEP).map {
            val amount = BigDecimal(random.nextLong(1L, MAX_MINOR_UNITS)).movePointLeft(2)
            ClearingItem(
                batchId = batchId,
                paymentId = random.nextUuid(),
                paymentReference = "CLR-${random.nextUuid()}",
                debtorIban = iban(debtorAccount),
                creditorIban = iban(world.customerAccounts.first { account -> account != debtorAccount }),
                amount = amount,
                currency = CLEARING_CURRENCY,
                rail = PaymentRail.SEPA_SCT,
                status = ClearingStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            ).also(world.clearingItems::add)
        }
    }

    private fun openBatch(world: World, batchId: UUID, items: List<ClearingItem>): ClearingBatch {
        val random = world.context.random
        val now = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC)
        // The production fold (ClearingService.openCurrencyBatch): totalDebit folds the item
        // amounts in the kernel Money, totalCredit mirrors it bilaterally, and netPosition is
        // the offset — computed here with the SAME real Money arithmetic production uses.
        val totalDebit = items.fold(Money.zero(CLEARING_CURRENCY)) { acc, item ->
            acc + Money.of(item.amount, item.currency)
        }
        val totalCredit = totalDebit
        val netPosition = totalDebit - totalCredit
        return ClearingBatch(
            id = batchId,
            batchReference = "cycle-${random.nextUuid()}",
            rail = PaymentRail.SEPA_SCT,
            settlementType = SettlementType.NET,
            status = ClearingStatus.PENDING,
            totalDebit = totalDebit.amount,
            totalCredit = totalCredit.amount,
            netPosition = netPosition.amount,
            currency = CLEARING_CURRENCY,
            itemCount = items.size,
            settlementDate = LocalDate.ofInstant(world.context.clock.instant(), ZoneOffset.UTC),
            createdAt = now,
            updatedAt = now,
        ).also(world.clearingBatches::add)
    }

    private fun storeBatch(world: World, batchIndex: Int, batch: ClearingBatch, to: ClearingStatus): ClearingBatch {
        val next = batch.copy(
            status = to,
            updatedAt = OffsetDateTime.ofInstant(world.context.clock.instant(), ZoneOffset.UTC),
        )
        world.clearingBatches[batchIndex] = next
        world.audit.append("clearing batch ${next.id} -> ${next.status}")
        return next
    }

    private fun storeItem(world: World, next: ClearingItem) {
        val index = world.clearingItems.indexOfFirst { it.id == next.id }
        world.clearingItems[index] = next
        world.audit.append("clearing item ${next.id} -> ${next.status}")
    }

    private fun iban(accountId: UUID): String = "CZ0000000000$accountId".take(IBAN_PREFIX_LENGTH)
}
