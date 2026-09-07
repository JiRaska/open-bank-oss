// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.application.usecase.JournalReversalConflictException
import com.openbank.ledger.domain.model.ControlAccountTieOut
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.domain.model.LedgerScope
import com.openbank.ledger.domain.model.SubLedgerBalance
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.ledger.infrastructure.persistence.entity.JournalEntryEntity
import com.openbank.ledger.infrastructure.persistence.entity.JournalLineEntity
import com.openbank.ledger.infrastructure.persistence.entity.LedgerIdempotencyEntity
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class JournalLineRepository : PanacheRepositoryBase<JournalLineEntity, UUID>

@ApplicationScoped
class LedgerIdempotencyRepository(private val clock: Clock) : PanacheRepositoryBase<LedgerIdempotencyEntity, String> {
    fun persistInTransaction(key: String, journalId: UUID, entryDate: LocalDate): Uni<Void> {
        val e = LedgerIdempotencyEntity().also {
            it.idempotencyKey = key
            it.journalId = journalId
            it.journalEntryDate = entryDate
            it.createdAt = Instant.now(clock)
        }
        return persist(e).replaceWithVoid()
    }
}

@ApplicationScoped
class PanacheJournalRepository(
    private val clock: Clock,
    private val lineRepository: JournalLineRepository,
    private val idempotencyRepository: LedgerIdempotencyRepository,
    private val outboxRepository: LedgerOutboxRepositoryImpl,
) : JournalRepository,
    PanacheRepository<JournalEntryEntity> {

    override suspend fun nextEntryNumber(): Long {
        val value = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery("select nextval('journal_entry_number_seq')", java.lang.Long::class.java)
                    .singleResult
            }
        }.awaitSuspending()
        return (value as Number).toLong()
    }

    override suspend fun findById(id: UUID): JournalEntry? = Panache.withSession {
        find("id", id).firstResult().flatMap { entity ->
            if (entity == null) {
                Uni.createFrom().nullItem<JournalEntry>()
            } else {
                lineRepository.find("journalId = ?1 order by sequence", entity.id).list()
                    .map { lines -> entity.toDomain(lines) }
            }
        }
    }.awaitSuspending()

    override suspend fun findByTransactionId(transactionId: UUID): List<JournalEntry> = Panache.withSession {
        find("transactionId = ?1 order by createdAt desc", transactionId).list()
            .flatMap { entries -> attachLines(entries) }
    }.awaitSuspending()

    override suspend fun findByDateRange(
        from: LocalDate,
        to: LocalDate,
        limit: Int,
        afterId: UUID?,
    ): List<JournalEntry> = Panache.withSession {
        find("entryDate >= ?1 and entryDate <= ?2 order by entryDate desc, createdAt desc", from, to).list()
            .flatMap { all ->
                val startIdx = if (afterId != null) {
                    val i = all.indexOfFirst { it.id == afterId }
                    if (i >= 0) i + 1 else 0
                } else {
                    0
                }
                attachLines(all.drop(startIdx).take(limit))
            }
    }.awaitSuspending()

    override suspend fun findByIdempotencyKey(idempotencyKey: String): JournalEntry? {
        val journalId = Panache.withSession {
            idempotencyRepository.find("idempotencyKey", idempotencyKey).firstResult()
        }.awaitSuspending()?.journalId ?: return null
        return findById(journalId)
    }

    // Persist every outbox message in order, within the caller's transaction (ADR-0039 Phase D:
    // JournalPosted + N AccountBookedChanged must commit atomically with the journal).
    private fun persistOutbox(messages: List<OutboxMessage>): Uni<Void> =
        messages.fold(Uni.createFrom().voidItem() as Uni<Void>) { acc, msg ->
            acc.flatMap { outboxRepository.persistInTransaction(msg).replaceWithVoid() }
        }

    override suspend fun save(entry: JournalEntry, idempotencyKey: String?, outbox: List<OutboxMessage>): JournalEntry =
        Panache.withTransaction {
            persist(entry.toEntity())
                .flatMap { persistLines(entry) }
                .flatMap {
                    if (idempotencyKey != null) {
                        idempotencyRepository.persistInTransaction(idempotencyKey, entry.id, entry.entryDate)
                    } else {
                        Uni.createFrom().voidItem()
                    }
                }
                .flatMap { persistOutbox(outbox) }
                .replaceWith(entry)
        }.awaitSuspending()

    override suspend fun appendOutbox(messages: List<OutboxMessage>): Int = if (messages.isEmpty()) {
        0
    } else {
        Panache.withTransaction {
            persistOutbox(messages).replaceWith(messages.size)
        }.awaitSuspending()
    }

    override suspend fun saveReversal(
        reversal: JournalEntry,
        originalId: UUID,
        originalEntryDate: LocalDate,
        outbox: List<OutboxMessage>,
    ): JournalEntry = Panache.withTransaction {
        // Status guard FIRST, as a conditional update: only a still-POSTED original may be
        // flipped to REVERSED. Two concurrent reversals both read POSTED before either
        // commits (the use-case check cannot see the other transaction); the row lock taken
        // here serialises them and the loser matches 0 rows after the winner commits — so it
        // fails BEFORE persisting a second compensation entry and its AccountBookedChanged
        // deltas (double-credit downstream). Backstop: uq_journal_entries_reversal_of (V12).
        update(
            "status = ?1, version = version + 1 where id = ?2 and entryDate = ?3 and status = ?4",
            JournalStatus.REVERSED.name,
            originalId,
            originalEntryDate,
            JournalStatus.POSTED.name,
        ).flatMap { updated ->
            if (updated == 0) {
                Uni.createFrom().failure(
                    JournalReversalConflictException(
                        "Journal $originalId is not POSTED — already reversed by a concurrent request",
                    ),
                )
            } else {
                persist(reversal.toEntity())
                    .flatMap { persistLines(reversal) }
                    .flatMap { persistOutbox(outbox) }
                    .replaceWith(reversal)
            }
        }
    }.awaitSuspending()

    override suspend fun trialBalance(asOf: LocalDate, scope: LedgerScope): List<TrialBalanceLine> {
        val rows: List<*> = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(trialBalanceSql(scope))
                    .setParameter("asOf", asOf)
                    .resultList
            }
        }.awaitSuspending()
        return rows.map { row ->
            @Suppress("UNCHECKED_CAST")
            val cols = row as Array<Any?>
            TrialBalanceLine(
                glAccountId = cols[0].toUuid(),
                code = cols[1] as String,
                name = cols[2] as String,
                type = GlAccountType.valueOf(cols[3] as String),
                currency = cols[4] as String,
                totalDebit = cols[5].toBig(),
                totalCredit = cols[6].toBig(),
            )
        }
    }

    override suspend fun trialBalanceForPeriod(
        from: LocalDate,
        to: LocalDate,
        scope: LedgerScope,
    ): List<TrialBalanceLine> {
        val rows: List<*> = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(trialBalancePeriodSql(scope))
                    .setParameter("fromDate", from)
                    .setParameter("toDate", to)
                    .resultList
            }
        }.awaitSuspending()
        return rows.map { row ->
            @Suppress("UNCHECKED_CAST")
            val cols = row as Array<Any?>
            TrialBalanceLine(
                glAccountId = cols[0].toUuid(),
                code = cols[1] as String,
                name = cols[2] as String,
                type = GlAccountType.valueOf(cols[3] as String),
                currency = cols[4] as String,
                totalDebit = cols[5].toBig(),
                totalCredit = cols[6].toBig(),
            )
        }
    }

    override suspend fun subLedgerBalances(asOf: LocalDate, subAccountId: UUID?): List<SubLedgerBalance> {
        val sql = if (subAccountId != null) SUB_LEDGER_BALANCE_SQL_FILTERED else SUB_LEDGER_BALANCE_SQL
        val rows: List<*> = Panache.withSession {
            Panache.getSession().flatMap { session ->
                val query = session.createNativeQuery<Any>(sql).setParameter("asOf", asOf)
                if (subAccountId != null) query.setParameter("subAccountId", subAccountId)
                query.resultList
            }
        }.awaitSuspending()
        return rows.map { row ->
            @Suppress("UNCHECKED_CAST")
            val cols = row as Array<Any?>
            SubLedgerBalance(
                subAccountId = cols[0].toUuid(),
                currency = cols[1] as String,
                totalDebit = cols[2].toBig(),
                totalCredit = cols[3].toBig(),
            )
        }
    }

    override suspend fun controlAccountTieOut(controlAccountId: UUID, asOf: LocalDate): List<ControlAccountTieOut> {
        // Two queries per currency: GL aggregate (all lines for the control account) and
        // sub-ledger aggregate (only lines with sub_account_id). The difference is the tie-out delta.
        val glRows: List<*> = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(CONTROL_ACCOUNT_GL_BALANCE_SQL)
                    .setParameter("controlAccountId", controlAccountId)
                    .setParameter("asOf", asOf)
                    .resultList
            }
        }.awaitSuspending()

        val subRows: List<*> = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(CONTROL_ACCOUNT_SUB_LEDGER_SQL)
                    .setParameter("controlAccountId", controlAccountId)
                    .setParameter("asOf", asOf)
                    .resultList
            }
        }.awaitSuspending()

        // CONTROL_ACCOUNT_SUB_LEDGER_SQL cols: [0]=base_currency [1]=sub_account_id [2]=total_debit [3]=total_credit
        @Suppress("UNCHECKED_CAST")
        val subByCurrency = subRows.map { it as Array<Any?> }
            .groupBy { it[0] as String }

        @Suppress("UNCHECKED_CAST")
        return glRows.map { row ->
            val cols = row as Array<Any?>
            val currency = cols[0] as String
            val glDebit = cols[1].toBig()
            val glCredit = cols[2].toBig()
            val glNet = glCredit.subtract(glDebit)

            val lines = (subByCurrency[currency] ?: emptyList()).map { sub ->
                SubLedgerBalance(
                    subAccountId = sub[1].toUuid(),
                    currency = currency,
                    totalDebit = sub[2].toBig(),
                    totalCredit = sub[3].toBig(),
                )
            }
            val subLedgerNet = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) }
            ControlAccountTieOut(
                controlAccountId = controlAccountId,
                currency = currency,
                asOf = asOf,
                glNet = glNet,
                subLedgerNet = subLedgerNet,
                delta = glNet.subtract(subLedgerNet),
                lines = lines,
            )
        }
    }

    private fun persistLines(entry: JournalEntry): Uni<Void> {
        var chain: Uni<Void> = Uni.createFrom().voidItem()
        entry.lines.forEach { line ->
            val lineEntity = line.toEntity()
            chain = chain.flatMap { lineRepository.persist(lineEntity).replaceWithVoid() }
        }
        return chain
    }

    private fun attachLines(entries: List<JournalEntryEntity>): Uni<List<JournalEntry>> {
        if (entries.isEmpty()) return Uni.createFrom().item(emptyList())
        val ids = entries.map { it.id }
        return lineRepository.find("journalId in ?1 order by sequence", ids).list()
            .map { lines ->
                val byJournal = lines.groupBy { it.journalId }
                entries.map { it.toDomain(byJournal[it.id] ?: emptyList()) }
            }
    }

    private fun JournalEntry.toEntity() = JournalEntryEntity().also {
        it.id = id
        it.entryDate = entryDate
        it.entryNumber = entryNumber ?: 0
        it.transactionId = transactionId
        it.valueDate = valueDate
        it.description = description
        it.status = status.name
        it.createdAt = createdAt
        it.createdBy = createdBy
        it.version = version
        it.reversalOf = reversalOf
        it.synthetic = synthetic
    }

    private fun JournalLine.toEntity() = JournalLineEntity().also {
        it.id = id
        it.journalId = journalId
        it.glAccountId = glAccountId
        it.side = if (side == JournalSide.DEBIT) "D" else "C"
        it.amount = amount.amount
        it.currencyCode = amount.currency.code
        it.fxRate = fxRate
        it.baseAmount = baseAmount.amount
        it.baseCurrency = baseAmount.currency.code
        it.sequence = sequence
        it.subAccountId = subAccountId
    }

    private fun JournalEntryEntity.toDomain(lineEntities: List<JournalLineEntity>) = JournalEntry(
        id = id,
        entryNumber = entryNumber,
        transactionId = transactionId,
        entryDate = entryDate,
        valueDate = valueDate,
        description = description,
        status = JournalStatus.valueOf(status),
        lines = lineEntities.map { it.toDomainLine() },
        createdAt = createdAt,
        createdBy = createdBy,
        version = version,
        reversalOf = reversalOf,
        synthetic = synthetic,
    )

    private fun JournalLineEntity.toDomainLine(): JournalLine {
        val txScale = CurrencyCode.of(currencyCode).defaultFractionDigits
        val baseScale = CurrencyCode.of(baseCurrency).defaultFractionDigits
        return JournalLine(
            id = id,
            journalId = journalId,
            glAccountId = glAccountId,
            side = if (side == "D") JournalSide.DEBIT else JournalSide.CREDIT,
            amount = Money.of(amount.setScale(txScale, RoundingMode.HALF_EVEN), currencyCode),
            fxRate = fxRate,
            baseAmount = Money.of(baseAmount.setScale(baseScale, RoundingMode.HALF_EVEN), baseCurrency),
            sequence = sequence,
            subAccountId = subAccountId,
        )
    }

    private fun Any?.toUuid(): UUID = when (this) {
        is UUID -> this
        null -> throw LedgerConflictException("null UUID in trial balance row")
        else -> UUID.fromString(this.toString())
    }

    private fun Any?.toBig(): BigDecimal = when (this) {
        is BigDecimal -> this
        is Number -> BigDecimal(this.toString())
        null -> BigDecimal.ZERO
        else -> BigDecimal(this.toString())
    }

    companion object {
        // Balance queries include BOTH 'POSTED' and 'REVERSED' entries (only 'PENDING' is
        // excluded). A reversal is stored as the original entry (status flipped to REVERSED) plus
        // a compensating POSTED entry with mirrored sides — immutable history, the pair nets to
        // zero. Filtering to POSTED alone drops the original's legs while keeping the reversal's,
        // skewing every touched account by the original's net (issue #939: the deposit-control
        // balance was off by the reversed originals' sum, and the global debit==credit tie-out
        // can't catch it because the surviving reversal entry is internally balanced too).
        private const val BOOKED_STATUSES = "('POSTED', 'REVERSED')"

        /**
         * ADR-0252 population filter, applied on the ENTRY (`journal_entries.synthetic`, V26) and
         * never on the line: a journal balances within itself, so excluding whole entries keeps
         * sum(debits) == sum(credits), while a per-line predicate could keep one leg of a balanced
         * pair. Built from a closed enum, so nothing caller-supplied reaches the SQL text.
         */
        private fun scopeClause(scope: LedgerScope): String = when (scope) {
            LedgerScope.REAL_ONLY -> " and je.synthetic = false"
            LedgerScope.SYNTHETIC_ONLY -> " and je.synthetic = true"
            LedgerScope.ALL -> ""
        }

        private fun trialBalanceSql(scope: LedgerScope) = """
            select ga.id, ga.code, ga.name, ga.type, jl.base_currency,
                   coalesce(sum(case when jl.side = 'D' then jl.base_amount else 0 end), 0) as total_debit,
                   coalesce(sum(case when jl.side = 'C' then jl.base_amount else 0 end), 0) as total_credit
            from journal_lines jl
            join journal_entries je on je.id = jl.journal_id
            join gl_accounts ga on ga.id = jl.gl_account_id
            where je.status in $BOOKED_STATUSES
              and je.entry_date <= :asOf${scopeClause(scope)}
            group by ga.id, ga.code, ga.name, ga.type, jl.base_currency
            order by ga.code
        """.trimIndent()

        // Fiscal-period aggregation behind the entity-level year close (ADR-0078 D5): booked
        // activity with entry_date INSIDE the period, per GL account. Same shape as
        // TRIAL_BALANCE_SQL, but range-bounded instead of cumulative-to-date.
        private fun trialBalancePeriodSql(scope: LedgerScope) = """
            select ga.id, ga.code, ga.name, ga.type, jl.base_currency,
                   coalesce(sum(case when jl.side = 'D' then jl.base_amount else 0 end), 0) as total_debit,
                   coalesce(sum(case when jl.side = 'C' then jl.base_amount else 0 end), 0) as total_credit
            from journal_lines jl
            join journal_entries je on je.id = jl.journal_id
            join gl_accounts ga on ga.id = jl.gl_account_id
            where je.status in $BOOKED_STATUSES
              and je.entry_date >= :fromDate and je.entry_date <= :toDate${scopeClause(scope)}
            group by ga.id, ga.code, ga.name, ga.type, jl.base_currency
            order by ga.code
        """.trimIndent()

        // Per-customer deposit-control sub-ledger (ADR-0039 Phase B). Only booked lines that carry
        // a sub_account_id contribute, grouped by (sub_account_id, base_currency).
        private val SUB_LEDGER_BALANCE_SQL = """
            select jl.sub_account_id, jl.base_currency,
                   coalesce(sum(case when jl.side = 'D' then jl.base_amount else 0 end), 0) as total_debit,
                   coalesce(sum(case when jl.side = 'C' then jl.base_amount else 0 end), 0) as total_credit
            from journal_lines jl
            join journal_entries je on je.id = jl.journal_id
            where je.status in $BOOKED_STATUSES and je.entry_date <= :asOf and jl.sub_account_id is not null
            group by jl.sub_account_id, jl.base_currency
            order by jl.sub_account_id, jl.base_currency
        """.trimIndent()

        private val SUB_LEDGER_BALANCE_SQL_FILTERED = """
            select jl.sub_account_id, jl.base_currency,
                   coalesce(sum(case when jl.side = 'D' then jl.base_amount else 0 end), 0) as total_debit,
                   coalesce(sum(case when jl.side = 'C' then jl.base_amount else 0 end), 0) as total_credit
            from journal_lines jl
            join journal_entries je on je.id = jl.journal_id
            where je.status in $BOOKED_STATUSES and je.entry_date <= :asOf and jl.sub_account_id = :subAccountId
            group by jl.sub_account_id, jl.base_currency
            order by jl.base_currency
        """.trimIndent()

        // Tie-out: GL aggregate for a control account per currency (ALL booked lines).
        private val CONTROL_ACCOUNT_GL_BALANCE_SQL = """
            select jl.base_currency,
                   coalesce(sum(case when jl.side = 'D' then jl.base_amount else 0 end), 0) as total_debit,
                   coalesce(sum(case when jl.side = 'C' then jl.base_amount else 0 end), 0) as total_credit
            from journal_lines jl
            join journal_entries je on je.id = jl.journal_id
            where je.status in $BOOKED_STATUSES
              and je.entry_date <= :asOf
              and jl.gl_account_id = :controlAccountId
            group by jl.base_currency
            order by jl.base_currency
        """.trimIndent()

        // Tie-out: sub-ledger lines for a control account per (currency, sub_account_id).
        private val CONTROL_ACCOUNT_SUB_LEDGER_SQL = """
            select jl.base_currency,
                   jl.sub_account_id,
                   coalesce(sum(case when jl.side = 'D' then jl.base_amount else 0 end), 0) as total_debit,
                   coalesce(sum(case when jl.side = 'C' then jl.base_amount else 0 end), 0) as total_credit
            from journal_lines jl
            join journal_entries je on je.id = jl.journal_id
            where je.status in $BOOKED_STATUSES
              and je.entry_date <= :asOf
              and jl.gl_account_id = :controlAccountId
              and jl.sub_account_id is not null
            group by jl.base_currency, jl.sub_account_id
            order by jl.base_currency, jl.sub_account_id
        """.trimIndent()
    }
}
