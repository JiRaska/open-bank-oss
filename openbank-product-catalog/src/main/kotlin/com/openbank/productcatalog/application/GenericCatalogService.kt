// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.productcatalog.application.port.out.GenericCatalogRepository
import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.CatalogSchemaValidator
import com.openbank.productcatalog.domain.catalog.MarketContext
import com.openbank.productcatalog.domain.catalog.ProductOffering
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.ProductSpecification
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

class CatalogNotFoundException(message: String) : RuntimeException(message)
class CatalogValidationException(val violations: List<SchemaViolation>) : RuntimeException("catalog content is invalid")
class CatalogPreconditionFailedException(message: String) : RuntimeException(message)
class CatalogPreconditionRequiredException(message: String) : RuntimeException(message)
class CatalogConflictException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
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
        val hash = catalogJson.sha256(catalogJson.toContentNode(draft.content))
        return repository.publishDraft(
            revisionId = revisionId,
            expectedRevision = expectedRevision,
            checkerId = checkerId,
            reason = reason,
            contentHash = hash,
            at = Instant.now(clock),
        )
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
}
