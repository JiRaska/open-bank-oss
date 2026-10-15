// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.maintenance.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.maintenance.ContractChangeStore
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationHistory
import com.openbank.pension.domain.maintenance.BeneficiaryDesignationVersion
import com.openbank.pension.domain.maintenance.ContributionScheduleHistory
import com.openbank.pension.domain.maintenance.ScheduleVersion
import com.openbank.pension.domain.maintenance.ScheduleVersionStatus
import com.openbank.pension.domain.model.ContributionFrequency
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.infrastructure.exit.persistence.lockContractRow
import com.openbank.pension.infrastructure.persistence.entity.PensionContractEntity
import com.openbank.pension.infrastructure.persistence.repository.ConcurrentContractUpdateException
import com.openbank.pension.infrastructure.persistence.repository.PensionContractRepositoryImpl.BeneficiaryRow
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Every column named explicitly (entity-column-names): this service sets no naming strategy. */
@Entity
@Table(name = "pension_contribution_schedule_changes")
class ScheduleChangeEntity : PanacheEntity() {
    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "seq", nullable = false)
    var seq: Int = 0

    @Column(name = "amount", nullable = false)
    lateinit var amount: BigDecimal

    @Column(name = "employer_amount", nullable = false)
    lateinit var employerAmount: BigDecimal

    @Column(name = "currency", nullable = false)
    lateinit var currency: String

    @Column(name = "frequency", nullable = false)
    lateinit var frequency: String

    @Column(name = "day_of_month", nullable = false)
    var dayOfMonth: Int = 0

    @Column(name = "effective_from", nullable = false)
    lateinit var effectiveFrom: LocalDate

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "document_sha256", nullable = false)
    lateinit var documentSha256: String

    @Column(name = "sca_challenge_id", nullable = false)
    lateinit var scaChallengeId: String

    @Column(name = "idempotency_key", nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "changed_at", nullable = false)
    lateinit var changedAt: Instant
}

@Entity
@Table(name = "pension_beneficiary_designations")
class BeneficiaryDesignationEntity : PanacheEntity() {
    @Column(name = "contract_id", nullable = false)
    lateinit var contractId: UUID

    @Column(name = "seq", nullable = false)
    var seq: Int = 0

    @Column(name = "beneficiaries", nullable = false)
    lateinit var beneficiaries: String

    @Column(name = "document_sha256", nullable = false)
    lateinit var documentSha256: String

    @Column(name = "sca_challenge_id", nullable = false)
    lateinit var scaChallengeId: String

    @Column(name = "idempotency_key", nullable = false)
    lateinit var idempotencyKey: String

    @Column(name = "changed_at", nullable = false)
    lateinit var changedAt: Instant
}

@ApplicationScoped
class ScheduleChangeRepository : PanacheRepository<ScheduleChangeEntity>

@ApplicationScoped
class BeneficiaryDesignationRepository : PanacheRepository<BeneficiaryDesignationEntity>

/** The contract row, for the designation write only (its own repository: one entity per class). */
@ApplicationScoped
class ContractRowRepository : PanacheRepository<PensionContractEntity>

/**
 * [ContractChangeStore] over Hibernate Reactive. A concurrent second change computes the same next
 * `seq` and loses on the `(contract_id, seq)` unique constraint (409, nothing written); the
 * designation write is additionally optimistic-locked on the contract row.
 */
