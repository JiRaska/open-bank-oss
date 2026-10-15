// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application.port.out

import com.openbank.productcatalog.domain.catalog.CatalogSchema
import com.openbank.productcatalog.domain.catalog.ProductOffering
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.ProductSpecification
import com.openbank.productcatalog.domain.catalog.SchemaRef
import java.time.Instant
import java.util.UUID

enum class PensionApprovalRole { LEGAL_COUNSEL, PRODUCT_OWNER }

data class PensionRevisionApproval(
    val revisionId: UUID,
    val role: PensionApprovalRole,
    val issuer: String,
    val subject: String,
    val digest: String,
    val reason: String,
    val approvedAt: Instant,
)

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
    suspend fun approvePensionRevision(
        revisionId: UUID,
        expectedRevision: Long,
        role: PensionApprovalRole,
        issuer: String,
        subject: String,
        actorName: String,
        reason: String,
    ): PensionRevisionApproval
    suspend fun pensionApprovals(revisionId: UUID): List<PensionRevisionApproval>
    suspend fun publishDraft(
        revisionId: UUID,
        expectedRevision: Long,
        checkerId: String,
        reason: String,
        contentHash: String,
        at: Instant,
    ): ProductRevision
    suspend fun findPublished(offeringId: UUID, effectiveAt: Instant): ProductRevision?
}
