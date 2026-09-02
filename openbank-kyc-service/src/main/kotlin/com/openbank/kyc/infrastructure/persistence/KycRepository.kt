// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.port.out.KycCaseRepository
import com.openbank.kyc.application.port.out.KycOutboxRepository
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycCheck
import com.openbank.kyc.domain.model.KycEvent
import com.openbank.kyc.domain.model.RiskLevel
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.jboss.logging.Logger
import java.math.BigDecimal
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

    // V2 EBA/FATF compliance fields — mapped here solely so GDPR Art. 17 erasure can null them.
    @Column(name = "source_of_funds")
    var sourceOfFunds: String? = null

    @Column(name = "source_of_wealth")
    var sourceOfWealth: String? = null

    @Column(name = "business_purpose")
    var businessPurpose: String? = null

    @Column(name = "expected_turnover")
    var expectedTurnover: BigDecimal? = null

    @Column(name = "pep_declaration")
    var pepDeclaration: Boolean = false

    @Column(name = "beneficial_owner_id")
    var beneficialOwnerId: UUID? = null

    @Column(name = "screening_ref")
    var screeningRef: String? = null

    @Column(name = "escalated_to")
    var escalatedTo: String? = null

    @Column(name = "escalation_reason")
    var escalationReason: String? = null

    @Column(name = "erased_at")
    var erasedAt: Instant? = null
}

