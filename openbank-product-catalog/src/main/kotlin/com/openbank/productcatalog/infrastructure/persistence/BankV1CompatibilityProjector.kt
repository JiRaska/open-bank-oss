// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.persistence

import com.openbank.libs.domain.identifiers.Ids
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
import jakarta.persistence.LockModeType
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
@Suppress("TooManyFunctions")
class BankV1CompatibilityProjector(
    private val catalogJson: CatalogJson,
    private val mapping: BankV1CatalogMapping,
    private val evidence: CatalogCompatibilityEvidenceWriter,
    private val clock: Clock,
) {
    fun ensureMapped(session: Mutiny.Session, product: Product, legacyCode: String?, actorId: String): Uni<Boolean> =
        session.find(BankV1ProductMappingEntity::class.java, UUID.fromString(product.id)).flatMap { existing ->
            if (existing == null) {
                createMapping(session, product, legacyCode, actorId).replaceWith(true)
            } else {
                reconcileMapping(session, existing, product, actorId)
            }
        }

    fun syncDraft(session: Mutiny.Session, previous: Product, product: Product, actorId: String): Uni<Void> =
        session.find(BankV1ProductMappingEntity::class.java, UUID.fromString(product.id)).flatMap { existing ->
            if (existing == null) {
                createMapping(session, product, null, actorId)
            } else {
                currentDraftRevision(session, existing.defaultOfferingId).flatMap { draft ->
                    currentPublishedRevision(session, existing.defaultOfferingId).flatMap { published ->
                        projectedRevision(session, existing.projectedRevisionId).flatMap { projected ->
                            initialiseWatermarks(existing, previous, draft, published, projected)
                            syncMappedDraft(session, existing, product, actorId)
                        }
                    }
                }
            }
        }

    fun refreshLegacyProjection(session: Mutiny.Session, revision: CatalogRevisionEntity, at: Instant): Uni<Void> =
        session.createQuery(
            "FROM BankV1ProductMappingEntity WHERE defaultOfferingId = :offeringId",
            BankV1ProductMappingEntity::class.java,
        ).setParameter("offeringId", revision.offeringId).resultList.flatMap { mappings ->
            val bankMapping = mappings.firstOrNull() ?: return@flatMap Uni.createFrom().voidItem()
            projectLegacy(session, bankMapping, revision, at)
        }

    private fun reconcileMapping(
        session: Mutiny.Session,
        bankMapping: BankV1ProductMappingEntity,
        product: Product,
        actorId: String,
    ): Uni<Boolean> = currentPublishedRevision(session, bankMapping.defaultOfferingId).flatMap { published ->
        currentDraftRevision(session, bankMapping.defaultOfferingId).flatMap { draft ->
            projectedRevision(session, bankMapping.projectedRevisionId).flatMap { projected ->
                reconcileMapping(session, bankMapping, product, actorId, published, draft, projected)
            }
        }
    }

    private fun reconcileMapping(
        session: Mutiny.Session,
        bankMapping: BankV1ProductMappingEntity,
        product: Product,
        actorId: String,
        published: CatalogRevisionEntity?,
        draft: CatalogRevisionEntity?,
        projected: CatalogRevisionEntity?,
    ): Uni<Boolean> {
        requireProjectedRevisionStillExists(bankMapping, product, projected)
        initialiseWatermarks(bankMapping, product, draft, published, projected)
        val legacyChanged = product.revision > bankMapping.lastSyncedProductRevision
        val catalogChanged = published?.id != bankMapping.projectedRevisionId
        val draftChanged = draft != null && draft.revision != bankMapping.lastSyncedDraftRevision
        val equivalentPreviousDualWrite = draft?.let { mapping.isEquivalentDraft(it, product) } == true
        if (legacyChanged && draftChanged) {
            if (!catalogChanged && equivalentPreviousDualWrite) {
                // The previous bank-adapter binary atomically dual-wrote both authorities but did not
                // know the V7 watermarks. Equivalent snapshots are one change, not a divergence.
                bankMapping.lastSyncedProductRevision = product.revision
                bankMapping.lastSyncedDraftRevision = checkNotNull(draft).revision
                return Uni.createFrom().item(true)
            }
        }
        requireNoDivergentChanges(bankMapping, product, legacyChanged, catalogChanged, draftChanged)
        return when {
            legacyChanged -> syncMappedDraft(session, bankMapping, product, actorId).replaceWith(true)
            catalogChanged && published != null ->
                projectLegacy(session, bankMapping, published, Instant.now(clock)).replaceWith(true)
            else -> Uni.createFrom().item(false)
        }
    }

    private fun requireProjectedRevisionStillExists(
        bankMapping: BankV1ProductMappingEntity,
        product: Product,
        projected: CatalogRevisionEntity?,
    ) {
        if (bankMapping.projectedRevisionId != null && projected == null) {
            throw CatalogConflictException(
                "mapped banking product ${product.id} lost its projected published revision",
            )
        }
    }

    private fun requireNoDivergentChanges(
        bankMapping: BankV1ProductMappingEntity,
        product: Product,
        legacyChanged: Boolean,
        catalogChanged: Boolean,
        draftChanged: Boolean,
    ) {
        if (product.revision < bankMapping.lastSyncedProductRevision) {
            throw CatalogConflictException(
                "legacy product ${product.id} is older than its compatibility watermark",
            )
        }
        if (legacyChanged && (catalogChanged || draftChanged)) {
            throw CatalogConflictException("banking product ${product.id} changed independently in v1 and v2")
        }
    }

    private fun initialiseWatermarks(
        bankMapping: BankV1ProductMappingEntity,
        product: Product,
        draft: CatalogRevisionEntity?,
        published: CatalogRevisionEntity?,
        projected: CatalogRevisionEntity?,
    ) {
        if (
            bankMapping.lastSyncedProductRevision >= 0 &&
            bankMapping.lastSyncedDraftRevision != UNINITIALISED_DRAFT_REVISION
        ) {
            return
        }
        val legacyChanged = projected != null && !mapping.isEquivalentPublishedProjection(projected, product)
        val catalogPublishedChanged = published?.id != bankMapping.projectedRevisionId
        val catalogDraftChanged = draft != null && !mapping.isEquivalentDraft(draft, product)
        if (projected == null && catalogDraftChanged) {
            throw CatalogConflictException(
                "banking product ${product.id} has no published compatibility baseline and its v1 and v2 drafts differ",
            )
        }
        if (legacyChanged && (catalogPublishedChanged || catalogDraftChanged)) {
            throw CatalogConflictException(
                "banking product ${product.id} changed independently in v1 and v2 and requires reconciliation",
            )
        }
        bankMapping.lastSyncedProductRevision =
            if (legacyChanged) maxOf(NO_PRODUCT_REVISION, product.revision - 1) else product.revision
        bankMapping.lastSyncedDraftRevision = when {
            draft == null -> NO_DRAFT_REVISION
            catalogDraftChanged -> maxOf(NO_DRAFT_REVISION, draft.revision - 1)
            else -> draft.revision
        }
    }

    private fun projectedRevision(session: Mutiny.Session, revisionId: UUID?): Uni<CatalogRevisionEntity?> =
        if (revisionId == null) {
            Uni.createFrom().nullItem()
        } else {
            session.find(CatalogRevisionEntity::class.java, revisionId)
        }

    private fun currentPublishedRevision(session: Mutiny.Session, offeringId: UUID): Uni<CatalogRevisionEntity?> =
        session.createQuery(
            "FROM CatalogRevisionEntity WHERE offeringId = :offeringId AND state = 'PUBLISHED' ORDER BY number DESC",
            CatalogRevisionEntity::class.java,
        ).setParameter("offeringId", offeringId).setMaxResults(2).resultList.map { published ->
            if (published.size > 1) {
                throw CatalogConflictException("banking offering $offeringId has multiple published revisions")
            }
            published.firstOrNull()
        }

    private fun currentDraftRevision(session: Mutiny.Session, offeringId: UUID): Uni<CatalogRevisionEntity?> =
        session.createQuery(
            "FROM CatalogRevisionEntity WHERE offeringId = :offeringId AND state = 'DRAFT' ORDER BY number DESC",
            CatalogRevisionEntity::class.java,
        ).setParameter("offeringId", offeringId).setMaxResults(2).resultList.map { drafts ->
            if (drafts.size > 1) {
                throw CatalogConflictException("banking offering $offeringId has multiple drafts")
            }
            drafts.firstOrNull()
        }

    private fun projectLegacy(
        session: Mutiny.Session,
        bankMapping: BankV1ProductMappingEntity,
        revision: CatalogRevisionEntity,
        at: Instant,
    ): Uni<Void> {
        val projected = validatedProjection(revision, bankMapping.productId)
        return session.createQuery(
            "FROM ProductEntity WHERE id = :id",
            ProductEntity::class.java,
        ).setParameter("id", bankMapping.productId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .singleResultOrNull.flatMap { entity ->
                checkNotNull(entity) { "legacy product ${bankMapping.productId} disappeared" }
                if (entity.revision != bankMapping.lastSyncedProductRevision) {
                    throw CatalogConflictException(
                        "legacy product ${bankMapping.productId} changed after its v2 compatibility watermark",
                    )
                }
                if (projected.code != entity.code) {
                    throw CatalogConflictException("mapped banking revision changed immutable product code")
                }
                mapping.applyProjection(
                    entity,
                    projected.copy(
                        status = ProductStatus.ACTIVE,
                        updatedAt = at,
                        revision = entity.revision + 1,
                    ),
                )
                bankMapping.projectedRevisionId = revision.id
                bankMapping.lastSyncedProductRevision = entity.revision + 1
                bankMapping.lastSyncedDraftRevision = NO_DRAFT_REVISION
                Uni.createFrom().voidItem()
            }
    }

    private fun validatedProjection(revision: CatalogRevisionEntity, expectedProductId: UUID): Product {
        val projected = try {
            mapping.validatedLegacyProduct(revision)
        } catch (failure: IllegalArgumentException) {
            throw CatalogConflictException(
                failure.message ?: "mapped banking revision is not lossless",
                failure,
            )
        }
        if (projected.id != expectedProductId.toString()) {
            throw CatalogConflictException("mapped banking revision changed the canonical product id")
        }
        return projected
    }

    @Suppress("LongMethod")
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
        if (published) revisionEntity.state = RevisionState.DRAFT.name
        val mapping = BankV1ProductMappingEntity().apply {
            this.productId = productId
            defaultOfferingId = offeringId
            this.legacyCode = legacyCode
            projectedRevisionId = if (published) revisionId else null
            lastSyncedProductRevision = product.revision
            lastSyncedDraftRevision = if (published) NO_DRAFT_REVISION else FIRST_ENTITY_REVISION
            createdAt = now
        }
        return session.persistAll(specification, offering, revisionEntity, mapping)
            .flatMap { session.flush() }
            .flatMap { persistPrices(session, revision) }
            .flatMap { session.flush() }
            .invoke { _: Void? -> if (published) revisionEntity.state = RevisionState.PUBLISHED.name }
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
        bankMapping: BankV1ProductMappingEntity,
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
            .invoke { _: Void? -> bankMapping.lastSyncedDraftRevision = FIRST_ENTITY_REVISION }
    }

    private fun syncMappedDraft(
        session: Mutiny.Session,
        bankMapping: BankV1ProductMappingEntity,
        product: Product,
        actorId: String,
    ): Uni<Void> = session.createQuery(
        "FROM CatalogRevisionEntity WHERE offeringId = :offeringId AND state = 'DRAFT' ORDER BY number DESC",
        CatalogRevisionEntity::class.java,
    ).setParameter("offeringId", bankMapping.defaultOfferingId).resultList.flatMap { drafts ->
        if (drafts.size > 1) {
            throw CatalogConflictException("banking offering ${bankMapping.defaultOfferingId} has multiple drafts")
        }
        val current = drafts.firstOrNull()
        if (
            current != null &&
            bankMapping.lastSyncedDraftRevision >= 0 &&
            current.revision != bankMapping.lastSyncedDraftRevision
        ) {
            throw CatalogConflictException(
                "mapped v2 draft for banking product ${bankMapping.productId} changed since the v1 read",
            )
        }
        val synchronized = current?.let { replaceDraft(session, bankMapping, it, product, actorId) }
            ?: nextNumber(session, bankMapping.defaultOfferingId).flatMap { number ->
                persistDraft(session, bankMapping, product, bankMapping.defaultOfferingId, number, actorId)
            }
        synchronized.invoke { _: Void? -> bankMapping.lastSyncedProductRevision = product.revision }
    }

    private fun replaceDraft(
        session: Mutiny.Session,
        bankMapping: BankV1ProductMappingEntity,
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
                session.createMutationQuery("DELETE FROM CatalogRelationshipEntity WHERE revisionId = :revisionId")
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
            .invoke { _: Void? -> bankMapping.lastSyncedDraftRevision = draft.revision }
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
        id = Ids.newId()
        revisionId = revision.id
        makerId = revision.makerId
        checkerId = BOOTSTRAP_CHECKER
        reason = BOOTSTRAP_REASON
        approvedAt = at
    }

    private fun deterministicId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val FIRST_REVISION_NUMBER = 1L
        const val FIRST_ENTITY_REVISION = 0L
        const val NO_PRODUCT_REVISION = -1L
        const val NO_DRAFT_REVISION = -1L
        const val UNINITIALISED_DRAFT_REVISION = -2L
        const val BOOTSTRAP_CHECKER = "system:bank-v1-backfill-checker"
        const val BOOTSTRAP_REASON = "Bootstrap of the pre-existing approved v1 banking projection"
        val BANK_V1_SCHEMA = SchemaRef("org.openbank.banking.legacy-product", 1)
    }
}
