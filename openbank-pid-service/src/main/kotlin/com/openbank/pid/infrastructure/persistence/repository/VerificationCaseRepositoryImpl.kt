// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.persistence.repository

import com.openbank.pid.application.port.out.VerificationCaseRepository
import com.openbank.pid.domain.model.VerificationCase
import com.openbank.pid.domain.model.VerificationCaseStatus
import com.openbank.pid.infrastructure.persistence.entity.VerificationCaseEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Persistence for four-eyes identity-verification cases. Every Panache op is wrapped in
 * [Panache.withSession]/[Panache.withTransaction] because these are `suspend` methods and the
 * request-scoped auto-session is not propagated across the coroutine (mirrors PartyRepositoryImpl).
 */
@ApplicationScoped
class VerificationCaseRepositoryImpl :
    VerificationCaseRepository,
    PanacheRepository<VerificationCaseEntity> {

    override suspend fun findById(id: UUID): VerificationCase? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findActiveByDedupKey(dedupKey: String): VerificationCase? = Panache.withSession {
        find("dedupKey = ?1 and status != ?2", dedupKey, VerificationCaseStatus.DECIDED.name).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findLatestDecidedByDedupKey(dedupKey: String): VerificationCase? = Panache.withSession {
        find(
            "dedupKey = ?1 and status = ?2 order by decidedAt desc",
            dedupKey,
            VerificationCaseStatus.DECIDED.name,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun listByStatuses(statuses: List<VerificationCaseStatus>): List<VerificationCase> =
        Panache.withSession {
            find("status in ?1 order by createdAt desc", statuses.map { it.name }).list()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(case: VerificationCase): VerificationCase {
        Panache.withTransaction { persist(VerificationCaseEntity.fromDomain(case)) }.awaitSuspending()
        return case
    }

    override suspend fun update(case: VerificationCase): VerificationCase {
        Panache.withTransaction {
            find("id", case.id).firstResult().map { existing ->
                requireNotNull(existing) { "verification case ${case.id} not found" }.apply {
                    status = case.status.name
                    firstApprover = case.firstApprover
                    firstVerdict = case.firstVerdict?.name
                    firstLinkPartyId = case.firstLinkPartyId
                    firstNotes = case.firstNotes
                    firstAt = case.firstAt
                    secondApprover = case.secondApprover
                    secondAt = case.secondAt
                    finalVerdict = case.finalVerdict?.name
                    finalLinkPartyId = case.finalLinkPartyId
                    decidedAt = case.decidedAt
                    updatedAt = case.updatedAt
                }
            }
        }.awaitSuspending()
        return case
    }
}
