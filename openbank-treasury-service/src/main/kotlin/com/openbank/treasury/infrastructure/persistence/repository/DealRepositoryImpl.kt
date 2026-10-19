// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.treasury.application.port.out.CashFlowConflictException
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CorrectionCommit
import com.openbank.treasury.application.port.out.CounterpartyRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealReferenceLookup
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.application.port.out.ScheduleWrite
import com.openbank.treasury.application.port.out.TreasuryOutboxRepository
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.CorrectionBlockedByPaymentException
import com.openbank.treasury.domain.model.CorrectionStaleException
import com.openbank.treasury.domain.model.Counterparty
import com.openbank.treasury.domain.model.CounterpartyKind
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealHeader
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.DealTransition
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.FxTerms
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.LegacyLegs
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.LimitOverride
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.reference.CounterpartyPayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceRef
import com.openbank.treasury.domain.reference.ReferenceStatus
import com.openbank.treasury.infrastructure.cashflow.CashFlowConflictEntity
import com.openbank.treasury.infrastructure.cashflow.CashFlowEntity
import com.openbank.treasury.infrastructure.cashflow.CashFlowOverrideEntity
import com.openbank.treasury.infrastructure.cashflow.DealVersionEntity
import com.openbank.treasury.infrastructure.cashflow.fill
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

@ApplicationScoped
class DealVersionPanacheRepository : PanacheRepository<DealVersionEntity>

@ApplicationScoped
class CashFlowPanacheRepository : PanacheRepository<CashFlowEntity>

@ApplicationScoped
class CashFlowOverridePanacheRepository : PanacheRepository<CashFlowOverrideEntity>