@Suppress("TooManyFunctions")
@ApplicationScoped
class KycRepository(private val outboxRepository: KycOutboxRepository) :
    KycCaseRepository,
    PanacheRepository<KycCaseEntity> {

    @Inject lateinit var objectMapper: ObjectMapper

    override suspend fun save(case: KycCase): KycCase {
        Panache.withTransaction { persist(case.toEntity(objectMapper)) }.awaitSuspending()
        return case
    }

    // Aggregate state change + outbox row in ONE transaction (issue #4007): persistInTransaction
    // uses the CALLER's reactive session, so it joins this `Panache.withTransaction` block and the
    // case row and its event commit together. persist() and not merge() here because
    // KycCaseEntity extends PanacheEntity — the @Id is generated, so this is a genuine INSERT;
    // the app-assigned-id trap that forces merge() elsewhere in the fleet does not apply.
    // `case_id` is the business key, not the @Id.
    override suspend fun save(case: KycCase, event: KycEvent): KycCase {
        Panache.withTransaction {
            persist(case.toEntity(objectMapper))
                .flatMap { outboxRepository.persistInTransaction(event.toOutboxMessage(objectMapper)) }
        }.awaitSuspending()
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

    // Projects to party_id only: the reconciliation needs presence, not the aggregate, and
    // hydrating full KycCase rows (each of which parses checksJson) to then discard everything but
    // the id would make a read-only monitoring query the most expensive one in the service.
    // BATCHED, and the batching is the point. The caller's candidate set is bounded only by
    // `page-size x max-pages` (100 x 500 = 50,000 by default), and Hibernate expands `IN :ids`
    // into one bind parameter PER ID. The PostgreSQL wire protocol carries the parameter count
    // as an int16, so a single statement cannot exceed 65,535 binds -- the previous shape sat
    // 15,535 under a hard protocol ceiling on defaults, and the cap warning in
    // OrphanedPartyDetector told operators to raise `max-pages`, which is the one action that
    // walks it over the edge. A register of 66,000 parties, or a max-pages of 700, turned the
    // reconciler into a hard failure with no reading of the code suggesting why.
    //
    // Chunking makes the statement shape independent of the register size: bind count per
    // statement is <= ID_BATCH_SIZE no matter how large the candidate set grows, and the number
    // of statements grows linearly instead. See `idBatches` for the measured guarantee.
    override suspend fun findPartyIdsWithAnyCase(partyIds: Collection<UUID>): Set<UUID> {
        if (partyIds.isEmpty()) return emptySet()
        val found = mutableSetOf<UUID>()
        for (batch in idBatches(partyIds)) {
            found += Panache.withSession {
                Panache.getSession().flatMap { session ->
                    session.createQuery(PARTY_IDS_WITH_CASE_HQL, UUID::class.java)
                        .setParameter("ids", batch)
                        .resultList
                }
            }.awaitSuspending()
        }
        return found
    }

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

    override suspend fun update(case: KycCase): KycCase =
        Panache.withTransaction { applyUpdate(case) }.awaitSuspending()

    /** Transactional outbox (issue #4007) — the UPDATE and the event row share one transaction. */
    override suspend fun update(case: KycCase, event: KycEvent): KycCase = Panache.withTransaction {
        applyUpdate(case).flatMap { updated ->
            outboxRepository.persistInTransaction(event.toOutboxMessage(objectMapper)).replaceWith(updated)
        }
    }.awaitSuspending()

    private fun applyUpdate(case: KycCase): Uni<KycCase> = find("caseId", case.id).firstResult().map { e ->
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

    override suspend fun anonymizeByPartyId(partyId: UUID, now: Instant) {
        Panache.withTransaction {
            find("partyId", partyId).list().map { cases ->
                cases.forEach { c ->
                    // V1 PII fields
                    c.assignedTo = null
                    c.notes = null
                    c.reviewedBy = null
                    c.checksJson = anonymizeChecksJson(c.checksJson, objectMapper)
                    // V2 EBA/FATF compliance PII fields
                    c.sourceOfFunds = null
                    c.sourceOfWealth = null
                    c.businessPurpose = null
                    c.expectedTurnover = null
                    c.pepDeclaration = false
                    c.beneficialOwnerId = null
                    c.screeningRef = null
                    c.escalatedTo = null
                    c.escalationReason = null
                    // ADR-0118 §5: record when PII was erased so the retention job can delete after 5y
                    if (c.erasedAt == null) c.erasedAt = now
                }
            }
        }.awaitSuspending()
    }

    override suspend fun deleteErasedCasesOlderThan(cutoff: Instant): Long = Panache.withTransaction {
        delete("erasedAt IS NOT NULL AND erasedAt < ?1", cutoff)
    }.awaitSuspending()

    companion object {
        private val log = Logger.getLogger(KycRepository::class.java)

        /**
         * Scalar HQL rather than Panache's `.project(...)`: a constructor-arg projection resolves
         * its columns from CONSTRUCTOR PARAMETER NAMES, which requires the `-parameters` compiler
         * flag this build does not set, so it compiles cleanly and throws
         * `PanacheQueryException` at runtime. Selecting the single column directly needs no
         * parameter-name metadata, and returns only the ids — the point of the query.
         */
/**
         * Maximum ids bound into one `IN` statement.
         *
         * Well under PostgreSQL's 65,535-parameter protocol ceiling, so the margin absorbs any
         * other binds the statement grows later, and small enough that one batch stays a cheap
         * index probe rather than a planner-defeating list.
         */
        internal const val ID_BATCH_SIZE = 1_000

        /**
         * Split [partyIds] into statement-sized batches.
         *
         * `internal` so the bound can be MEASURED rather than argued: each returned list is
         * exactly the collection handed to `setParameter("ids", ...)`, so its size IS the bind
         * count of one statement (`KycRepositoryBatchingTest`).
         */
        internal fun idBatches(partyIds: Collection<UUID>): List<List<UUID>> = partyIds.toList().chunked(ID_BATCH_SIZE)

        private const val PARTY_IDS_WITH_CASE_HQL =
            "SELECT c.partyId FROM KycCaseEntity c WHERE c.partyId IN :ids"

        internal fun anonymizeChecksJson(checksJson: String, objectMapper: ObjectMapper): String = try {
            val checks: List<KycCheck> = objectMapper.readValue(
                checksJson,
                objectMapper.typeFactory.constructCollectionType(List::class.java, KycCheck::class.java),
            )
            objectMapper.writeValueAsString(checks.map { it.copy(result = null) })
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            log.warnf(
                "Corrupted checksJson during GDPR erasure for a KYC case — replacing with empty array: %s",
                e.message,
            )
            "[]"
        }
    }
}

// The outbox payload is the event's own flat envelope verbatim — `kyc-outbox-out` and the retired
// `kyc-events-out` both target topic `openbank.kyc.events`, so a consumer sees exactly the bytes it
// saw before, plus the additive OutboxKafkaHeaders and a partition key.
private fun KycEvent.toOutboxMessage(objectMapper: ObjectMapper) = OutboxMessage(
    aggregateId = aggregateId,
    eventType = eventType,
    payload = objectMapper.writeValueAsString(envelope),
    createdAt = occurredAt,
)

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
