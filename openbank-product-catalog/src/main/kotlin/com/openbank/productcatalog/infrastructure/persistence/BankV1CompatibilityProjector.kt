// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.openbank.productcatalog.application.CatalogConflictException
import com.openbank.productcatalog.domain.Product
import com.openbank.productcatalog.domain.ProductStatus
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.RevisionState
import com.openbank.productcatalog.domain.catalog.SchemaRef
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Lossless compatibility bridge required by ADR-0257. The legacy JSON stays an explicit banking-pack
 * attribute; generic code never guesses how an unknown industry maps into `/api/v1`.
 */
@ApplicationScoped
class BankV1CompatibilityProjector(
    private val catalogJson: CatalogJson,
    private val mapping: BankV1CatalogMapping,
    private val evidence: CatalogCompatibilityEvidenceWriter,
    private val clock: Clock,
) {
    fun ensureMapped(session: Mutiny.Session, product: Product, legacyCode: String?, actorId: String): Uni<Void> =
        session.find(BankV1ProductMappingEntity::class.java, UUID.fromString(product.id)).flatMap { mapping ->
            if (mapping == null) createMapping(session, product, legacyCode, actorId) else Uni.createFrom().voidItem()
        }

    fun syncDraft(session: Mutiny.Session, product: Product, actorId: String): Uni<Void> =
        ensureMapped(session, product, null, actorId).flatMap {
            session.find(BankV1ProductMappingEntity::class.java, UUID.fromString(product.id))
        }.flatMap { mapping ->
            checkNotNull(mapping) { "bank mapping for ${product.id} disappeared" }
            session.createQuery(
                "FROM CatalogRevisionEntity WHERE offeringId = :offeringId AND state = 'DRAFT' " +
                    "ORDER BY number DESC",
                CatalogRevisionEntity::class.java,
            ).setParameter("offeringId", mapping.defaultOfferingId).resultList.flatMap { drafts ->
                val existing = drafts.firstOrNull()
                if (existing == null) {
                    nextNumber(session, mapping.defaultOfferingId).flatMap { number ->
                        persistDraft(session, product, mapping.defaultOfferingId, number, actorId)
                    }
                } else {
                    replaceDraft(session, existing, product, actorId)
                }
            }
        }

    fun refreshLegacyProjection(session: Mutiny.Session, revision: CatalogRevisionEntity, at: Instant): Uni<Void> =
        session.createQuery(
            "FROM BankV1ProductMappingEntity WHERE defaultOfferingId = :offeringId",
            BankV1ProductMappingEntity::class.java,
        ).setParameter("offeringId", revision.offeringId).resultList.flatMap { mappings ->
            val bankMapping = mappings.firstOrNull() ?: return@flatMap Uni.createFrom().voidItem()
            val projected = try {
                mapping.legacyProduct(revision)
            } catch (failure: IllegalStateException) {
                throw CatalogConflictException(
                    failure.message ?: "mapped banking revision is not lossless",
                    failure,
                )
            }
            if (projected.id != bankMapping.productId.toString()) {
                throw CatalogConflictException("mapped banking revision changed the canonical product id")
            }
            session.find(ProductEntity::class.java, bankMapping.productId).flatMap { entity ->
                checkNotNull(entity) { "legacy product ${bankMapping.productId} disappeared" }
                if (projected.code != entity.code) {
                    throw CatalogConflictException("mapped banking revision changed immutable product code")
                }
                mapping.applyProjection(
                    entity,
                    projected.copy(
                        status = ProductStatus.ACTIVE,
                        updatedAt = at,
                        revision =
                        entity.revision + 1,
                    ),
                )
                bankMapping.projectedRevisionId = revision.id
                Uni.createFrom().voidItem()
            }
        }

    private fun createMapping(
        session: Mutiny.Session,
        product: Product,
        legacyCode: String?,
        actorId: String,
    ): Uni<Void> {
        val now = Instant.now(clock)
        val productId = UUID.fromString(product.id)
        val offeringId = deterministicId("bank-v1-offering:$productId")
        val revisionId = deterministicId("bank-v1-revision:$productId:$FIRST_REVISION_NUMBER")
        val published = product.status == ProductStatus.ACTIVE
        val content = mapping.contentOf(product)
        val revision = ProductRevision(
            id = revisionId,
            offeringId = offeringId,
            number = FIRST_REVISION_NUMBER,
            schemaRef = BANK_V1_SCHEMA,
            state = if (published) RevisionState.PUBLISHED else RevisionState.DRAFT,
            content = content,
            makerId = actorId,
            checkerId = if (published) BOOTSTRAP_CHECKER else null,
            reason = if (published) BOOTSTRAP_REASON else null,
            contentHash = if (published) catalogJson.sha256(catalogJson.toContentNode(content)) else null,
            createdAt = now,
            updatedAt = now,
        )
        val specification = CatalogSpecificationEntity().apply {
            id = productId
            code = product.code
            schemaId = BANK_V1_SCHEMA.id
            schemaVersion = BANK_V1_SCHEMA.version
            createdAt = now
        }
        val offering = CatalogOfferingEntity().apply {
            id = offeringId
            specificationId = productId
            code = product.code
            market = JsonObject()
        }
        val revisionEntity = mapping.toEntity(revision)
        val mapping = BankV1ProductMappingEntity().apply {
            this.productId = productId
            defaultOfferingId = offeringId
            this.legacyCode = legacyCode
            projectedRevisionId = if (published) revisionId else null
            createdAt = now
        }
        return session.persistAll(specification, offering, revisionEntity, mapping)
            .flatMap { persistPrices(session, revision) }
            .flatMap {
                if (published) session.persist(bootstrapApproval(revision, now)) else Uni.createFrom().voidItem()
            }
            .flatMap { evidence.record(session, "SPECIFICATION", productId, "BANK_V1_BACKFILLED", actorId) }
            .flatMap {
                if (published) {
                    evidence.record(session, "REVISION", revisionId, "BANK_V1_BOOTSTRAP_PUBLISHED", actorId)
                } else {
                    Uni.createFrom().voidItem()
                }
            }
    }

    private fun persistDraft(
        session: Mutiny.Session,
        product: Product,
        offeringId: UUID,
        number: Long,
        actorId: String,
    ): Uni<Void> {
        val now = Instant.now(clock)
        val revision = ProductRevision(
            offeringId = offeringId,
            number = number,
            schemaRef = BANK_V1_SCHEMA,
            content = mapping.contentOf(product),
            makerId = actorId,
            createdAt = now,
            updatedAt = now,
        )
        return session.persist(mapping.toEntity(revision))
            .flatMap { persistPrices(session, revision) }
            .flatMap { evidence.record(session, "REVISION", revision.id, "BANK_V1_DRAFTED", actorId) }
    }

    private fun replaceDraft(
        session: Mutiny.Session,
        draft: CatalogRevisionEntity,
        product: Product,
        actorId: String,
    ): Uni<Void> {
        val content = mapping.contentOf(product)
        draft.content = JsonObject(catalogJson.toContentNode(content).toString())
        draft.makerId = actorId
        draft.updatedAt = Instant.now(clock)
        return session.flush()
            .flatMap {
                session.createMutationQuery("DELETE FROM CatalogPriceEntity WHERE revisionId = :revisionId")
                    .setParameter("revisionId", draft.id).executeUpdate()
            }
            .flatMap {
                persistPrices(
                    session,
                    ProductRevision(
                        id = draft.id,
                        offeringId = draft.offeringId,
                        number = draft.number,
                        schemaRef = BANK_V1_SCHEMA,
                        content = content,
                        makerId = actorId,
                        createdAt = draft.createdAt,
                        updatedAt = draft.updatedAt,
                        revision = draft.revision + 1,
                    ),
                )
            }
            .flatMap { evidence.record(session, "REVISION", draft.id, "BANK_V1_DRAFT_UPDATED", actorId) }
    }

    private fun persistPrices(session: Mutiny.Session, revision: ProductRevision): Uni<Void> =
        mapping.priceEntities(revision).fold(Uni.createFrom().voidItem()) {
                persisted,
                entity,
            ->
            persisted.flatMap { session.persist(entity) }
        }

    private fun nextNumber(session: Mutiny.Session, offeringId: UUID): Uni<Long> = session.createQuery(
        "SELECT COALESCE(MAX(number), 0) FROM CatalogRevisionEntity WHERE offeringId = :offeringId",
        Long::class.javaObjectType,
    ).setParameter("offeringId", offeringId).singleResult.map { it + 1 }

    private fun bootstrapApproval(revision: ProductRevision, at: Instant) = CatalogApprovalEntity().apply {
        id = UUID.randomUUID()
        revisionId = revision.id
        makerId = revision.makerId
        checkerId = BOOTSTRAP_CHECKER
        reason = BOOTSTRAP_REASON
        approvedAt = at
    }

    private fun deterministicId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val FIRST_REVISION_NUMBER = 1L
        const val BOOTSTRAP_CHECKER = "system:bank-v1-backfill-checker"
        const val BOOTSTRAP_REASON = "Bootstrap of the pre-existing approved v1 banking projection"
        val BANK_V1_SCHEMA = SchemaRef("org.openbank.banking.legacy-product", 1)
    }
}
