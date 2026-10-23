// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application.port.out

import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.ProductOffering
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.ProductSpecification
import com.openbank.productcatalog.domain.catalog.RevisionApproval
import com.openbank.productcatalog.domain.catalog.SchemaRef
import java.time.Instant
import java.util.UUID

@Suppress("TooManyFunctions")
interface GenericCatalogRepository {
    suspend fun registerSchema(schema: CatalogSchema)
    suspend fun findSchema(ref: SchemaRef): CatalogSchema?
    suspend fun listSchemas(): List<CatalogSchema>
    suspend fun createSpecification(specification: ProductSpecification, actorId: String): ProductSpecification
    suspend fun listSpecifications(): List<ProductSpecification>
    suspend fun findSpecification(id: UUID): ProductSpecification?
    suspend fun createOffering(offering: ProductOffering, actorId: String): ProductOffering
    suspend fun listOfferings(specificationId: UUID?): List<ProductOffering>
    suspend fun findOffering(id: UUID): ProductOffering?
    suspend fun createDraft(revision: ProductRevision, actorId: String): ProductRevision
    suspend fun listRevisions(offeringId: UUID): List<ProductRevision>
    suspend fun findRevision(id: UUID): ProductRevision?
    suspend fun updateDraft(revision: ProductRevision, actorId: String): ProductRevision
    suspend fun approveRevision(
        revisionId: UUID,
        expectedRevision: Long,
        role: String,
        issuer: String,
        subject: String,
        actorName: String,
        reason: String,
    ): RevisionApproval
    suspend fun revisionApprovals(revisionId: UUID): List<RevisionApproval>
    suspend fun publishDraft(
        revisionId: UUID,
        expectedRevision: Long,
        checkerId: String,
        reason: String,
        contentHash: String,
        at: Instant,
    ): ProductRevision
    suspend fun findPublished(offeringId: UUID, effectiveAt: Instant): ProductRevision?

    /** The published revision of the offering the v1 banking product [productId] is mapped to (ADR-0341). */
    suspend fun findPublishedForLegacyProduct(productId: UUID, effectiveAt: Instant): ProductRevision?
}
