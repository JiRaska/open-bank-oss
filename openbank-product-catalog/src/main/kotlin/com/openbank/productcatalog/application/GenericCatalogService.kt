// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.domain.error.ResourceNotFoundException
import com.openbank.productcatalog.application.port.out.GenericCatalogRepository
import com.openbank.productcatalog.application.port.out.PensionApprovalRole
import com.openbank.productcatalog.application.port.out.PensionRevisionApproval
import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.CatalogSchemaValidator
import com.openbank.productcatalog.domain.catalog.MarketContext
import com.openbank.productcatalog.domain.catalog.ProductOffering
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.ProductSpecification
import com.openbank.productcatalog.domain.catalog.RelationshipKind
import com.openbank.productcatalog.domain.catalog.RevisionContent
import com.openbank.productcatalog.domain.catalog.RevisionState
import com.openbank.productcatalog.domain.catalog.SchemaRef
import com.openbank.productcatalog.domain.catalog.SchemaValidationResult
import com.openbank.productcatalog.domain.catalog.SchemaViolation
import com.openbank.productcatalog.infrastructure.catalog.CatalogJson
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

// #10911 phase 2: extends the libs-domain base so libs-runtime's ResourceNotFoundExceptionMapper
// handles the 404 with the same "CATALOG_NOT_FOUND" code the deleted local
// CatalogNotFoundExceptionMapper used. See ProductCatalogExceptionMapperEquivalenceTest.
class CatalogNotFoundException(message: String) : ResourceNotFoundException(message, code = "CATALOG_NOT_FOUND")
class CatalogValidationException(val violations: List<SchemaViolation>) : RuntimeException("catalog content is invalid")
class CatalogPreconditionFailedException(message: String) : RuntimeException(message)
class CatalogPreconditionRequiredException(message: String) : RuntimeException(message)

// #10911 phase 2: extends the libs-domain base, mapped 409 by ResourceConflictExceptionMapper with
// the same "CATALOG_CONFLICT" code the deleted local CatalogConflictExceptionMapper used.
class CatalogConflictException(message: String, cause: Throwable? = null) :
    ResourceConflictException(message, code = "CATALOG_CONFLICT", cause = cause)
class CatalogForbiddenException(message: String) : RuntimeException(message)