@ApplicationScoped
class ContractChangeStoreImpl(
    private val schedules: ScheduleChangeRepository,
    private val designations: BeneficiaryDesignationRepository,
    private val contractRows: ContractRowRepository,
    private val objectMapper: ObjectMapper,
) : ContractChangeStore {

    override suspend fun scheduleHistory(contractId: UUID): ContributionScheduleHistory = Panache.withSession {
        schedules.find("contractId = ?1 order by seq", contractId).list()
    }.awaitSuspending().let { rows -> ContributionScheduleHistory(contractId, rows.map { it.toDomain() }) }

    override suspend fun saveSchedule(history: ContributionScheduleHistory): ContributionScheduleHistory {
        Panache.withTransaction {
            lockContractRow(history.contractId).flatMap {
                schedules.find("contractId = ?1 order by seq", history.contractId).list()
            }.flatMap { stored ->
                // Under the contract lock: the history this change was planned on must still be the
                // stored one (exactly one new version on top of it). A concurrent change that
                // committed first makes this a 409 here — not only via the (contract_id, seq) key.
                if (stored.size != history.latestSeq - 1) {
                    throw ConcurrentContractUpdateException(history.contractId, stored.size, history.latestSeq - 1)
                }
                val bySeq = stored.associateBy { it.seq }
                history.versions.filter { it.seq in bySeq }.forEach { v ->
                    // The only mutation history allows: SCHEDULED -> SUPERSEDED.
                    val row = bySeq.getValue(v.seq)
                    if (row.status != v.status.name) {
                        check(v.status == ScheduleVersionStatus.SUPERSEDED) { "a schedule version cannot be revived" }
                        row.status = v.status.name
                    }
                }
                val fresh = history.versions.filter { it.seq !in bySeq }.map { it.toEntity(history.contractId) }
                (if (fresh.isEmpty()) Uni.createFrom().voidItem() else schedules.persist(fresh))
                    .flatMap { Panache.getSession() }
                    .flatMap { it.flush() }
            }
        }.awaitSuspending()
        return history
    }

    override suspend fun beneficiaryHistory(contractId: UUID): BeneficiaryDesignationHistory = Panache.withSession {
        designations.find("contractId = ?1 order by seq", contractId).list()
    }.awaitSuspending().let { rows ->
        BeneficiaryDesignationHistory(
            contractId,
            rows.map {
                BeneficiaryDesignationVersion(
                    seq = it.seq,
                    beneficiaries = objectMapper.readValue<List<BeneficiaryRow>>(it.beneficiaries).map { r ->
                        r.toDomain()
                    },
                    documentSha256 = it.documentSha256,
                    scaChallengeId = it.scaChallengeId,
                    idempotencyKey = it.idempotencyKey,
                    changedAt = it.changedAt,
                )
            },
        )
    }

    override suspend fun saveBeneficiaries(contract: PensionContract, version: BeneficiaryDesignationVersion) {
        val json = objectMapper.writeValueAsString(
            version.beneficiaries.map { BeneficiaryRow(it.name, it.partyId?.toString(), it.sharePercent) },
        )
        Panache.withTransaction {
            // 1. Row lock on the contract: the check and the write below are one critical section,
            //    serialised against a concurrent designation AND against a death-claim insert
            //    (every exit write takes the same lock, see ExitPersistence.lockContractRow).
            lockContractRow(contract.id)
                .flatMap { contractRows.find("contractId", contract.id).firstResult() }
                .flatMap { row ->
                    checkNotNull(row) { "contract ${contract.id} disappeared" }
                    // 2. Optimistic version re-checked UNDER the lock: a designation that won the
                    //    race bumped row_version, so the loser is a 409 and writes nothing.
                    if (row.rowVersion != contract.version) {
                        throw ConcurrentContractUpdateException(contract.id, row.rowVersion, contract.version)
                    }
                    // 3. Death claim read under the same lock: a claim registered first always wins.
                    Panache.getSession().flatMap { session ->
                        session.createNativeQuery<Long>(
                            "select count(*) from pension_death_claims where contract_id = :id",
                            Long::class.javaObjectType,
                        ).setParameter("id", contract.id).singleResult
                    }.flatMap { claims ->
                        check(claims == 0L) { "beneficiaries cannot change once a death claim is registered" }
                        row.beneficiaries = json
                        row.updatedAt = contract.updatedAt
                        // 4. History row (with its unique (contract_id, idempotency_key)) in the same
                        //    transaction: designation, history and key commit together or not at all.
                        designations.persist(
                            BeneficiaryDesignationEntity().apply {
                                contractId = contract.id
                                seq = version.seq
                                beneficiaries = json
                                documentSha256 = version.documentSha256
                                scaChallengeId = version.scaChallengeId
                                idempotencyKey = version.idempotencyKey
                                changedAt = version.changedAt
                            },
                        )
                    }
                }
                .flatMap { Panache.getSession() }
                .flatMap { it.flush() }
        }.awaitSuspending()
    }

    private fun ScheduleChangeEntity.toDomain() = ScheduleVersion(
        seq = seq,
        amount = amount,
        employerAmount = employerAmount,
        currency = currency,
        frequency = ContributionFrequency.valueOf(frequency),
        dayOfMonth = dayOfMonth,
        effectiveFrom = effectiveFrom,
        status = ScheduleVersionStatus.valueOf(status),
        documentSha256 = documentSha256,
        scaChallengeId = scaChallengeId,
        idempotencyKey = idempotencyKey,
        changedAt = changedAt,
    )

    private fun ScheduleVersion.toEntity(id: UUID) = ScheduleChangeEntity().also {
        it.contractId = id
        it.seq = seq
        it.amount = amount
        it.employerAmount = employerAmount
        it.currency = currency
        it.frequency = frequency.name
        it.dayOfMonth = dayOfMonth
        it.effectiveFrom = effectiveFrom
        it.status = status.name
        it.documentSha256 = documentSha256
        it.scaChallengeId = scaChallengeId
        it.idempotencyKey = idempotencyKey
        it.changedAt = changedAt
    }
}
