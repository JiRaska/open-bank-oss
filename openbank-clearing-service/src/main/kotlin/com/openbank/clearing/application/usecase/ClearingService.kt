// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.application.usecase

import com.openbank.clearing.application.port.`in`.GetBatchUseCase
import com.openbank.clearing.application.port.`in`.GetItemUseCase
import com.openbank.clearing.application.port.`in`.GetPositionsUseCase
import com.openbank.clearing.application.port.`in`.ReconcileUseCase
import com.openbank.clearing.application.port.`in`.SubmitPaymentUseCase
import com.openbank.clearing.application.port.`in`.TriggerClearingUseCase
import com.openbank.clearing.application.port.out.ClearingBatchRepository
import com.openbank.clearing.application.port.out.ClearingCycleMetrics
import com.openbank.clearing.application.port.out.ClearingEventPublisher
import com.openbank.clearing.application.port.out.ClearingItemRepository
import com.openbank.clearing.application.port.out.SettlementAccountDirectory
import com.openbank.clearing.application.port.out.SettlementPositionRepository
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingCycleResult
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.ReconciliationReport
import com.openbank.clearing.domain.model.SettlementPosition
import com.openbank.clearing.domain.model.SettlementType
import com.openbank.clearing.domain.model.SubmitPaymentCommand
import com.openbank.libs.domain.money.Money
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// TooManyFunctions: the idempotency violation detector (ADR-0298) pushes the use-case facade
// to the threshold; splitting it out would scatter the submit path's replay logic.
@Suppress("TooManyFunctions")
@ApplicationScoped
class ClearingService(
    private val batchRepo: ClearingBatchRepository,
    private val itemRepo: ClearingItemRepository,
    private val positionRepo: SettlementPositionRepository,
    private val eventPublisher: ClearingEventPublisher,
    private val clock: Clock,
    private val settlementAccounts: SettlementAccountDirectory,
    private val cycleMetrics: ClearingCycleMetrics,
) : SubmitPaymentUseCase,
    GetBatchUseCase,
    GetItemUseCase,
    TriggerClearingUseCase,
    GetPositionsUseCase,
    ReconcileUseCase {

    private val log = Logger.getLogger(ClearingService::class.java)

    @Retry(maxRetries = 3)
    override fun submit(command: SubmitPaymentCommand): Uni<ClearingItem> {
        val now = OffsetDateTime.now(clock)
        val item = ClearingItem(
            batchId = UUID.fromString("00000000-0000-0000-0000-000000000000"), // assigned during clearing
            paymentId = command.paymentId,
            paymentReference = command.paymentReference,
            debtorIban = command.debtorIban,
            creditorIban = command.creditorIban,
            debtorBic = command.debtorBic,
            creditorBic = command.creditorBic,
            // Canonical currency scale and upper-case ISO code, from the boundary-built Money.
            amount = command.amount.amount,
            currency = command.amount.currency.code,
            rail = command.rail,
            status = ClearingStatus.PENDING,
            valueDate = command.valueDate ?: LocalDate.now(clock),
            endToEndId = command.endToEndId,
            remittanceInfo = command.remittanceInfo,
            createdAt = now,
            updatedAt = now,
        )
        // Idempotent replay (ADR-0298, #8351): a payment enters clearing exactly once, so the
        // natural key is paymentId. A retried POST replays the existing item instead of stacking
        // a second PENDING row the clearing cycle would sweep into a batch — the same payment
        // settled twice. Check-first covers the retry window; uq_clearing_items_payment (V9) is
        // the DB backstop, and a lost race re-reads the winner rather than erroring the caller.
        return itemRepo.findByPaymentId(command.paymentId).flatMap { existing ->
            existing.firstOrNull()?.let { Uni.createFrom().item(it) }
                ?: itemRepo.save(item).onFailure(this::isPaymentUniqueViolation)
                    .recoverWithUni { _: Throwable ->
                        itemRepo.findByPaymentId(command.paymentId)
                            .map { winners -> winners.first() }
                    }
        }
    }

    private fun isPaymentUniqueViolation(t: Throwable): Boolean = generateSequence(t) { it.cause }
        .filterIsInstance<java.sql.SQLException>()
        .any {
            it.message?.contains("uq_clearing_items_payment") == true &&
                (it.sqlState == "23505" || it.message.orEmpty().contains("(23505)"))
        }

    override fun getBatch(id: UUID): Uni<ClearingBatch?> = batchRepo.findById(id)

    override fun listBatches(status: ClearingStatus?, page: Int, size: Int): Uni<List<ClearingBatch>> =
        if (status != null) {
            batchRepo.findByStatus(status)
        } else {
            batchRepo.findAll(page, size)
        }

    override fun getItem(id: UUID): Uni<ClearingItem?> = itemRepo.findById(id)
    override fun listItemsByBatch(batchId: UUID): Uni<List<ClearingItem>> = itemRepo.findByBatchId(batchId)
    override fun listItemsByPayment(paymentId: UUID): Uni<List<ClearingItem>> = itemRepo.findByPaymentId(paymentId)

    @Timeout(value = 30000)
    override fun triggerClearingCycle(rail: PaymentRail): Uni<ClearingCycleResult> {
        // One cycle can open several currency batches; a millisecond suffix can collide,
        // and the old SEPA_SCT_INST format exceeded cycle_id VARCHAR(32).
        val cycleId = "C-${rail.name}-${UUID.randomUUID().toString().replace("-", "").take(14)}"
        // #11974: a batch is per (rail, currency). Only currencies with a settlement GL pair are
        // selected at all, so an unsettleable item can neither enter a batch whose journal could
        // never post, nor occupy the selection window and starve settleable items on every run.
        val settleable = settlementAccounts.settleableCurrencies()
        return itemRepo.countPendingWithoutRail().flatMap { unresolved ->
            check(unresolved == 0L) {
                "Cannot clear while $unresolved legacy pending items have no verified payment rail"
            }
            itemRepo.countPendingOutside(rail, settleable)
        }.flatMap { stranded ->
            reportUnsettleable(rail, cycleId, stranded)
            itemRepo.findPendingByRail(rail, settleable, CYCLE_ITEM_LIMIT).flatMap { items ->
                if (items.isEmpty()) {
                    emptyCycle(rail, cycleId).map { ClearingCycleResult(cycleId, rail, listOf(it), stranded) }
                } else {
                    // One batch per currency, in a stable order so the batch references and the
                    // event sequence of a cycle are reproducible.
                    val byCurrency = items.groupBy { it.currency }.toSortedMap()
                    Multi.createFrom().iterable(byCurrency.entries)
                        .onItem().transformToUniAndConcatenate { (currency, group) ->
                            openCurrencyBatch(rail, cycleId, currency, group)
                        }
                        .collect().asList()
                        .map { ClearingCycleResult(cycleId, rail, it, stranded) }
                }
            }
        }
    }

    private fun emptyCycle(rail: PaymentRail, cycleId: String): Uni<ClearingBatch> {
        val now = OffsetDateTime.now(clock)
        val emptyBatch = ClearingBatch(
            batchReference = cycleId,
            rail = rail,
            status = ClearingStatus.SETTLED,
            cycleId = cycleId,
            settlementDate = LocalDate.now(clock),
            createdAt = now,
            updatedAt = now,
        )
        // An empty cycle still RAN, and a consumer that receives nothing cannot tell
        // "the cycle ran and had nothing to settle" from "the cycle did not run" -- the
        // two states are the reason this event exists. So the batch is born SETTLED *and*
        // announced, atomically, exactly as a populated one is.
        //
        // `batch.settled` only: NOT the net_settlement.post command the populated path
        // also emits, because there is no journal to post. Emitting a zero-amount
        // settlement command would give NetSettlementPostingConsumer work that must not
        // happen.
        return batchRepo.saveWithEvent(emptyBatch, eventPublisher.batchSettledMessage(emptyBatch))
    }

    /**
     * Opens the IN_CLEARING batch for one currency of the cycle. Every item in [items] carries
     * [currency] by construction (the caller grouped on it), so the kernel `Money.plus` below is a
     * safety net: it would throw on a mixed group instead of adding euros to koruny, and for a
     * valid group it cannot.
     */
    private fun openCurrencyBatch(
        rail: PaymentRail,
        cycleId: String,
        currency: String,
        items: List<ClearingItem>,
    ): Uni<ClearingBatch> {
        val now = OffsetDateTime.now(clock)
        // For GROSS settlement: every item is a debit from our participant's perspective.
        // totalCredit = gross sum of incoming amounts for the counterparty rail leg.
        // For NET batches these will be equal (bilateral exchange); for multi-lateral
        // netting positionRepo.upsertPosition() is used per participant in settleBatch().
        val totalDebit = items.fold(Money.zero(currency)) { acc, i -> acc + Money.of(i.amount, i.currency) }
        val totalCredit = totalDebit // bilateral: same volume on both legs
        val netPosition = totalDebit - totalCredit // net exposure after offset
        val batch = ClearingBatch(
            // batch_reference is UNIQUE (V1); the cycle has one batch per currency.
            batchReference = "$cycleId-$currency",
            rail = rail,
            settlementType = SettlementType.NET,
            status = ClearingStatus.IN_CLEARING,
            totalDebit = totalDebit.amount,
            totalCredit = totalCredit.amount,
            netPosition = netPosition.amount,
            currency = currency,
            itemCount = items.size,
            cycleId = cycleId,
            settlementDate = LocalDate.now(clock),
            createdAt = now,
            updatedAt = now,
        )
        return batchRepo.save(batch).flatMap { savedBatch ->
            val updatedItems = items.map {
                it.copy(
                    batchId = savedBatch.id,
                    status = ClearingStatus.IN_CLEARING,
                    revision = it.revision + 1,
                    updatedAt = now,
                )
            }
            itemRepo.saveAll(updatedItems).map { savedBatch }
        }
    }

    private fun reportUnsettleable(rail: PaymentRail, cycleId: String, stranded: Map<String, Long>) {
        cycleMetrics.recordUnsettleablePending(stranded)
        stranded.forEach { (currency, count) ->
            log.warnf(
                "[clearing-cycle] %s %s: %d PENDING item(s) in %s left unbatched — no settlement GL " +
                    "account exists for that currency; they settle on the first cycle after one is seeded",
                cycleId,
                rail,
                count,
                currency,
            )
        }
    }

    override fun settleBatch(batchId: UUID): Uni<ClearingBatch> = batchRepo.findById(batchId).flatMap { batch ->
        if (batch == null) {
            Uni.createFrom().failure(IllegalArgumentException("Batch not found: $batchId"))
        } else {
            check(batch.status == ClearingStatus.IN_CLEARING) {
                "Cannot settle batch in status ${batch.status}"
            }
            val now = OffsetDateTime.now(clock)
            val settled = batch.copy(
                status = ClearingStatus.SETTLED,
                settledAt = now,
                updatedAt = now,
            )
            // #8509: ONE transaction for batch + items + outbox rows, owned by the repository
            // (settleWithEvents) — composing update/saveAll/publish here gave each its own
            // transaction (measured xmin 750 vs 752) and could lose the settled event on a crash
            // between commits. The boundary lives in infrastructure so this use case stays
            // unit-testable without a Vert.x context (the sanctions `saveWithEvent` shape).
            // ADR-0281: the net_settlement.post command commits in the SAME transaction — a
            // SETTLED batch always has its settlement-leg intent durable; the actual journal
            // posting is the consumer's idempotent job.
            itemRepo.findByBatchId(batchId).flatMap { items ->
                val updatedItems = items.map {
                    it.copy(status = ClearingStatus.SETTLED, revision = it.revision + 1, updatedAt = now)
                }
                batchRepo.settleWithEvents(
                    settled,
                    updatedItems,
                    listOf(
                        eventPublisher.batchSettledMessage(settled),
                        eventPublisher.netSettlementPostMessage(settled),
                    ) + updatedItems.map(eventPublisher::itemClearedMessage),
                )
            }
        }
    }

    override fun getPositions(cycleId: String): Uni<List<SettlementPosition>> = positionRepo.findByCycleId(cycleId)

    override fun reconcileBatch(batchId: UUID): Uni<ReconciliationReport> =
        batchRepo.findById(batchId).flatMap { batch ->
            if (batch == null) {
                Uni.createFrom().failure(IllegalArgumentException("Batch not found: $batchId"))
            } else {
                itemRepo.findByBatchId(batchId).map { items ->
                    val stuckIds = items
                        .filter { it.status != ClearingStatus.SETTLED && it.status != ClearingStatus.REVERSED }
                        .map { it.id }
                    ReconciliationReport(
                        batchId = batchId,
                        cycleId = batch.cycleId,
                        expectedItemCount = batch.itemCount,
                        settledItemCount = items.count { it.status == ClearingStatus.SETTLED },
                        stuckItemIds = stuckIds,
                        checkedAt = OffsetDateTime.now(clock),
                    )
                }
            }
        }

    private companion object {
        const val CYCLE_ITEM_LIMIT = 1000
    }
}