@ApplicationScoped
@Suppress("TooManyFunctions")
class GenericCatalogService(
    private val repository: GenericCatalogRepository,
    private val validator: CatalogSchemaValidator,
    private val catalogJson: CatalogJson,
    private val clock: Clock,
) {
    suspend fun listSchemas(): List<CatalogSchema> = repository.listSchemas()

    suspend fun findSchema(ref: SchemaRef): CatalogSchema = repository.findSchema(ref)
        ?: throw CatalogNotFoundException("schema $ref not found")

    suspend fun validate(ref: SchemaRef, attributes: JsonNode): List<SchemaViolation> {
        val schema = findSchema(ref)
        return when (val result = validator.validate(schema, catalogJson.toObject(attributes))) {
            SchemaValidationResult.Valid -> emptyList()
            is SchemaValidationResult.Invalid -> result.violations
        }
    }

    suspend fun createSpecification(specification: ProductSpecification, actorId: String): ProductSpecification {
        findSchema(specification.schemaRef)
        return repository.createSpecification(specification, actorId)
    }

    suspend fun listSpecifications(): List<ProductSpecification> = repository.listSpecifications()

    suspend fun findSpecification(id: UUID): ProductSpecification = repository.findSpecification(id)
        ?: throw CatalogNotFoundException("specification $id not found")

    suspend fun createOffering(
        specificationId: UUID,
        code: String,
        market: MarketContext,
        actorId: String,
    ): ProductOffering {
        findSpecification(specificationId)
        return repository.createOffering(
            ProductOffering(specificationId = specificationId, code = code, market = market),
            actorId,
        )
    }

    suspend fun listOfferings(specificationId: UUID?): List<ProductOffering> = repository.listOfferings(specificationId)

    suspend fun findOffering(id: UUID): ProductOffering = repository.findOffering(id)
        ?: throw CatalogNotFoundException("offering $id not found")

    suspend fun createDraft(
        offeringId: UUID,
        schemaRef: SchemaRef,
        content: RevisionContent,
        effectiveFrom: Instant?,
        effectiveTo: Instant?,
        actorId: String,
    ): ProductRevision {
        val offering = findOffering(offeringId)
        val specification = findSpecification(offering.specificationId)
        require(schemaRef.id == specification.schemaRef.id) {
            "a revision may advance its schema version but may not change its schema family"
        }
        findSchema(schemaRef)
        validateOrThrow(schemaRef, content.attributes)
        validateRelationshipReferences(offeringId, content)
        val now = Instant.now(clock)
        return repository.createDraft(
            ProductRevision(
                offeringId = offeringId,
                number = 1, // repository allocates the offering-scoped number while holding its lock
                schemaRef = schemaRef,
                content = content,
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                makerId = actorId,
                createdAt = now,
                updatedAt = now,
            ),
            actorId,
        )
    }

    suspend fun findRevision(id: UUID): ProductRevision = repository.findRevision(id)
        ?: throw CatalogNotFoundException("revision $id not found")

    suspend fun listRevisions(offeringId: UUID): List<ProductRevision> {
        findOffering(offeringId)
        return repository.listRevisions(offeringId)
    }

    suspend fun updateDraft(
        revisionId: UUID,
        expectedRevision: Long,
        content: RevisionContent,
        effectiveFrom: Instant?,
        effectiveTo: Instant?,
        actorId: String,
    ): ProductRevision {
        val existing = findRevision(revisionId)
        requireExpected(existing, expectedRevision)
        if (existing.state != RevisionState.DRAFT) {
            throw CatalogConflictException("published revisions are immutable")
        }
        validateOrThrow(existing.schemaRef, content.attributes)
        validateRelationshipReferences(existing.offeringId, content)
        return repository.updateDraft(
            existing.copy(
                content = content,
                effectiveFrom = effectiveFrom,
                effectiveTo = effectiveTo,
                makerId = actorId,
                checkerId = null,
                reason = null,
                contentHash = null,
                updatedAt = Instant.now(clock),
            ),
            actorId,
        )
    }

    suspend fun publish(revisionId: UUID, expectedRevision: Long, checkerId: String, reason: String): ProductRevision {
        val draft = findRevision(revisionId)
        requireExpected(draft, expectedRevision)
        if (draft.state != RevisionState.DRAFT) {
            throw CatalogConflictException("published revisions are immutable")
        }
        if (draft.makerId == checkerId) {
            throw CatalogForbiddenException("maker cannot publish their own revision")
        }
        require(reason.isNotBlank()) { "publication reason must not be blank" }
        validateOrThrow(draft.schemaRef, draft.content.attributes)
        val publicationAt = Instant.now(clock)
        requirePublishableBundleComponents(draft, draft.effectiveFrom ?: publicationAt)
        val hash = catalogJson.sha256(catalogJson.toContentNode(draft.content))
        return repository.publishDraft(
            revisionId = revisionId,
            expectedRevision = expectedRevision,
            checkerId = checkerId,
            reason = reason,
            contentHash = hash,
            at = publicationAt,
        )
    }

    @Suppress("ThrowsCount")
    suspend fun approvePensionRevision(
        revisionId: UUID,
        expectedRevision: Long,
        role: PensionApprovalRole,
        issuer: String,
        subject: String,
        actorName: String,
        reason: String,
    ): PensionRevisionApproval {
        val draft = findRevision(revisionId)
        requireExpected(draft, expectedRevision)
        if (draft.schemaRef.id != PENSION_SCHEMA ||
            draft.schemaRef.version != 2 ||
            draft.state != RevisionState.DRAFT
        ) {
            throw CatalogConflictException("only draft pension pack v2 revisions accept role approvals")
        }
        if (draft.effectiveFrom == null) {
            throw CatalogConflictException("pension approval requires an explicit effectiveFrom")
        }
        if (catalogJson.toContentNode(draft.content).path("attributes").path("reviewStatus").asText() !=
            REVIEWED_STATUS
        ) {
            throw CatalogConflictException("pension revision has not declared legal and commercial review")
        }
        require(issuer.isNotBlank() && subject.isNotBlank()) { "authenticated approver is required" }
        require(actorName.isNotBlank() && reason.isNotBlank()) { "approver and reason are required" }
        require(reason.length <= MAX_APPROVAL_REASON_LENGTH) { "approval reason is too long" }
        if (draft.makerId == subject || draft.makerId == actorName) {
            throw CatalogForbiddenException("maker cannot approve their own revision")
        }
        validateOrThrow(draft.schemaRef, draft.content.attributes)
        return repository.approvePensionRevision(revisionId, expectedRevision, role, issuer, subject, actorName, reason)
    }

    suspend fun pensionApprovals(revisionId: UUID): List<PensionRevisionApproval> {
        val revision = findRevision(revisionId)
        if (revision.schemaRef.id != PENSION_SCHEMA || revision.schemaRef.version != 2) {
            throw CatalogConflictException("revision is not pension pack v2")
        }
        val digest = catalogJson.approvalDigest(revision)
        return repository.pensionApprovals(revisionId).filter { it.digest == digest }
    }

    suspend fun findPublished(offeringId: UUID, effectiveAt: Instant): ProductRevision =
        repository.findPublished(offeringId, effectiveAt)
            ?: throw CatalogNotFoundException("no published offering $offeringId is effective at $effectiveAt")

    private suspend fun validateOrThrow(
        ref: SchemaRef,
        attributes: com.openbank.productcatalog.domain.catalog.CatalogValue.ObjectValue,
    ) {
        val schema = findSchema(ref)
        when (val result = validator.validate(schema, attributes)) {
            SchemaValidationResult.Valid -> Unit
            is SchemaValidationResult.Invalid -> throw CatalogValidationException(result.violations)
        }
    }

    private fun requireExpected(revision: ProductRevision, expected: Long) {
        if (revision.revision != expected) {
            throw CatalogPreconditionFailedException(
                "revision ${revision.id} was modified (expected $expected, current ${revision.revision})",
            )
        }
    }

    private companion object {
        const val PENSION_SCHEMA = "org.openbank.retirement.pension-savings"
        const val REVIEWED_STATUS = "LEGAL_AND_COMMERCIAL_REVIEWED"
        const val MAX_APPROVAL_REASON_LENGTH = 4_096
    }

    /**
     * Relationships retain stable offering references rather than copying terms. Checking them
     * while drafting makes invalid references visible before an approval is requested.
     */
    private suspend fun validateRelationshipReferences(offeringId: UUID, content: RevisionContent) {
        val relationships = content.relationships
        require(relationships.none { it.targetOfferingId == offeringId }) {
            "an offering cannot reference itself"
        }
        require(relationships.distinctBy { it.kind to it.targetOfferingId }.size == relationships.size) {
            "offering relationships must be unique"
        }
        relationships.forEach { relationship -> findOffering(relationship.targetOfferingId) }
    }

    /** A published bundle may contain only effective, published offerings and may not form a cycle. */
    private suspend fun requirePublishableBundleComponents(draft: ProductRevision, effectiveAt: Instant) {
        val bundleMarket = findOffering(draft.offeringId).market
        draft.content.relationships
            .filter { it.kind == RelationshipKind.BUNDLE }
            .forEach { relationship ->
                requirePublishedBundlePath(
                    offeringId = relationship.targetOfferingId,
                    effectiveAt = effectiveAt,
                    path = setOf(draft.offeringId),
                    bundleMarket = bundleMarket,
                )
            }
    }

    private suspend fun requirePublishedBundlePath(
        offeringId: UUID,
        effectiveAt: Instant,
        path: Set<UUID>,
        bundleMarket: MarketContext,
    ) {
        if (offeringId in path) {
            throw CatalogConflictException("bundle relationships must not form a cycle")
        }
        requireBundleMarketCompatibility(bundleMarket, findOffering(offeringId).market, offeringId)
        val component = repository.findPublished(offeringId, effectiveAt)
            ?: throw CatalogConflictException("bundle component $offeringId is not published")
        component.content.relationships
            .filter { it.kind == RelationshipKind.BUNDLE }
            .forEach { child ->
                requirePublishedBundlePath(child.targetOfferingId, effectiveAt, path + offeringId, bundleMarket)
            }
    }

    /** A bundle can narrow an audience, but it must never make a component available more broadly. */
    private fun requireBundleMarketCompatibility(
        bundle: MarketContext,
        component: MarketContext,
        componentOfferingId: UUID,
    ) {
        listOf(
            "brands" to (bundle.brands to component.brands),
            "countries" to (bundle.countries to component.countries),
            "channels" to (bundle.channels to component.channels),
            "segments" to (bundle.segments to component.segments),
            "locales" to (bundle.locales to component.locales),
        ).forEach { (dimension, audiences) ->
            val (bundleAudience, componentAudience) = audiences
            require(
                componentAudience.isEmpty() ||
                    (bundleAudience.isNotEmpty() && bundleAudience.all(componentAudience::contains)),
            ) {
                "bundle $dimension must not be broader than component $componentOfferingId"
            }
        }
    }
}
