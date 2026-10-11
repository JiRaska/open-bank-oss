// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pension.application.onboarding.ConcurrentModificationException
import com.openbank.pension.application.onboarding.OnboardingApplicationRepository
import com.openbank.pension.application.onboarding.SuitabilityAssessmentRepository
import com.openbank.pension.application.onboarding.TransactionRunner
import com.openbank.pension.application.onboarding.TransferRequestRepository
import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.OnboardingStatus
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.transfer.TransferRequest
import com.openbank.pension.domain.transfer.TransferStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.asUni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.util.UUID

/** The payload mapper: tolerant of derived getters the domain exposes (they are not state). */
@ApplicationScoped
class PayloadMapper(objectMapper: ObjectMapper) {
    val mapper: ObjectMapper = objectMapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun write(value: Any): String = mapper.writeValueAsString(value)
    fun <T> read(json: String, type: Class<T>): T = mapper.readValue(json, type)
}

/**
 * One reactive transaction around a suspend block. The block runs on the caller's Vert.x context
 * (`Dispatchers.Unconfined`), so a repository's own `Panache.withTransaction` inside it JOINS this
 * transaction instead of opening a second one — that is what makes contract + transfer +
 * application of a signature commit together.
 */
@ApplicationScoped
class PanacheTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        Panache.withTransaction { CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni() }.awaitSuspending()
}

/**
 * Optimistic save shared by the versioned aggregates: the row is read under a row lock, its stored
 * version must equal the aggregate's, and the write bumps it. Two writers that read the same
 * version cannot both win — the second blocks on the lock, then sees the bumped version and gets a
 * [ConcurrentModificationException] (409) instead of silently overwriting the first.
 */
private fun conflict(what: String, id: UUID, stored: Long, expected: Long): Nothing =
    throw ConcurrentModificationException("$what $id changed concurrently (stored v$stored, expected v$expected)")

@ApplicationScoped
class OnboardingApplicationRepositoryImpl(private val json: PayloadMapper) :
    OnboardingApplicationRepository,
    PanacheRepository<OnboardingApplicationEntity> {

    override suspend fun save(application: OnboardingApplication): OnboardingApplication {
        val next = application.version + 1
        Panache.withTransaction {
            find("applicationId", application.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                .flatMap { existing ->
                    if (existing == null) {
                        if (application.version !=
                            0L
                        ) {
                            conflict("onboarding application", application.id, -1, application.version)
                        }
                        persist(
                            OnboardingApplicationEntity().apply {
                                applicationId = application.id
                                createdAt = application.createdAt
                                fill(application, next)
                            },
                        ).replaceWithVoid()
                    } else {
                        if (existing.version != application.version) {
                            conflict("onboarding application", application.id, existing.version, application.version)
                        }
                        existing.fill(application, next)
                        Uni.createFrom().voidItem()
                    }
                }
        }.awaitSuspending()
        return application.copy(version = next)
    }

    private fun OnboardingApplicationEntity.fill(application: OnboardingApplication, next: Long) {
        partyId = application.partyId
        status = application.status.name
        contractId = application.contractId
        transferRequestId = application.transferRequestId
        version = next
        updatedAt = application.updatedAt
        payload = json.write(application.copy(version = next))
    }

    override suspend fun findById(id: UUID): OnboardingApplication? =
        Panache.withSession { find("applicationId", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByStatus(status: OnboardingStatus?, limit: Int): List<OnboardingApplication> =
        Panache.withSession {
            val query = if (status == null) {
                find("order by updatedAt desc")
            } else {
                find("status = ?1 order by updatedAt desc", status.name)
            }
            query.range(0, limit - 1).list()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun findByContract(contractId: UUID): OnboardingApplication? =
        Panache.withSession { find("contractId", contractId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByTransferRequest(transferId: UUID): OnboardingApplication? =
        Panache.withSession { find("transferRequestId", transferId).firstResult() }.awaitSuspending()?.toDomain()

    private fun OnboardingApplicationEntity.toDomain(): OnboardingApplication =
        json.read(payload, OnboardingApplication::class.java).copy(version = version)
}

@ApplicationScoped
class SuitabilityAssessmentRepositoryImpl(private val json: PayloadMapper) :
    SuitabilityAssessmentRepository,
    PanacheRepository<SuitabilityAssessmentEntity> {

    /** Append-only apart from CURRENT -> SUPERSEDED; the answers themselves are never rewritten. */
    override suspend fun save(assessment: SuitabilityAssessment): SuitabilityAssessment {
        Panache.withTransaction {
            find("assessmentId", assessment.id).firstResult().flatMap { existing ->
                if (existing == null) {
                    persist(
                        SuitabilityAssessmentEntity().apply {
                            assessmentId = assessment.id
                            partyId = assessment.partyId
                            applicationId = assessment.applicationId
                            assessedAt = assessment.assessedAt
                            status = assessment.status.name
                            payload = json.write(assessment)
                        },
                    ).replaceWithVoid()
                } else {
                    existing.status = assessment.status.name
                    existing.payload = json.write(assessment)
                    Uni.createFrom().voidItem()
                }
            }
        }.awaitSuspending()
        return assessment
    }

    override suspend fun findById(id: UUID): SuitabilityAssessment? =
        Panache.withSession { find("assessmentId", id).firstResult() }.awaitSuspending()
            ?.let { json.read(it.payload, SuitabilityAssessment::class.java) }
}

@ApplicationScoped
class TransferRequestRepositoryImpl(private val json: PayloadMapper) :
    TransferRequestRepository,
    PanacheRepository<TransferRequestEntity> {

    override suspend fun save(request: TransferRequest): TransferRequest {
        val next = request.version + 1
        Panache.withTransaction {
            find("transferId", request.id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                .flatMap { existing ->
                    if (existing == null) {
                        if (request.version != 0L) conflict("transfer request", request.id, -1, request.version)
                        persist(
                            TransferRequestEntity().apply {
                                transferId = request.id
                                direction = request.direction.name
                                contractId = request.contractId
                                partyId = request.partyId
                                createdAt = request.createdAt
                                fill(request, next)
                            },
                        ).replaceWithVoid()
                    } else {
                        if (existing.version != request.version) {
                            conflict("transfer request", request.id, existing.version, request.version)
                        }
                        existing.fill(request, next)
                        Uni.createFrom().voidItem()
                    }
                }
        }.awaitSuspending()
        return request.copy(version = next)
    }

    private fun TransferRequestEntity.fill(request: TransferRequest, next: Long) {
        status = request.status.name
        version = next
        updatedAt = request.updatedAt
        payload = json.write(request.copy(version = next))
    }

    override suspend fun findById(id: UUID): TransferRequest? =
        Panache.withSession { find("transferId", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findByContract(contractId: UUID): List<TransferRequest> =
        Panache.withSession { find("contractId = ?1 order by createdAt", contractId).list() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun findByStatus(status: TransferStatus?, limit: Int): List<TransferRequest> =
        Panache.withSession {
            val query = if (status == null) {
                find("order by updatedAt desc")
            } else {
                find("status = ?1 order by updatedAt desc", status.name)
            }
            query.range(0, limit - 1).list()
        }.awaitSuspending().map { it.toDomain() }

    private fun TransferRequestEntity.toDomain(): TransferRequest =
        json.read(payload, TransferRequest::class.java).copy(version = version)
}
