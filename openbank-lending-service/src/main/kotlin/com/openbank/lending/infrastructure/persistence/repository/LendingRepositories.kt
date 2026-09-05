// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.domain.model.ApplicationStateSummary
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.DecisionOutcomeSummary
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanStateSummary
import com.openbank.lending.domain.model.MoneyTotal
import com.openbank.lending.infrastructure.persistence.entity.CollateralEntity
import com.openbank.lending.infrastructure.persistence.entity.InstallmentEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanApplicationEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanProvisioningEntity
import com.openbank.lending.infrastructure.persistence.mapper.LendingMapper
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.lending.origination.LegacyOriginationMigration
import com.openbank.libs.lending.origination.OriginationState
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class LoanApplicationRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: LendingMapper,
) : LoanApplicationRepository {
    @WithTransaction override fun save(application: LoanApplication): Uni<LoanApplication> {
        val e = mapper.toEntity(application)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession override fun findById(id: LoanApplicationId): Uni<LoanApplication?> =
        sf.withSession { s -> s.find(LoanApplicationEntity::class.java, id.value) }.map { it?.let(mapper::toDomain) }

    @WithSession override fun findByParty(partyId: UUID): Uni<List<LoanApplication>> = sf.withSession { s ->
        s.createQuery(
            "FROM LoanApplicationEntity WHERE partyId = :p ORDER BY createdAt DESC",
            LoanApplicationEntity::class.java,
        )
            .setParameter("p", partyId).resultList
    }.map { it.map(mapper::toDomain) }

    /**
     * `GROUP BY status, currency` — one round trip for the whole book (issue #3294).
     *
     * Grouped by CURRENCY as well as status because `loan_application.currency` is per row: a single
     * summed amount would be CZK added to EUR, which looks authoritative and means nothing. The rows
     * are folded into one entry per status carrying a total per currency.
     *
     * `min(createdAt)` is the oldest item in that state — what the desk should act on first.
     */
    @WithSession
    override fun summariseByState(): Uni<List<ApplicationStateSummary>> = sf.withSession { s ->
        s.createQuery(
            """
            SELECT a.status, a.currency, count(a), min(a.createdAt), sum(a.requestedAmount)
            FROM LoanApplicationEntity a
            GROUP BY a.status, a.currency
            """.trimIndent(),
            Array<Any?>::class.java,
        ).resultList
    }.map { rows -> foldApplicationSummaries(rows) }

    @WithSession
    override fun findEvaluated(limit: Int): Uni<List<LoanApplication>> = sf.withSession { s ->
        s.createQuery(
            "FROM LoanApplicationEntity WHERE decidedEngineAt IS NOT NULL ORDER BY decidedEngineAt DESC, id ASC",
            LoanApplicationEntity::class.java,
        ).setMaxResults(limit).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun summariseDecisions(): Uni<List<DecisionOutcomeSummary>> = sf.withSession { s ->
        s.createQuery(
            """
            SELECT a.decisionOutcome, a.decisionPriceBand, count(a)
            FROM LoanApplicationEntity a
            WHERE a.decisionOutcome IS NOT NULL
            GROUP BY a.decisionOutcome, a.decisionPriceBand
            """.trimIndent(),
            Array<Any?>::class.java,
        ).resultList
    }.map { rows ->
        rows.map { r -> DecisionOutcomeSummary(r[0] as String, r[1] as? String, (r[2] as Number).toLong()) }
    }

    private fun mapStatusFilter(status: String): OriginationState =
        OriginationState.entries.firstOrNull { it.name == status }
            ?: LegacyOriginationMigration.mapLegacyStatus(status, wasSubmitted = true)
            ?: throw IllegalArgumentException("Unknown application status: $status")

    @WithSession
    override fun findRecent(status: String?, limit: Int): Uni<List<LoanApplication>> = sf.withSession { s ->
        val query = if (status != null) {
            s.createQuery(
                "FROM LoanApplicationEntity WHERE status = :st ORDER BY createdAt DESC",
                LoanApplicationEntity::class.java,
            ).setParameter("st", mapStatusFilter(status))
        } else {
            s.createQuery(
                "FROM LoanApplicationEntity ORDER BY createdAt DESC",
                LoanApplicationEntity::class.java,
            )
        }
        query.setMaxResults(limit).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction override fun update(application: LoanApplication): Uni<LoanApplication> = sf.withTransaction { s ->
        s.find(LoanApplicationEntity::class.java, application.id.value).flatMap { e ->
            e!!.status = application.status
            e.decidedBy = application.decidedBy
            e.decisionReason = application.decisionReason
            e.decidedAt = application.decidedAt
            s.persist(e).map { mapper.toDomain(e) }
        }
    }

    /**
     * The origination transitions do NOT go through [update].
     *
     * [update] is a blind write: it stores whatever the caller loaded, whenever the caller gets
     * round to it. Every origination transition reads the row in one transaction ([findById],
     * `@WithSession`), computes the next state from that snapshot in memory, and writes in another
     * — so two `advance` calls arriving together both observe the same status, both compute the
     * same forward edge, both pass the state machine, and both write. A lost update on the money
     * path's approval gate: the status column ends up holding the right value, and the transition
     * has been claimed twice, with two evidence records and two workflow signals for one step
     * (issue #3850). On `decide()` the same window lets two checkers each cast what each believes
     * is the deciding four-eyes vote, and the later write owns `decidedBy`/`decisionReason`.
     *
     * Making the expected state part of the UPDATE closes the window with no lock, no `@Version`
     * column and no schema change: the database evaluates `status = :from` and the assignment in
     * one statement, so exactly one of any number of concurrent callers claims the row and the rest
     * get `0`. `OriginationConcurrentAdvanceIT` measures it; on the previous code it observed both
     * advances accepted in 12 of 12 rounds.
     *
     * Same remedy, same reasoning as `JpaCompliancePackActivationRepository.compareAndSetDecision`.
     */
    @WithTransaction
    override fun compareAndSetStatus(
        id: LoanApplicationId,
        from: OriginationState,
        to: OriginationState,
        decidedBy: String?,
        decisionReason: String?,
        decidedAt: OffsetDateTime?,
    ): Uni<Int> = sf.withTransaction { s ->
        s.createMutationQuery(ADVANCE_HQL)
            .setParameter("to", to)
            .setParameter("decidedBy", decidedBy)
            .setParameter("reason", decisionReason)
            .setParameter("decidedAt", decidedAt)
            .setParameter("id", id.value)
            .setParameter("from", from)
            .executeUpdate()
    }

    private companion object {
        /**
         * The `and status = :from` clause is the whole point — without it this is [update] with
         * extra steps. Named parameters because `decidedBy`, `decisionReason` and `decidedAt` are
         * all nullable and a positional vararg cannot carry a null.
         */
        const val ADVANCE_HQL =
            "update LoanApplicationEntity " +
                "set status = :to, decidedBy = :decidedBy, decisionReason = :reason, decidedAt = :decidedAt " +
                "where id = :id and status = :from"
    }
}

@ApplicationScoped
class LoanRepositoryImpl @Inject constructor(private val sf: Mutiny.SessionFactory, private val mapper: LendingMapper) :
    LoanRepository {
    @WithTransaction override fun save(loan: Loan): Uni<Loan> {
        val e = mapper.toEntity(loan)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession override fun findById(id: LoanId): Uni<Loan?> =
        sf.withSession { s -> s.find(LoanEntity::class.java, id.value) }.map { it?.let(mapper::toDomain) }

    @WithSession override fun findByParty(partyId: UUID): Uni<List<Loan>> = sf.withSession { s ->
        s.createQuery("FROM LoanEntity WHERE partyId = :p ORDER BY disbursedAt DESC", LoanEntity::class.java)
            .setParameter("p", partyId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction override fun update(loan: Loan): Uni<Loan> = sf.withTransaction { s ->
        s.find(LoanEntity::class.java, loan.id.value).flatMap { e ->
            e!!.status = loan.status
            e.version = loan.version
            s.persist(e).map { mapper.toDomain(e) }
        }
    }

    /** `GROUP BY status, currency` over the loan book (issue #3294); see the application
     *  repository's note on why currency is part of the grouping. */
    @WithSession
    override fun summariseByState(): Uni<List<LoanStateSummary>> = sf.withSession { s ->
        s.createQuery(
            """
            SELECT l.status, l.currency, count(l), sum(l.principal)
            FROM LoanEntity l
            GROUP BY l.status, l.currency
            """.trimIndent(),
            Array<Any?>::class.java,
        ).resultList
    }.map { rows -> foldLoanSummaries(rows) }

    @WithSession override fun findRecent(limit: Int): Uni<List<Loan>> = sf.withSession { s ->
        s.createQuery("FROM LoanEntity ORDER BY disbursedAt DESC, id ASC", LoanEntity::class.java)
            .setMaxResults(limit)
            .resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession override fun findActive(limit: Int): Uni<List<Loan>> = sf.withSession { s ->
        s.createQuery(
            "FROM LoanEntity WHERE status = :active ORDER BY disbursedAt ASC, id ASC",
            LoanEntity::class.java,
        )
            .setParameter("active", com.openbank.lending.domain.model.LoanStatus.ACTIVE)
            .setMaxResults(limit)
            .resultList
    }.map { it.map(mapper::toDomain) }
}

@ApplicationScoped
class InstallmentRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: LendingMapper,
) : InstallmentRepository {
    @WithTransaction override fun saveAll(installments: List<LoanInstallment>): Uni<List<LoanInstallment>> {
        val entities = installments.map(mapper::toEntity)
        return sf.withTransaction { s ->
            s.persistAll(*entities.toTypedArray()).map { entities.map(mapper::toDomain) }
        }
    }

    @WithSession override fun findByLoan(loanId: LoanId): Uni<List<LoanInstallment>> = sf.withSession { s ->
        s.createQuery("FROM InstallmentEntity WHERE loanId = :l ORDER BY number ASC", InstallmentEntity::class.java)
            .setParameter("l", loanId.value).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction override fun markPaid(installmentId: UUID, paidAt: OffsetDateTime): Uni<Int> = sf.withTransaction {
            s,
            _,
        ->
        s.createMutationQuery("UPDATE InstallmentEntity SET paid = true, paidAt = :t WHERE id = :id")
            .setParameter("t", paidAt).setParameter("id", installmentId).executeUpdate()
    }

    @WithSession override fun findAccruable(asOf: java.time.LocalDate, limit: Int): Uni<List<LoanInstallment>> =
        sf.withSession { s ->
            // Interest earned but not yet recognized, only on loans still on the books (ACTIVE).
            s.createQuery(
                """
                FROM InstallmentEntity i
                WHERE i.dueDate <= :asOf AND i.paid = false AND i.interestAccrued = false
                  AND EXISTS (SELECT 1 FROM LoanEntity l WHERE l.id = i.loanId AND l.status = :active)
                ORDER BY i.dueDate ASC, i.number ASC
                """.trimIndent(),
                InstallmentEntity::class.java,
            )
                .setParameter("asOf", asOf)
                .setParameter("active", com.openbank.lending.domain.model.LoanStatus.ACTIVE)
                .setMaxResults(limit)
                .resultList
        }.map { it.map(mapper::toDomain) }

    @WithTransaction
    override fun markAccrued(installmentId: UUID, accruedAt: OffsetDateTime): Uni<Int> = sf.withTransaction { s, _ ->
        s.createMutationQuery(
            "UPDATE InstallmentEntity SET interestAccrued = true, accruedAt = :t WHERE id = :id AND interestAccrued = false",
        )
            .setParameter("t", accruedAt).setParameter("id", installmentId).executeUpdate()
    }

    @WithTransaction override fun deleteUnpaid(loanId: LoanId): Uni<Int> = sf.withTransaction { s, _ ->
        s.createMutationQuery("DELETE FROM InstallmentEntity WHERE loanId = :l AND paid = false")
            .setParameter("l", loanId.value).executeUpdate()
    }
}

@ApplicationScoped
class CollateralRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: LendingMapper,
) : CollateralRepository {
    @WithTransaction override fun save(collateral: Collateral): Uni<Collateral> {
        val e = mapper.toEntity(collateral)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }

    @WithSession override fun findById(id: CollateralId): Uni<Collateral?> =
        sf.withSession { s -> s.find(CollateralEntity::class.java, id.value) }.map { it?.let(mapper::toDomain) }

    @WithSession override fun findByLoan(loanId: LoanId): Uni<List<Collateral>> = sf.withSession { s ->
        s.createQuery(
            "FROM CollateralEntity WHERE loanId = :l ORDER BY createdAt DESC",
            CollateralEntity::class.java,
        )
            .setParameter("l", loanId.value).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction override fun update(collateral: Collateral): Uni<Collateral> = sf.withTransaction { s ->
        s.find(CollateralEntity::class.java, collateral.id.value).flatMap { e ->
            e!!.status = collateral.status
            e.decidedBy = collateral.decidedBy
            e.decidedAt = collateral.decidedAt
            s.persist(e).map { mapper.toDomain(e) }
        }
    }
}

@ApplicationScoped
class ProvisioningRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: LendingMapper,
) : ProvisioningRepository {

    // ktlint's standard:function-signature (wants signature+body combined),
    // standard:function-expression-body (wants expression form) and
    // standard:max-line-length (wants it split — combined exceeds 120 chars)
    // disagree for this exact shape: every reformatting ktlint itself proposes
    // (combined, expression-split, block-body) fails a DIFFERENT one of the
    // three. Verified by hand across all three forms; this is a ktlint rule
    // interaction gap, not a real style issue.
    @Suppress("ktlint:standard:function-signature")
    @WithSession
    override fun findLatestBefore(loanId: LoanId, period: String): Uni<LoanProvisioningRecord?> = sf.withSession { s ->
        s.createQuery(
            """
                FROM LoanProvisioningEntity
                WHERE loanId = :l AND period < :p
                ORDER BY period DESC
            """.trimIndent(),
            LoanProvisioningEntity::class.java,
        )
            .setParameter("l", loanId.value)
            .setParameter("p", period)
            .setMaxResults(1)
            .resultList
    }.map { it.firstOrNull()?.let(mapper::toDomain) }

    @WithSession override fun findLatestPerLoan(): Uni<List<LoanProvisioningRecord>> = sf.withSession { s ->
        s.createQuery(
            """
                FROM LoanProvisioningEntity p
                WHERE p.period = (SELECT max(q.period) FROM LoanProvisioningEntity q WHERE q.loanId = p.loanId)
                ORDER BY p.loanId ASC
            """.trimIndent(),
            LoanProvisioningEntity::class.java,
        ).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession override fun findByLoanAndPeriod(loanId: LoanId, period: String): Uni<LoanProvisioningRecord?> =
        sf.withSession { s ->
            s.createQuery(
                "FROM LoanProvisioningEntity WHERE loanId = :l AND period = :p",
                LoanProvisioningEntity::class.java,
            )
                .setParameter("l", loanId.value)
                .setParameter("p", period)
                .setMaxResults(1)
                .resultList
        }.map { it.firstOrNull()?.let(mapper::toDomain) }

    // Known gap (tracked, not fixed here): no catch around a UNIQUE(loan_id, period) violation if a
    // second process/instance ever raced the same insert. The application-level idempotency check
    // (findByLoanAndPeriod in LendingService.provisionOne) makes this vanishingly unlikely with today's
    // single-instance scheduler, but a real fix would map the constraint violation to a benign "already
    // provisioned" outcome rather than letting it surface as an unhandled persistence failure.
    @WithTransaction override fun save(record: LoanProvisioningRecord): Uni<LoanProvisioningRecord> {
        val e = mapper.toEntity(record)
        return sf.withTransaction { s -> s.persist(e).map { mapper.toDomain(e) } }
    }
}

// Column positions of the aggregate SELECTs. Named because the query and the fold that reads it sit
// far apart in this file: `row[4]` says nothing at the point of use, and a column added to the
// SELECT silently shifts every later index.
private const val COL_STATUS = 0
private const val COL_CURRENCY = 1
private const val COL_COUNT = 2
private const val COL_APP_OLDEST = 3
private const val COL_APP_SUM = 4
private const val COL_LOAN_SUM = 3

/**
 * Fold `(status, currency, count, oldest, sum)` tuples into one entry per status.
 *
 * Pure and file-private on purpose: the SQL is only half the answer, and the half that turns rows
 * into a per-currency total is the half that can silently add CZK to EUR. Unit-tested directly
 * (`LendingSummaryFoldTest`) rather than only through a container.
 */
internal fun foldApplicationSummaries(rows: List<Array<Any?>>): List<ApplicationStateSummary> {
    val byStatus = LinkedHashMap<String, MutableList<Array<Any?>>>()
    for (r in rows) byStatus.getOrPut(r[COL_STATUS].toString()) { mutableListOf() }.add(r)
    return byStatus.map { (status, group) ->
        ApplicationStateSummary(
            status = status,
            count = group.sumOf { (it[COL_COUNT] as Number).toLong() },
            oldestCreatedAt = group.mapNotNull { it[COL_APP_OLDEST] as? java.time.OffsetDateTime }.minOrNull(),
            requested = group
                .map {
                    MoneyTotal(
                        it[COL_CURRENCY].toString().trim(),
                        (it[COL_APP_SUM] as? BigDecimal) ?: BigDecimal.ZERO,
                    )
                }
                .sortedBy { it.currency },
        )
    }.sortedBy { it.status }
}

/** As [foldApplicationSummaries], for `(status, currency, count, sum)` loan tuples. */
internal fun foldLoanSummaries(rows: List<Array<Any?>>): List<LoanStateSummary> {
    val byStatus = LinkedHashMap<String, MutableList<Array<Any?>>>()
    for (r in rows) byStatus.getOrPut(r[COL_STATUS].toString()) { mutableListOf() }.add(r)
    return byStatus.map { (status, group) ->
        LoanStateSummary(
            status = status,
            count = group.sumOf { (it[COL_COUNT] as Number).toLong() },
            principal = group
                .map {
                    MoneyTotal(
                        it[COL_CURRENCY].toString().trim(),
                        (it[COL_LOAN_SUM] as? BigDecimal) ?: BigDecimal.ZERO,
                    )
                }
                .sortedBy { it.currency },
        )
    }.sortedBy { it.status }
}
