// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence.repository

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealReferenceLookup
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.application.port.out.TreasuryOutboxRepository
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.DealTransition
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.FxTerms
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.LimitOverride
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.reference.CounterpartyPayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceRef
import com.openbank.treasury.domain.reference.ReferenceStatus
import com.openbank.treasury.infrastructure.persistence.entity.CounterpartyEntity
import com.openbank.treasury.infrastructure.persistence.entity.DealCommandEntity
import com.openbank.treasury.infrastructure.persistence.entity.DealEntity
import com.openbank.treasury.infrastructure.persistence.entity.DealJournalEntity
import com.openbank.treasury.infrastructure.persistence.entity.DealTransitionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class DealTransitionPanacheRepository : PanacheRepository<DealTransitionEntity>

@ApplicationScoped
class DealJournalPanacheRepository : PanacheRepository<DealJournalEntity>

@ApplicationScoped
class DealCommandPanacheRepository : PanacheRepository<DealCommandEntity>

/**
 * ADR-0344 D3: the counterparty master is the reference store's ACTIVE `counterparty` record
 * effective on the bank's today — four-eyed and effective-dated. The `counterparties` table is its
 * projection (deals keep their foreign key to it) and still carries the sandbox `synthetic` flag.
 */
@ApplicationScoped
class CounterpartyRepositoryImpl(private val references: ReferenceRepository, private val clock: Clock) :
    CounterpartyRepository,
    PanacheRepository<CounterpartyEntity> {

    override suspend fun findById(id: String): Counterparty? {
        val today = AccountingClock.bank(clock).today()
        val record = references.versions(ReferenceKind.COUNTERPARTY, id).lastOrNull { it.isEffectiveOn(today) }
            ?: return null
        val row = Panache.withSession { find("counterpartyId", id).firstResult() }.awaitSuspending()
        return (record.payload as CounterpartyPayload).toDomain(row?.synthetic ?: false)
    }

    override suspend fun list(): List<Counterparty> {
        val today = AccountingClock.bank(clock).today()
        val synthetic = Panache.withSession { listAll() }.awaitSuspending().associate {
            it.counterpartyId to
                it.synthetic
        }
        return references.list(ReferenceKind.COUNTERPARTY, ReferenceStatus.ACTIVE, today)
            .map { (it.payload as CounterpartyPayload).toDomain(synthetic[it.key] ?: false) }
            .sortedWith(compareByDescending<Counterparty> { it.kind.name }.thenBy { it.id })
    }

    private fun CounterpartyPayload.toDomain(synthetic: Boolean) = Counterparty(
        id = id,
        name = name,
        kind = CounterpartyKind.valueOf(kind.name),
        limits = limits.filterKeys { it in Deal.SUPPORTED_CURRENCIES },
        synthetic = synthetic,
    )
}

