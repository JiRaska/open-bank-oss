// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.infrastructure.persistence.entity.CollateralEntity
import com.openbank.lending.infrastructure.persistence.entity.InstallmentEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanApplicationEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanEntity
import com.openbank.lending.infrastructure.persistence.mapper.LendingMapper
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

    @WithSession override fun findByLoan(loanId: LoanId): Uni<List<Collateral>> = sf.withSession { s ->
        s.createQuery(
            "FROM CollateralEntity WHERE loanId = :l ORDER BY createdAt DESC",
            CollateralEntity::class.java,
        )
            .setParameter("l", loanId.value).resultList
    }.map { it.map(mapper::toDomain) }
}