@ApplicationScoped
class CashFlowConflictPanacheRepository : PanacheRepository<CashFlowConflictEntity>

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
// LongParameterList: the deal aggregate spans these tables and they commit in ONE transaction;
// splitting the adapter would split the transaction.
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class DealRepositoryImpl(
    private val outbox: TreasuryOutboxRepository,
    private val transitions: DealTransitionPanacheRepository,
    private val journals: DealJournalPanacheRepository,
    private val commands: DealCommandPanacheRepository,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val versions: DealVersionPanacheRepository,
    private val cashFlows: CashFlowPanacheRepository,
    private val overrideRepo: CashFlowOverridePanacheRepository,
    private val conflictRepo: CashFlowConflictPanacheRepository,
) : DealRepository,
    DealReferenceLookup,
    PanacheRepository<DealEntity> {

    /** ADR-0344 D3: deals whose terms name [ref] (any state; a dependency query, not a filter). */
    override suspend fun dealsReferencing(ref: ReferenceRef): List<UUID> {
        val rows = Panache.withSession {
            when (ref.kind) {
                ReferenceKind.COUNTERPARTY -> find("counterpartyId = ?1", ref.key).list()
                ReferenceKind.BOOK -> find("bookId = ?1", ref.key).list()
                ReferenceKind.SECURITY -> find("securityIsin = ?1", ref.key).list()
                // Named inside the JSON documents: a coarse LIKE, then an exact check on the decoded terms.
                ReferenceKind.INDEX, ReferenceKind.CALENDAR ->
                    find("legsJson like ?1 or headerJson like ?1", "%\"${ref.key}\"%").list()
                else -> Uni.createFrom().item(emptyList())
            }
        }.awaitSuspending()
        return rows.filter { row ->
            when (ref.kind) {
                ReferenceKind.INDEX -> legsOf(row).any { it.rate.index == ref.key }
                ReferenceKind.CALENDAR -> ref.key in headerOf(row).calendars ||
                    legsOf(row).any { ref.key in it.paymentCalendars }
                else -> true
            }
        }.map { it.dealId }
    }

    /**
     * Deal row, its NEW timeline entries, the journal reference and the outbox event — one
     * transaction (ADR-0003). Timeline rows already stored are counted and skipped, so the table
     * stays append-only.
     */
    override suspend fun save(
        deal: Deal,
        journal: LedgerJournalRef?,
        event: DealEvent?,
        command: CommandKey?,
        schedule: ScheduleWrite?,
        extraEvents: List<DealEvent>,
        withdrawPayments: Boolean,
        limitGuard: com.openbank.treasury.application.port.out.ExposureGuard?,
    ): Deal {
        val actor = deal.history.lastOrNull()?.actor?.id ?: deal.createdBy.id
        Panache.withTransaction {
            val row = lockedRow(deal.id, withdrawPayments, limitGuard)
            row.flatMap { existing ->
                val entity = existing ?: DealEntity().apply {
                    dealId = deal.id
                    createdAt = deal.createdAt
                }
                entity.fill(deal)
                entity.lastChangedBy = actor
                val persisted: Uni<*> = if (existing == null) persist(entity) else Uni.createFrom().item(entity)
                persisted
                    .flatMap { transitions.count("dealId", deal.id) }
                    .flatMap { stored -> appendTransitions(deal, stored.toInt()) }
                    .flatMap { journal?.let { persistJournal(it) } ?: Uni.createFrom().voidItem() }
                    .flatMap { command?.let { persistCommand(it) } ?: Uni.createFrom().voidItem() }
                    .flatMap { versionRow(deal, actor) }
                    .flatMap { schedule?.let { scheduleRows(it) } ?: Uni.createFrom().voidItem() }
                    .flatMap {
                        listOfNotNull(event).plus(extraEvents).fold(Uni.createFrom().voidItem()) { chain, e ->
                            chain.flatMap {
                                outbox.persistInTransaction(
                                    OutboxMessage(
                                        aggregateId = deal.id,
                                        eventType = e.eventType,
                                        payload = e.payload,
                                        createdAt = clock.instant(),
                                    ),
                                )
                            }
                        }
                    }
            }
        }.awaitSuspending()
        return deal
    }

    /**
     * The deal row, locked when the change withdraws payments or consumes a limit line. Lock order
     * everywhere: the deal row, then the limit line (as [commitCorrection] does), so two writes on
     * one line never deadlock.
     */
    private fun lockedRow(
        dealId: UUID,
        withdrawPayments: Boolean,
        limitGuard: com.openbank.treasury.application.port.out.ExposureGuard?,
    ): Uni<DealEntity?> {
        if (!withdrawPayments && limitGuard == null) return find("dealId", dealId).firstResult()
        return find("dealId", dealId).withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).firstResult()
            .flatMap { locked ->
                if (withdrawPayments) withdraw(dealId).map { locked } else Uni.createFrom().item(locked)
            }
            .flatMap { locked ->
                limitGuard?.let { g ->
                    Panache.getSession().flatMap { exposureUnderLock(it, dealId, g) }.map { locked }
                }
                    ?: Uni.createFrom().item(locked)
            }
    }

    /** One `deal_versions` row per terms version; the schedule columns follow the latest generation. */
    private fun versionRow(deal: Deal, actor: String): Uni<Void> =
        versions.find("dealId = ?1 and version = ?2", deal.id, deal.version).firstResult().flatMap { row ->
            if (row == null) {
                versions.persist(
                    DealVersionEntity().apply {
                        dealId = deal.id
                        version = deal.version
                        termsJson = objectMapper.writeValueAsString(terms(deal))
                        scheduleVersion = deal.scheduleVersion.takeIf { it > 0 }
                        scheduleHash = deal.scheduleHash
                        createdBy = actor
                        createdAt = clock.instant()
                        changes = if (deal.version == 1) "created" else null
                    },
                ).replaceWithVoid()
            } else {
                row.scheduleVersion = deal.scheduleVersion.takeIf { it > 0 }
                row.scheduleHash = deal.scheduleHash
                Uni.createFrom().voidItem()
            }
        }

    /** The economic terms a version is made of (what a later version would be diffed against). */
    private fun terms(deal: Deal) = mapOf(
        "product" to deal.product.name,
        "kind" to deal.kind.name,
        "counterpartyId" to deal.counterpartyId,
        "currency" to deal.currency,
        "principal" to deal.principal,
        "rate" to deal.rate,
        "tradeDate" to deal.tradeDate,
        "valueDate" to deal.valueDate,
        "maturityDate" to deal.maturityDate,
        "legs" to deal.legs,
        "header" to deal.header.copy(confirmationsSent = emptyList()),
    )

    /**
     * Under the deal lock: refuse while a payment of the deal is released; else cancel its PROPOSED
     * payments with scoped, version-bumped updates, free exactly their flows, drop their links and
     * record the status event — all in the caller's transaction.
     */
    private fun withdraw(dealId: java.util.UUID): Uni<Void> = Panache.getSession().flatMap { session ->
        val like = "%$dealId%"
        session.createNativeQuery<Any>(
            "select cast(payment_id as varchar) from treasury_payments where deal_ids like :d and status in (:released)",
        ).setParameter("d", like).setParameter("released", RELEASED_STATUSES).resultList.flatMap { released ->
            released.firstOrNull()?.let {
                throw com.openbank.treasury.domain.payment.DealHasReleasedPaymentException(
                    dealId,
                    java.util.UUID.fromString(it.toString()),
                )
            }
            session.createNativeQuery<Any>(
                "select cast(payment_id as varchar) from treasury_payments where deal_ids like :d and status = 'PROPOSED' for update",
            ).setParameter("d", like).resultList
        }.flatMap { proposed ->
            cancelProposed(session, dealId, proposed.map { java.util.UUID.fromString(it.toString()) })
        }
    }

    /** Cancel each PROPOSED payment (scoped, guarded on its status), free its flows, record the event. */
    private fun cancelProposed(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        dealId: java.util.UUID,
        pids: List<java.util.UUID>,
    ): Uni<Void> = pids.fold(Uni.createFrom().voidItem()) { chain, pid ->
        chain.flatMap {
            session.createNativeQuery<Any>(
                "update deal_cash_flows set payment_id = null, row_version = row_version + 1 where payment_id = :p",
            ).setParameter("p", pid.toString()).executeUpdate()
        }.flatMap {
            session.createNativeQuery<Any>("delete from treasury_payment_flows where payment_id = :p")
                .setParameter("p", pid).executeUpdate()
        }.flatMap {
            session.createNativeQuery<Any>(
                "update treasury_payments set status = 'CANCELLED', status_reason = :r where payment_id = :p and status = 'PROPOSED'",
            ).setParameter("r", "deal $dealId changed").setParameter("p", pid).executeUpdate()
        }.flatMap { n ->
            check(n == 1) { "payment $pid changed while its deal was being changed" }
            outbox.persistInTransaction(
                OutboxMessage(
                    aggregateId = pid,
                    eventType = "treasury.payment.status-changed.v1",
                    payload = objectMapper.writeValueAsString(
                        mapOf(
                            "paymentId" to pid,
                            "status" to "CANCELLED",
                            "reason" to "deal $dealId changed",
                            "occurredAt" to clock.instant(),
                        ),
                    ),
                    createdAt = clock.instant(),
                ),
            )
        }
    }

    /**
     * ADR-0344 D7: an approved correction, in ONE transaction under the deal-row lock (see
     * [CorrectionCommit]). [correctionRow] writes the correction's own row in the same transaction.
     */
    fun commitCorrection(c: CorrectionCommit, correctionRow: () -> Uni<Void>): Uni<Void> = Panache.withTransaction {
        val deal = c.deal
        find("dealId", deal.id).withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).firstResult()
            .flatMap { locked ->
                checkNotNull(locked) { "deal ${deal.id} disappeared" }
                if (locked.dealVersion != c.correction.baseVersion ||
                    locked.scheduleVersion != c.correction.baseScheduleVersion
                ) {
                    throw CorrectionStaleException(
                        "deal ${deal.id} changed while the correction was being approved: propose it again",
                    )
                }
                Panache.getSession().flatMap { session ->
                    session.createNativeQuery<Any>(FINGERPRINT_SQL).setParameter("d", deal.id)
                        .setParameter("v", c.correction.baseScheduleVersion).singleResult
                        .flatMap { fp ->
                            if ((fp as Number).toLong() != c.baseFingerprint) {
                                throw CashFlowConflictException(
                                    "the deal's schedule changed (a fixing, override or payment landed) while the " +
                                        "correction was being approved: approve it again",
                                )
                            }
                            blockingOnChanged(session, deal.id, c.changedFlowIds)
                        }
                        .flatMap {
                            c.exposureGuard?.let { g -> exposureUnderLock(session, deal.id, g) }
                                ?: Uni.createFrom().voidItem()
                        }
                        .flatMap { proposedOnChanged(session, c.changedFlowIds) }
                        .flatMap { pids -> cancelProposed(session, deal.id, pids) }
                        .flatMap { writeCorrection(session, locked, c, correctionRow) }
                }
            }
    }

    /**
     * The headroom a limit-consuming write (submit, approve, correction approval) was vetted against,
     * re-measured inside its transaction under a transaction-scoped lock on the (counterparty, limit
     * currency) line — ONE key for every such write, so two writes on one line serialise and cannot
     * jointly breach — by the same [lineExposure] query [exposure] answers with. A breach meanwhile
     * is a 422, never a committed breach.
     */
    private fun exposureUnderLock(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        dealId: java.util.UUID,
        g: com.openbank.treasury.application.port.out.ExposureGuard,
    ): Uni<Void> = session.createNativeQuery<Any>("select pg_advisory_xact_lock(hashtext(:line))")
        .setParameter("line", "treasury-limit:${g.counterpartyId}:${g.currency}").singleResult
        .flatMap { lineExposure(session, g.counterpartyId, g.currency, dealId) }
        .map { exposure ->
            if (exposure > g.maxOthers) {
                throw com.openbank.treasury.application.port.out.CorrectionLimitBreachedException(
                    "counterparty ${g.counterpartyId} ${g.currency}: other exposure $exposure exceeds the " +
                        "${g.maxOthers} headroom the change was vetted against",
                )
            }
        }.replaceWithVoid()

    /**
     * The limit-consuming exposure of one (counterparty, LIMIT currency) line, [excludeDealId] aside:
     * the principal of ACTUAL asset deals in that currency in a consuming state, plus — on the CZK
     * line — the CZK counter-amount of unsettled ACTUAL FX spots ([Deal.limitAmount] of each).
     */
    private fun lineExposure(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        counterpartyId: String,
        currency: String,
        excludeDealId: UUID?,
    ): Uni<BigDecimal> {
        val fxPart = if (currency == Deal.CZK) {
            " + coalesce((select sum(fx_counter_amount) from deals where counterparty_id = :cp " +
                "and product = 'FX_SPOT' and state in (:fxStates) and kind = 'ACTUAL' and deal_id <> :d), 0)"
        } else {
            ""
        }
        val q = session.createNativeQuery<Any>(
            "select coalesce((select sum(principal) from deals where counterparty_id = :cp " +
                "and currency = :ccy and state in (:states) and product in (:products) " +
                "and kind = 'ACTUAL' and deal_id <> :d), 0)" +
                fxPart,
        ).setParameter("cp", counterpartyId).setParameter("ccy", currency)
            .setParameter("d", excludeDealId ?: NO_DEAL)
            .setParameter("states", LIMIT_CONSUMING_STATE_NAMES)
            .setParameter("products", LIMIT_CONSUMING_PRODUCT_NAMES)
        if (fxPart.isNotEmpty()) q.setParameter("fxStates", Deal.FX_LIMIT_CONSUMING_STATES.map { it.name })
        return q.singleResult.map { BigDecimal(it.toString()) }
    }

    /** The writes of an approved correction, after the guards passed and the payments were withdrawn. */
    private fun writeCorrection(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        locked: DealEntity,
        c: CorrectionCommit,
        correctionRow: () -> Uni<Void>,
    ): Uni<Void> {
        val deal = c.deal
        locked.fill(deal)
        locked.lastChangedBy = c.correction.decidedBy?.id
        return transitions.count("dealId", deal.id)
            .flatMap { stored -> appendTransitions(deal, stored.toInt()) }
            .flatMap { persistCommand(c.command) }
            .flatMap { correctionVersionRow(c) }
            .flatMap { c.schedule?.let { scheduleRows(it) } ?: Uni.createFrom().voidItem() }
            // The new rows must be in the database before the native updates below read them.
            .flatMap { session.flush() }
            .flatMap { repointPayments(session, deal.id, c) }
            .flatMap { correctionRow() }
            .flatMap {
                c.events.fold(Uni.createFrom().voidItem()) { chain, e ->
                    chain.flatMap {
                        outbox.persistInTransaction(
                            OutboxMessage(
                                aggregateId = deal.id,
                                eventType = e.eventType,
                                payload = e.payload,
                                createdAt = clock.instant(),
                            ),
                        )
                    }
                }
            }
    }

    /**
     * The payments that survive the correction follow their flows onto the new schedule version.
     * The payment link is never written by a whole-row save, so it is carried here by its own
     * scoped update, slot for slot: from every UNCHANGED flow still on a payment (a withdrawn one was
     * unlinked above), and from a CHANGED flow whose payment already settled (posted history). Then
     * the unchanged flows' payment rows are re-pointed, so a PROPOSED one stays releasable and a
     * released one keeps settling the rows the deal now has.
     */
    private fun repointPayments(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        dealId: java.util.UUID,
        c: CorrectionCommit,
    ): Uni<Void> {
        val schedule = c.schedule ?: return Uni.createFrom().voidItem()
        val changed = c.changedFlowIds.ifEmpty { setOf(java.util.UUID(0, 0)) }.toList()
        val slot = "n.leg_index = o.leg_index and n.period_index = o.period_index and n.kind = o.kind"
        return session.createNativeQuery<Any>(
            "update deal_cash_flows n set payment_id = o.payment_id, row_version = n.row_version + 1 " +
                "from deal_cash_flows o where o.deal_id = :d and o.schedule_version = :base and " +
                "n.deal_id = :d and n.schedule_version = :v and $slot and o.payment_id is not null and " +
                "(o.flow_id not in (:changed) or exists (select 1 from treasury_payments p where " +
                "cast(p.payment_id as varchar) = o.payment_id and p.status in ('SETTLED', 'MANUALLY_SETTLED')))",
        ).setParameter("d", dealId).setParameter("base", c.correction.baseScheduleVersion)
            .setParameter("v", schedule.scheduleVersion).setParameter("changed", changed).executeUpdate()
            .flatMap {
                session.createNativeQuery<Any>(
                    "update treasury_payment_flows pf set flow_id = n.flow_id from deal_cash_flows n " +
                        "where pf.deal_id = :d and n.deal_id = :d and n.schedule_version = :v " +
                        "and n.leg_index = pf.leg_index and n.period_index = pf.period_index and n.kind = pf.kind " +
                        "and n.payment_id = cast(pf.payment_id as varchar) and pf.flow_id not in (:changed)",
                ).setParameter("d", dealId).setParameter("v", schedule.scheduleVersion)
                    .setParameter("changed", changed).executeUpdate()
            }.flatMap {
                // ADR-0344 D7: an unchanged observed fixing keeps its provenance (source, hash, time);
                // the provenance columns, like the payment link, are only ever written by scoped updates.
                session.createNativeQuery<Any>(
                    "update deal_cash_flows n set fixing_source = o.fixing_source, fixing_sha256 = o.fixing_sha256, " +
                        "fixed_at = o.fixed_at, row_version = n.row_version + 1 from deal_cash_flows o " +
                        "where o.deal_id = :d and o.schedule_version = :base and n.deal_id = :d and " +
                        "n.schedule_version = :v and $slot and o.fixing_sha256 is not null and " +
                        "n.rate_source = 'OBSERVED' and n.fixing_date = o.fixing_date",
                ).setParameter("d", dealId).setParameter("base", c.correction.baseScheduleVersion)
                    .setParameter("v", schedule.scheduleVersion).executeUpdate()
            }.replaceWithVoid()
    }

    /** Σ row_version + count over one schedule version's flows (see CorrectionRepository.fingerprint). */
    fun fingerprint(dealId: java.util.UUID, scheduleVersion: Int): Uni<Long> = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createNativeQuery<Any>(FINGERPRINT_SQL).setParameter("d", dealId).setParameter("v", scheduleVersion)
                .singleResult.map { (it as Number).toLong() }
        }
    }

    private fun blockingOnChanged(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        dealId: java.util.UUID,
        changed: Set<java.util.UUID>,
    ): Uni<Void> {
        if (changed.isEmpty()) return Uni.createFrom().voidItem()
        return session.createNativeQuery<Array<Any>>(
            "select cast(p.payment_id as varchar), cast(f.flow_id as varchar) from treasury_payments p " +
                "join treasury_payment_flows f on f.payment_id = p.payment_id " +
                "where f.flow_id in (:f) and p.status in (:blocking)",
        ).setParameter("f", changed.toList()).setParameter("blocking", RELEASED_STATUSES).resultList.map { rows ->
            rows.firstOrNull()?.let {
                throw CorrectionBlockedByPaymentException(
                    dealId,
                    java.util.UUID.fromString(it[0].toString()),
                    java.util.UUID.fromString(it[1].toString()),
                )
            }
        }.replaceWithVoid()
    }

    private fun proposedOnChanged(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        changed: Set<java.util.UUID>,
    ): Uni<List<java.util.UUID>> {
        if (changed.isEmpty()) return Uni.createFrom().item(emptyList())
        return session.createNativeQuery<Any>(
            "select cast(p.payment_id as varchar) from treasury_payments p where p.status = 'PROPOSED' and " +
                "p.payment_id in (select f.payment_id from treasury_payment_flows f where f.flow_id in (:f)) " +
                "order by 1 for update",
        ).setParameter("f", changed.toList()).resultList.map { r -> r.map { java.util.UUID.fromString(it.toString()) } }
    }

    /** The correction's terms version: authored by its PROPOSER (a term author for every later release). */
    private fun correctionVersionRow(c: CorrectionCommit): Uni<Void> = versions.persist(
        DealVersionEntity().apply {
            dealId = c.deal.id
            version = c.deal.version
            termsJson = objectMapper.writeValueAsString(terms(c.deal))
            scheduleVersion = c.deal.scheduleVersion.takeIf { it > 0 }
            scheduleHash = c.deal.scheduleHash
            createdBy = c.correction.proposedBy.id
            createdAt = clock.instant()
            changes = "correction ${c.correction.correctionId} approved by ${c.correction.decidedBy?.id}: " +
                c.correction.reason.take(MAX_CHANGES_REASON)
        },
    ).replaceWithVoid()

    private fun scheduleRows(s: ScheduleWrite): Uni<Void> {
        val flows = s.flows.map { CashFlowEntity().fill(it) }
        val conflicts = s.conflicts.map { CashFlowConflictEntity().fill(it) }
        val touched = (s.carried + s.conflicted).associateBy { it.overrideId }
        return cashFlows.persist(flows)
            .flatMap { if (conflicts.isEmpty()) Uni.createFrom().voidItem() else conflictRepo.persist(conflicts) }
            .flatMap {
                if (touched.isEmpty()) {
                    Uni.createFrom().voidItem()
                } else {
                    overrideRepo.find("overrideId in ?1", touched.keys.toList()).list().map { rows ->
                        rows.forEach { row -> touched[row.overrideId]?.let { row.fill(it) } }
                    }.replaceWithVoid()
                }
            }
    }

    private fun legsOf(row: DealEntity): List<LegSpec> =
        row.legsJson?.let { objectMapper.readValue(it, LEGS_TYPE) } ?: emptyList()

    private fun headerOf(row: DealEntity): DealHeader =
        row.headerJson?.let { objectMapper.readValue(it, DealHeader::class.java) } ?: DealHeader.EMPTY

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

    internal fun persistJournal(ref: LedgerJournalRef): Uni<Void> = journals.persist(
        DealJournalEntity().apply {
            dealId = ref.dealId
            event = ref.event.name
            idempotencyKey = ref.idempotencyKey
            journalId = ref.journalId
            postedAt = ref.postedAt
            resolvedAccounts = ResolvedAccountsCodec.encode(ref.accounts)
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
            find(
                "state in ?1 and valueDate <= ?2 and kind = ?3 and product in ?4 order by valueDate asc",
                states.map { it.name },
                today,
                DealKind.ACTUAL.name,
                POSTABLE_PRODUCT_NAMES,
            ).list()
        }.awaitSuspending(),
    )

    override suspend fun dueForMaturity(today: LocalDate): List<Deal> = withHistories(
        Panache.withSession {
            // An FX spot is final once SETTLED (maturityDate == valueDate); it never matures.
            find(
                "state = ?1 and maturityDate <= ?2 and product <> ?3 and kind = ?4 and product in ?5 order by maturityDate asc",
                DealState.SETTLED.name,
                today,
                ProductType.FX_SPOT.name,
                DealKind.ACTUAL.name,
                POSTABLE_PRODUCT_NAMES,
            ).list()
        }.awaitSuspending(),
    )

    override suspend fun exposure(counterpartyId: String, currency: String, excludeDealId: UUID?): BigDecimal =
        Panache.withSession {
            Panache.getSession().flatMap { lineExposure(it, counterpartyId, currency, excludeDealId) }
        }.awaitSuspending()

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
        LedgerJournalRef(
            it.dealId,
            PostingEvent.valueOf(it.event),
            it.idempotencyKey,
            it.journalId,
            it.postedAt,
            ResolvedAccountsCodec.decode(it.resolvedAccounts),
        )
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
        kind = deal.kind.name
        bookId = deal.bookId
        // The legacy products derive their legs from the flat fields on read: nothing stored that could drift.
        legsJson = if (deal.product.legacy) null else objectMapper.writeValueAsString(deal.legs)
        legSchemaVersion = if (deal.product.legacy) null else LegSpec.SCHEMA_VERSION
        headerJson = if (deal.header == DealHeader.EMPTY) null else objectMapper.writeValueAsString(deal.header)
        headerSchemaVersion = if (deal.header == DealHeader.EMPTY) null else DealHeader.SCHEMA_VERSION
        dealVersion = deal.version
        scheduleVersion = deal.scheduleVersion
        scheduleHash = deal.scheduleHash
        securityIsin = deal.header.securityTerms?.securityIsin ?: deal.header.collateral?.securityIsin
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
            kind = DealKind.valueOf(kind),
            legs = legsOf(this),
            header = headerOf(this),
            version = dealVersion,
            scheduleVersion = scheduleVersion,
            scheduleHash = scheduleHash,
        ).let { d -> if (d.product.legacy) d.copy(legs = LegacyLegs.derive(d)) else d }
    }

    private companion object {
        /** Derived from [PaymentStatus.BLOCKING_DEAL_CHANGE] — never a second literal list. */
        val RELEASED_STATUSES = com.openbank.treasury.domain.payment.PaymentStatus.BLOCKING_DEAL_CHANGE_NAMES

        /** Derived from [Deal.LIMIT_CONSUMING_STATES] — never a second, independently-typed literal list. */
        val LIMIT_CONSUMING_STATE_NAMES = Deal.LIMIT_CONSUMING_STATES.map { it.name }

        /** No deal excluded: a UUID no deal carries, so `deal_id <> :d` binds a typed value. */
        val NO_DEAL: UUID = UUID(0L, 0L)
        val LIMIT_CONSUMING_PRODUCT_NAMES = ProductType.entries.filter { it.isAsset }.map { it.name }

        /** The products with posting rules today: the only ones the simulated market moves. */
        val POSTABLE_PRODUCT_NAMES = ProductType.entries.filter { it.legacy }.map { it.name }

        val LEGS_TYPE = object : TypeReference<List<LegSpec>>() {}

        const val MAX_CHANGES_REASON = 200
        const val FINGERPRINT_SQL =
            "select coalesce(sum(row_version), 0) + count(*) from deal_cash_flows where deal_id = :d and schedule_version = :v"
    }
}
