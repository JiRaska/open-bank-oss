// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.kyc.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.RiskLevel
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "kyc_cases")
class KycCaseEntity : PanacheEntity() {
    @Column(name = "case_id", nullable = false, unique = true)
    lateinit var caseId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "risk_level", nullable = false)
    lateinit var riskLevel: String

    @Column(name = "assigned_to")
    var assignedTo: String? = null

    @Column(name = "checks_json", columnDefinition = "TEXT")
    lateinit var checksJson: String

    @Column(name = "notes", columnDefinition = "TEXT")
    var notes: String? = null

    @Column(name = "reviewed_by")
    var reviewedBy: String? = null

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null

    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@ApplicationScoped
class KycRepository :
    KycCaseRepository,
    PanacheRepository<KycCaseEntity> {

    @Inject lateinit var objectMapper: ObjectMapper

    override suspend fun save(case: KycCase): KycCase {
        Panache.withTransaction { persist(case.toEntity(objectMapper)) }.awaitSuspending()
        return case
    }

    override suspend fun findById(id: UUID): KycCase? =
        Panache.withSession { find("caseId", id).firstResult() }.awaitSuspending()?.toDomain(objectMapper)

    override suspend fun findByPartyId(partyId: UUID): KycCase? = Panache.withSession {
        find("partyId = ?1 ORDER BY createdAt DESC", partyId).firstResult()
    }.awaitSuspending()?.toDomain(objectMapper)

    override suspend fun findActiveByPartyId(partyId: UUID): KycCase? = Panache.withSession {
        find(
            "partyId = ?1 AND status NOT IN ?2 ORDER BY createdAt DESC",
            partyId,
            KycCaseStatus.TERMINAL_NAMES,
        ).firstResult()
    }.awaitSuspending()?.toDomain(objectMapper)

    override suspend fun listAll(page: Int, size: Int): List<KycCase> =
        Panache.withSession { findAll().page(page, size).list() }.awaitSuspending().map { it.toDomain(objectMapper) }

    /** Filter by [status]. Used by the onboarding cockpit funnel view (ADR-0068). */
    override suspend fun listByStatus(status: KycCaseStatus, page: Int, size: Int): List<KycCase> =
        Panache.withSession {
            find("status", status.name).page(page, size).list()
        }.awaitSuspending().map { it.toDomain(objectMapper) }

    override suspend fun countAll(): Long = Panache.withSession { count() }.awaitSuspending()

    /** Count cases in a given [status]. Used for funnel KPI tiles (ADR-0068). */
    override suspend fun countByStatus(status: KycCaseStatus): Long =
        Panache.withSession { count("status", status.name) }.awaitSuspending()

    override suspend fun update(case: KycCase): KycCase = Panache.withTransaction {
        find("caseId", case.id).firstResult().map { e ->
            e?.also {
                it.status = case.status.name
                it.riskLevel = case.riskLevel.name
                it.assignedTo = case.assignedTo
                it.checksJson = objectMapper.writeValueAsString(case.checks)
                it.notes = case.notes
                it.reviewedBy = case.reviewedBy
                it.reviewedAt = case.reviewedAt
                it.updatedAt = case.updatedAt
            }
        }.replaceWith(case)
    }.awaitSuspending()
}

// Mappers kept at file scope (pure functions over an injected ObjectMapper) so the repository
// class stays within detekt's per-class function budget.
private fun KycCase.toEntity(objectMapper: ObjectMapper) = KycCaseEntity().also {
    it.caseId = id
    it.partyId = partyId
    it.status = status.name
    it.riskLevel = riskLevel.name
    it.assignedTo = assignedTo
    it.checksJson = objectMapper.writeValueAsString(checks)
    it.notes = notes
    it.reviewedBy = reviewedBy
    it.reviewedAt = reviewedAt
    it.expiresAt = expiresAt
    it.createdAt = createdAt
    it.updatedAt = updatedAt
}

private fun KycCaseEntity.toDomain(objectMapper: ObjectMapper): KycCase {
    val checks: List<KycCheck> = objectMapper.readValue(
        checksJson,
        objectMapper.typeFactory.constructCollectionType(List::class.java, KycCheck::class.java),
    )
    return KycCase(
        caseId, partyId, KycCaseStatus.valueOf(status), RiskLevel.valueOf(riskLevel),
        assignedTo, checks, notes, reviewedBy, reviewedAt, expiresAt, createdAt, updatedAt,
    )
}
