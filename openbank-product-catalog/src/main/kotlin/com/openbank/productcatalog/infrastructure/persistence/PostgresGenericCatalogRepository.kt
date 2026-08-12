// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.productcatalog.application.CatalogConflictException
import com.openbank.productcatalog.application.CatalogForbiddenException
import com.openbank.productcatalog.application.CatalogPreconditionFailedException
import com.openbank.productcatalog.application.port.out.GenericCatalogRepository
import com.openbank.productcatalog.domain.catalog.CatalogChangeEvent
import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.MarketContext
import com.openbank.productcatalog.domain.catalog.ProductOffering
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.ProductSpecification
import com.openbank.productcatalog.domain.catalog.RevisionState
import com.openbank.productcatalog.domain.catalog.SchemaRef
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.LockMode
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions")
class PostgresGenericCatalogRepository(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    private val catalogJson: CatalogJson,
    private val clock: Clock,
) : GenericCatalogRepository {
    override suspend fun registerSchema(schema: CatalogSchema) {
        sessions.withTransaction { session ->
            session.find(CatalogSchemaEntity::class.java, schema.ref.key()).flatMap { existing ->
                when {
                    existing == null -> session.persist(schema.toEntity())
                    existing.sha256 == schema.sha256 -> Uni.createFrom().voidItem()
                    else -> error(
                        "schema ${schema.ref} is already registered with a different hash",
                    )
                }
            }
        }.awaitSuspending()
    }

    override suspend fun findSchema(ref: SchemaRef): CatalogSchema? = sessions.withSession { session ->
        session.find(CatalogSchemaEntity::class.java, ref.key())
    }.map { it?.toDomain() }.awaitSuspending()

    override suspend fun listSchemas(): List<CatalogSchema> = sessions.withSession { session ->
        session.createQuery(
            "FROM CatalogSchemaEntity ORDER BY schemaId, schemaVersion",
            CatalogSchemaEntity::class.java,
        )
            .resultList
    }.map { rows -> rows.map { it.toDomain() } }.awaitSuspending()

    override suspend fun createSpecification(
        specification: ProductSpecification,
        actorId: String,
    ): ProductSpecification = sessions.withTransaction { session ->
        session.persist(specification.toEntity())
            .flatMap {
                recordChange(session, "SPECIFICATION", specification.id, "SPECIFICATION_CREATED", actorId)
            }
            .map { specification }
    }.awaitSuspending()

    override suspend fun findSpecification(id: UUID): ProductSpecification? = sessions.withSession { session ->
        session.find(CatalogSpecificationEntity::class.java, id)
    }.map { it?.toDomain() }.awaitSuspending()

    override suspend fun createOffering(offering: ProductOffering, actorId: String): ProductOffering =
        sessions.withTransaction { session ->
            session.persist(offering.toEntity())
                .flatMap { recordChange(session, "OFFERING", offering.id, "OFFERING_CREATED", actorId) }
                .map { offering }
        }.awaitSuspending()

    override suspend fun findOffering(id: UUID): ProductOffering? = sessions.withSession { session ->
        session.find(CatalogOfferingEntity::class.java, id)
    }.map { it?.toDomain() }.awaitSuspending()

    override suspend fun createDraft(revision: ProductRevision, actorId: String): ProductRevision =
        sessions.withTransaction { session ->
            session.persist(revision.toEntity())
                .flatMap { persistRevisionChildren(session, revision) }
                .flatMap { recordChange(session, "REVISION", revision.id, "REVISION_DRAFTED", actorId) }
                .map { revision }
        }.awaitSuspending()

    override suspend fun nextRevisionNumber(offeringId: UUID): Long = sessions.withSession { session ->
        session.createQuery(
            "SELECT COALESCE(MAX(number), 0) FROM CatalogRevisionEntity WHERE offeringId = :offeringId",
            Long::class.javaObjectType,
        ).setParameter("offeringId", offeringId).singleResult
    }.map { it + 1 }.awaitSuspending()

    override suspend fun findRevision(id: UUID): ProductRevision? = sessions.withSession { session ->
        session.find(CatalogRevisionEntity::class.java, id)
    }.map { it?.toDomain() }.awaitSuspending()

    override suspend fun updateDraft(revision: ProductRevision, actorId: String): ProductRevision =
        translateOptimisticFailure {
            sessions.withTransaction { session ->
                session.createQuery(
                    "FROM CatalogRevisionEntity WHERE id = :id AND revision = :revision",
                    CatalogRevisionEntity::class.java,
                ).setParameter("id", revision.id).setParameter("revision", revision.revision).resultList
                    .map {
                        it.firstOrNull()
                            ?: throw CatalogPreconditionFailedException("revision was modified concurrently")
                    }
                    .flatMap { entity ->
                        if (entity.state != RevisionState.DRAFT.name) {
                            throw CatalogConflictException("published revisions are immutable")
                        }
                        entity.applyFrom(revision)
                        session.flush()
                            .flatMap { deleteRevisionChildren(session, revision.id) }
                            .flatMap { persistRevisionChildren(session, revision) }
                            .flatMap { recordChange(session, "REVISION", revision.id, "REVISION_UPDATED", actorId) }
                            .map { revision.copy(revision = revision.revision + 1) }
                    }
            }.awaitSuspending()
        }

    override suspend fun publishDraft(
        revisionId: UUID,
        expectedRevision: Long,
        checkerId: String,
        reason: String,
        contentHash: String,
        at: Instant,
    ): ProductRevision = translateOptimisticFailure {
        sessions.withTransaction { session ->
            session.createQuery(
                "FROM CatalogRevisionEntity WHERE id = :id AND revision = :revision",
                CatalogRevisionEntity::class.java,
            ).setParameter("id", revisionId).setParameter("revision", expectedRevision).resultList
                .map {
                    it.firstOrNull()
                        ?: throw CatalogPreconditionFailedException("revision was modified concurrently")
                }
                .flatMap { draft ->
                    if (draft.state != RevisionState.DRAFT.name) {
                        throw CatalogConflictException("published revisions are immutable")
                    }
                    if (draft.makerId == checkerId) {
                        throw CatalogForbiddenException("maker cannot publish their own revision")
                    }
                    require(reason.isNotBlank()) { "publication reason must not be blank" }
                    session.find(CatalogOfferingEntity::class.java, draft.offeringId, LockMode.PESSIMISTIC_WRITE)
                        .flatMap { offering ->
                            checkNotNull(offering) { "offering ${draft.offeringId} disappeared during publication" }
                            preparePublication(session, draft, at).flatMap {
                                draft.state = RevisionState.PUBLISHED.name
                                draft.checkerId = checkerId
                                draft.reason = reason
                                draft.contentHash = contentHash
                                draft.updatedAt = at
                                session.persist(approval(draft, checkerId, reason, at))
                            }.flatMap {
                                recordChange(session, "REVISION", draft.id, "REVISION_PUBLISHED", checkerId)
                            }.map { draft.toDomain().copy(revision = draft.revision + 1) }
                        }
                }
        }.awaitSuspending()
    }

    override suspend fun findPublished(specificationId: UUID, effectiveAt: Instant): ProductRevision? =
        sessions.withSession { session ->
            session.createQuery(
                """FROM CatalogRevisionEntity r WHERE r.offeringId IN """ +
                    """(SELECT o.id FROM CatalogOfferingEntity o WHERE o.specificationId = :specificationId) """ +
                    """AND r.state IN ('PUBLISHED', 'SUPERSEDED') """ +
                    """AND (r.effectiveFrom IS NULL OR r.effectiveFrom <= :at) """ +
                    """AND (r.effectiveTo IS NULL OR r.effectiveTo > :at) ORDER BY r.number DESC""",
                CatalogRevisionEntity::class.java,
            ).setParameter("specificationId", specificationId).setParameter("at", effectiveAt).resultList
        }.map { it.firstOrNull()?.toDomain() }.awaitSuspending()

    private fun preparePublication(session: Mutiny.Session, draft: CatalogRevisionEntity, at: Instant): Uni<Void> =
        session.createQuery(
            "FROM CatalogRevisionEntity WHERE offeringId = :offeringId AND state IN ('PUBLISHED', 'SUPERSEDED')",
            CatalogRevisionEntity::class.java,
        ).setParameter("offeringId", draft.offeringId).resultList.invoke { published ->
            val newStart = draft.effectiveFrom ?: at
            if (draft.effectiveTo != null && !draft.effectiveTo!!.isAfter(newStart)) {
                throw CatalogConflictException("published effectiveTo must be after its effectiveFrom")
            }
            val overlaps = published.filter { existing -> intervalsOverlap(existing, newStart, draft.effectiveTo) }
            val predecessors = overlaps.filter { (it.effectiveFrom ?: Instant.MIN).isBefore(newStart) }
            if (predecessors.size > 1 || overlaps.size != predecessors.size) {
                throw CatalogConflictException("published effective intervals must not overlap")
            }
            predecessors.singleOrNull()?.let { previous ->
                previous.state = RevisionState.SUPERSEDED.name
                previous.effectiveTo = newStart
                previous.updatedAt = at
            }
        }.replaceWithVoid()

    private fun intervalsOverlap(existing: CatalogRevisionEntity, newStart: Instant, newEnd: Instant?): Boolean {
        val existingStart = existing.effectiveFrom ?: Instant.MIN
        val existingEndsAfterNewStarts = existing.effectiveTo == null || existing.effectiveTo!!.isAfter(newStart)
        val newEndsAfterExistingStarts = newEnd == null || newEnd.isAfter(existingStart)
        return existingEndsAfterNewStarts && newEndsAfterExistingStarts
    }

    @Suppress("SpreadOperator")
    private fun persistRevisionChildren(session: Mutiny.Session, revision: ProductRevision): Uni<Void> {
        val prices = revision.content.prices.map { price ->
            CatalogPriceEntity().apply {
                id = UUID.randomUUID()
                revisionId = revision.id
                code = price.code
                kind = price.kind.name
                value = price.value
                currency = price.currency
                unit = price.unit
                cadence = price.cadence.name
                taxTreatment = price.taxTreatment.name
            }
        }
        val relationships = revision.content.relationships.map { relationship ->
            CatalogRelationshipEntity().apply {
                id = UUID.randomUUID()
                revisionId = revision.id
                targetOfferingId = relationship.targetOfferingId
                kind = relationship.kind.name
            }
        }
        return if (prices.isEmpty() && relationships.isEmpty()) {
            Uni.createFrom().voidItem()
        } else {
            session.persistAll(*(prices + relationships).toTypedArray())
        }
    }

    private fun deleteRevisionChildren(session: Mutiny.Session, revisionId: UUID): Uni<Void> =
        session.createMutationQuery("DELETE FROM CatalogPriceEntity WHERE revisionId = :revisionId")
            .setParameter("revisionId", revisionId).executeUpdate()
            .flatMap {
                session.createMutationQuery("DELETE FROM CatalogRelationshipEntity WHERE revisionId = :revisionId")
                    .setParameter("revisionId", revisionId).executeUpdate()
            }.replaceWithVoid()

    private fun recordChange(
        session: Mutiny.Session,
        aggregateType: String,
        aggregateId: UUID,
        action: String,
        actorId: String,
    ): Uni<Void> {
        val at = Instant.now(clock)
        val event = CatalogChangeEvent(
            eventId = UUID.randomUUID(),
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = "com.openbank.catalog.${action.lowercase()}",
            schemaVersion = 1,
            occurredAt = at,
            actorId = actorId,
        )
        val details = JsonObject(mapper.writeValueAsString(mapOf("action" to action)))
        val audit = CatalogAuditEntity().apply {
            id = UUID.randomUUID()
            this.aggregateType = aggregateType
            this.aggregateId = aggregateId
            this.action = action
            this.actorId = actorId
            occurredAt = at
            this.details = details
        }
        val outbox = CatalogOutboxEntity().apply {
            id = event.eventId
            this.aggregateType = aggregateType
            this.aggregateId = aggregateId
            eventType = event.eventType
            schemaVersion = event.schemaVersion
            occurredAt = at
            payload = JsonObject(mapper.writeValueAsString(event))
        }
        return session.persistAll(audit, outbox)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> translateOptimisticFailure(block: suspend () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        val optimistic = generateSequence(failure) { it.cause }.any {
            it is jakarta.persistence.OptimisticLockException ||
                it.javaClass.simpleName in setOf("StaleStateException", "StaleObjectStateException")
        }
        if (optimistic) {
            throw CatalogPreconditionFailedException("revision was modified concurrently")
        }
        throw failure
    }

    private fun approval(
        revision: CatalogRevisionEntity,
        checkerId: String,
        reason: String,
        at: Instant,
    ): CatalogApprovalEntity = CatalogApprovalEntity().apply {
        id = UUID.randomUUID()
        revisionId = revision.id
        makerId = revision.makerId
        this.checkerId = checkerId
        this.reason = reason
        approvedAt = at
    }

    private fun CatalogSchema.toEntity() = CatalogSchemaEntity().also {
        it.key = ref.key()
        it.schemaId = ref.id
        it.schemaVersion = ref.version
        it.document = JsonObject(catalogJson.toNode(document).toString())
        it.sha256 = sha256
        it.registeredAt = registeredAt
    }

    private fun CatalogSchemaEntity.toDomain() = CatalogSchema(
        ref = SchemaRef(schemaId, schemaVersion),
        document = catalogJson.toObject(mapper.readTree(document.encode())),
        sha256 = sha256,
        registeredAt = registeredAt,
    )

    private fun ProductSpecification.toEntity() = CatalogSpecificationEntity().also {
        it.id = id
        it.code = code
        it.schemaId = schemaRef.id
        it.schemaVersion = schemaRef.version
        it.createdAt = createdAt
        it.revision = revision
    }

    private fun CatalogSpecificationEntity.toDomain() = ProductSpecification(
        id = id,
        code = code,
        schemaRef = SchemaRef(schemaId, schemaVersion),
        createdAt = createdAt,
        revision = revision,
    )

    private fun ProductOffering.toEntity() = CatalogOfferingEntity().also {
        it.id = id
        it.specificationId = specificationId
        it.code = code
        it.market = JsonObject(mapper.writeValueAsString(market))
        it.revision = revision
    }

    private fun CatalogOfferingEntity.toDomain() = ProductOffering(
        id = id,
        specificationId = specificationId,
        code = code,
        market = mapper.readValue(market.encode(), MarketContext::class.java),
        revision = revision,
    )

    private fun ProductRevision.toEntity() = CatalogRevisionEntity().also { it.applyFrom(this) }

    private fun CatalogRevisionEntity.applyFrom(source: ProductRevision) {
        id = source.id
        offeringId = source.offeringId
        number = source.number
        schemaId = source.schemaRef.id
        schemaVersion = source.schemaRef.version
        state = source.state.name
        content = JsonObject(catalogJson.toContentNode(source.content).toString())
        effectiveFrom = source.effectiveFrom
        effectiveTo = source.effectiveTo
        makerId = source.makerId
        checkerId = source.checkerId
        reason = source.reason
        contentHash = source.contentHash
        createdAt = source.createdAt
        updatedAt = source.updatedAt
        revision = source.revision
    }

    private fun CatalogRevisionEntity.toDomain() = ProductRevision(
        id = id,
        offeringId = offeringId,
        number = number,
        schemaRef = SchemaRef(schemaId, schemaVersion),
        state = RevisionState.valueOf(state),
        content = catalogJson.toContent(mapper.readTree(content.encode())),
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        makerId = makerId,
        checkerId = checkerId,
        reason = reason,
        contentHash = contentHash,
        createdAt = createdAt,
        updatedAt = updatedAt,
        revision = revision,
    )

    private fun SchemaRef.key(): String = "$id:$version"
}
