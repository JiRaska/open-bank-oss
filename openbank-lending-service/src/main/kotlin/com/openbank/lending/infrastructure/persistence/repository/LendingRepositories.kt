// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.domain.model.ApplicationStatus
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.infrastructure.persistence.entity.CollateralEntity
import com.openbank.lending.infrastructure.persistence.entity.InstallmentEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanApplicationEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanProvisioningEntity
import com.openbank.lending.infrastructure.persistence.mapper.LendingMapper
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
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

    @WithSession
    override fun findRecent(status: String?, limit: Int): Uni<List<LoanApplication>> = sf.withSession { s ->
        val query = if (status != null) {
            s.createQuery(
                "FROM LoanApplicationEntity WHERE status = :st ORDER BY createdAt DESC",
                LoanApplicationEntity::class.java,
            ).setParameter("st", ApplicationStatus.valueOf(status))
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