@ApplicationScoped
@Suppress("TooManyFunctions")
class DealRepositoryImpl(
    private val outbox: TreasuryOutboxRepository,
    private val transitions: DealTransitionPanacheRepository,
    private val journals: DealJournalPanacheRepository,
    private val commands: DealCommandPanacheRepository,
    private val clock: Clock,
) : DealRepository,
    DealReferenceLookup,
    PanacheRepository<DealEntity> {

    /** ADR-0344 D3: deals whose terms name [ref] (any state; a dependency query, not a filter). */
    override suspend fun dealsReferencing(ref: ReferenceRef): List<UUID> {
        val field = when (ref.kind) {
            ReferenceKind.COUNTERPARTY -> "counterpartyId"
            else -> return emptyList()
        }
        return Panache.withSession { find("$field = ?1", ref.key).list() }.awaitSuspending().map { it.dealId }
    }

    /**
     * Deal row, its NEW timeline entries, the journal reference and the outbox event — one
     * transaction (ADR-0003). Timeline rows already stored are counted and skipped, so the table
     * stays append-only.
     */
    override suspend fun save(deal: Deal, journal: LedgerJournalRef?, event: DealEvent?, command: CommandKey?): Deal {
        Panache.withTransaction {
            find("dealId", deal.id).firstResult().flatMap { existing ->
                val entity = existing ?: DealEntity().apply {
                    dealId = deal.id
                    createdAt = deal.createdAt
                }
                entity.fill(deal)
                val persisted: Uni<*> = if (existing == null) persist(entity) else Uni.createFrom().item(entity)
                persisted
                    .flatMap { transitions.count("dealId", deal.id) }
                    .flatMap { stored -> appendTransitions(deal, stored.toInt()) }
                    .flatMap { journal?.let { persistJournal(it) } ?: Uni.createFrom().voidItem() }
                    .flatMap { command?.let { persistCommand(it) } ?: Uni.createFrom().voidItem() }
                    .flatMap {
                        event?.let {
                            outbox.persistInTransaction(
                                OutboxMessage(
                                    aggregateId = deal.id,
                                    eventType = it.eventType,
                                    payload = it.payload,
                                    createdAt = clock.instant(),
                                ),
                            )
                        } ?: Uni.createFrom().voidItem()
                    }
            }
        }.awaitSuspending()
        return deal
    }

    private fun appendTransitions(deal: Deal, stored: Int): Uni<Void> {
        val fresh = deal.history.drop(stored).mapIndexed { i, t ->
            DealTransitionEntity().apply {
                dealId = deal.id
                seq = stored + i
                fromState = t.from?.name
                toState = t.to.name
                actorId = t.actor.id
                actorType = t.actor.type.name
                at = t.at
                note = t.note
            }
        }
        return if (fresh.isEmpty()) Uni.createFrom().voidItem() else transitions.persist(fresh).replaceWithVoid()
    }

    private fun persistJournal(ref: LedgerJournalRef): Uni<Void> = journals.persist(
        DealJournalEntity().apply {
            dealId = ref.dealId
            event = ref.event.name
            idempotencyKey = ref.idempotencyKey
            journalId = ref.journalId
            postedAt = ref.postedAt
        },
    ).replaceWithVoid()

    private fun persistCommand(c: CommandKey): Uni<Void> = commands.persist(
        DealCommandEntity().apply {
            idempotencyKey = c.key
            action = c.action
            dealId = c.dealId
            createdAt = clock.instant()
        },
    ).replaceWithVoid()

    override suspend fun findCommand(key: String): CommandKey? = Panache.withSession {
        commands.find("idempotencyKey", key).firstResult()
    }.awaitSuspending()?.let { CommandKey(it.idempotencyKey, it.action, it.dealId) }

    override suspend fun findById(dealId: UUID): Deal? {
        val entity = Panache.withSession { find("dealId", dealId).firstResult() }.awaitSuspending() ?: return null
        return entity.toDomain(history(dealId))
    }

    override suspend fun list(state: DealState?): List<Deal> {
        val rows = Panache.withSession {
            if (state == null) {
                find("order by createdAt desc").list()
            } else {
                find("state = ?1 order by createdAt desc", state.name).list()
            }
        }.awaitSuspending()
        return withHistories(rows)
    }

    override suspend fun dueForSettlement(today: LocalDate, states: Set<DealState>): List<Deal> = withHistories(
        Panache.withSession {
            find("state in ?1 and valueDate <= ?2 order by valueDate asc", states.map { it.name }, today).list()
        }.awaitSuspending(),
    )

    override suspend fun dueForMaturity(today: LocalDate): List<Deal> = withHistories(
        Panache.withSession {
            // An FX spot is final once SETTLED (maturityDate == valueDate); it never matures.
            find(
                "state = ?1 and maturityDate <= ?2 and product <> ?3 order by maturityDate asc",
                DealState.SETTLED.name,
                today,
                ProductType.FX_SPOT.name,
            ).list()
        }.awaitSuspending(),
    )

    override suspend fun exposure(counterpartyId: String, currency: String, excludeDealId: UUID?): BigDecimal {
        val rows = Panache.withSession {
            find(
                "counterpartyId = ?1 and currency = ?2 and state in ?3 and product in ?4",
                counterpartyId,
                currency,
                LIMIT_CONSUMING_STATE_NAMES,
                LIMIT_CONSUMING_PRODUCT_NAMES,
            ).list()
        }.awaitSuspending()
        val placed = rows.filter { it.dealId != excludeDealId }.sumOf { it.principal }
        if (currency != Deal.CZK) return placed
        // #10896: an unsettled FX spot carries settlement risk on its CZK equivalent until it settles.
        val fx = Panache.withSession {
            find(
                "counterpartyId = ?1 and product = ?2 and state in ?3",
                counterpartyId,
                ProductType.FX_SPOT.name,
                Deal.FX_LIMIT_CONSUMING_STATES.map { it.name },
            ).list()
        }.awaitSuspending()
        return placed + fx.filter { it.dealId != excludeDealId }.sumOf { it.fxCounterAmount ?: BigDecimal.ZERO }
    }

    /**
     * Deals currently PENDING_APPROVAL that carry a senior limit override (ADR-0315 D4, #10896).
     * NOT filtered by counterparty or currency in SQL: the limit line an override counts against is
     * [Deal.limitCurrency], which is not the `currency` column for every product, so the grouping
     * happens in the domain ([Deal.holdsActiveLimitOverride]). A re-submission (which clears
     * `limitOverrideBy` via [Deal.reject]) or moving off PENDING_APPROVAL retires the override.
     */
    override suspend fun pendingLimitOverrides(): List<Deal> = withHistories(
        Panache.withSession {
            find("state = ?1 and limitOverrideBy is not null", DealState.PENDING_APPROVAL.name).list()
        }.awaitSuspending(),
    )

    /**
     * Idempotent on the key: an accrual pass and a maturity can record the same day concurrently.
     * The ledger already deduplicated the journal; here the loser of the unique-key race only
     * confirms the winner's row exists instead of failing the pass.
     */
    override suspend fun recordJournal(journal: LedgerJournalRef) {
        val recorded = suspend {
            Panache.withSession { journals.find("idempotencyKey", journal.idempotencyKey).count() }.awaitSuspending() >
                0
        }
        if (recorded()) return
        try {
            Panache.withTransaction { persistJournal(journal) }.awaitSuspending()
        } catch (e: PersistenceException) {
            if (!recorded()) throw e
        } catch (e: PgException) {
            if (!recorded()) throw e
        }
    }

    override suspend fun journals(dealId: UUID): List<LedgerJournalRef> = Panache.withSession {
        journals.find("dealId = ?1 order by postedAt asc", dealId).list()
    }.awaitSuspending().map {
        LedgerJournalRef(it.dealId, PostingEvent.valueOf(it.event), it.idempotencyKey, it.journalId, it.postedAt)
    }

    private suspend fun history(dealId: UUID): List<DealTransition> = Panache.withSession {
        transitions.find("dealId = ?1 order by seq asc", dealId).list()
    }.awaitSuspending().map { it.toDomain() }

    private suspend fun withHistories(rows: List<DealEntity>): List<Deal> {
        if (rows.isEmpty()) return emptyList()
        val all = Panache.withSession {
            transitions.find("dealId in ?1 order by seq asc", rows.map { it.dealId }).list()
        }.awaitSuspending().groupBy { it.dealId }
        return rows.map { row -> row.toDomain(all[row.dealId].orEmpty().map { it.toDomain() }) }
    }

    private fun DealTransitionEntity.toDomain() = DealTransition(
        from = fromState?.let(DealState::valueOf),
        to = DealState.valueOf(toState),
        actor = Actor(actorId, ActorType.valueOf(actorType)),
        at = at,
        note = note,
    )

    private fun DealEntity.fill(deal: Deal) = apply {
        product = deal.product.name
        counterpartyId = deal.counterpartyId
        currency = deal.currency
        principal = deal.principal
        rate = deal.rate
        tradeDate = deal.tradeDate
        valueDate = deal.valueDate
        maturityDate = deal.maturityDate
        state = deal.state.name
        createdBy = deal.createdBy.id
        createdByType = deal.createdBy.type.name
        submittedBy = deal.submittedBy?.id
        submittedByType = deal.submittedBy?.type?.name
        approvedBy = deal.approvedBy?.id
        approvedByType = deal.approvedBy?.type?.name
        limitAmount = deal.limitCheck?.limit
        limitExposureBefore = deal.limitCheck?.exposureBefore
        limitDealAmount = deal.limitCheck?.dealAmount
        rationale = deal.rationale
        draftInputs = deal.inputs
        limitOverrideBy = deal.limitOverride?.by?.id
        limitOverrideReason = deal.limitOverride?.reason
        limitOverrideAt = deal.limitOverride?.at
        limitOverrideExposure = deal.limitOverride?.coversExposureUpTo
        limitOverrideLimit = deal.limitOverride?.limitAtOverride
        fxSide = deal.fx?.side?.name
        fxCounterAmount = deal.fx?.counterAmount
        fxMidRate = deal.fx?.midRate
        fxRateFlag = deal.fx?.rateFlag
        updatedAt = deal.updatedAt
    }

    /** Senior overrides are always human (the domain refuses any other actor). */
    private fun DealEntity.overrideOrNull(): LimitOverride? {
        val by = limitOverrideBy ?: return null
        return LimitOverride(
            by = Actor(by, ActorType.HUMAN),
            reason = checkNotNull(limitOverrideReason) { "deal $dealId: override without a reason" },
            at = checkNotNull(limitOverrideAt) { "deal $dealId: override without a time" },
            coversExposureUpTo = checkNotNull(limitOverrideExposure) { "deal $dealId: override without an exposure" },
            limitAtOverride = checkNotNull(limitOverrideLimit) { "deal $dealId: override without a limit" },
        )
    }

    private fun DealEntity.toDomain(history: List<DealTransition>): Deal {
        val limit = limitAmount
        val before = limitExposureBefore
        val amount = limitDealAmount
        val productType = ProductType.valueOf(product)
        val fx = fxSide?.let {
            FxTerms(
                side = FxSide.valueOf(it),
                counterAmount = checkNotNull(fxCounterAmount) { "deal $dealId: FX side without a counter amount" },
                midRate = fxMidRate,
                rateFlag = fxRateFlag,
            )
        }
        val limitCurrency = if (productType == ProductType.FX_SPOT) Deal.CZK else currency
        return Deal(
            id = dealId,
            product = productType,
            counterpartyId = counterpartyId,
            currency = currency,
            principal = principal,
            rate = rate,
            tradeDate = tradeDate,
            valueDate = valueDate,
            maturityDate = maturityDate,
            state = DealState.valueOf(state),
            createdBy = Actor(createdBy, ActorType.valueOf(createdByType)),
            createdAt = createdAt,
            updatedAt = updatedAt,
            submittedBy = submittedBy?.let { Actor(it, ActorType.valueOf(submittedByType ?: ActorType.HUMAN.name)) },
            approvedBy = approvedBy?.let { Actor(it, ActorType.valueOf(approvedByType ?: ActorType.HUMAN.name)) },
            limitCheck = if (limit != null && before != null && amount != null) {
                LimitCheck(counterpartyId, limitCurrency, limit, before, amount)
            } else {
                null
            },
            rationale = rationale,
            inputs = draftInputs,
            limitOverride = overrideOrNull(),
            fx = fx,
            history = history,
        )
    }

    private companion object {
        /** Derived from [Deal.LIMIT_CONSUMING_STATES] — never a second, independently-typed literal list. */
        val LIMIT_CONSUMING_STATE_NAMES = Deal.LIMIT_CONSUMING_STATES.map { it.name }
        val LIMIT_CONSUMING_PRODUCT_NAMES = ProductType.entries.filter { it.isAsset }.map { it.name }
    }
}
