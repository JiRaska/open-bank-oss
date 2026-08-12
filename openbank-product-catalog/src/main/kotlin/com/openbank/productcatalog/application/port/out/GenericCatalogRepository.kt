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

@Suppress("TooManyFunctions")
interface GenericCatalogRepository {
    suspend fun registerSchema(schema: CatalogSchema)
    suspend fun findSchema(ref: SchemaRef): CatalogSchema?
    suspend fun listSchemas(): List<CatalogSchema>
    suspend fun createSpecification(specification: ProductSpecification, actorId: String): ProductSpecification
    suspend fun findSpecification(id: UUID): ProductSpecification?
    suspend fun createOffering(offering: ProductOffering, actorId: String): ProductOffering
    suspend fun findOffering(id: UUID): ProductOffering?
    suspend fun createDraft(revision: ProductRevision, actorId: String): ProductRevision
    suspend fun nextRevisionNumber(offeringId: UUID): Long
    suspend fun findRevision(id: UUID): ProductRevision?
    suspend fun updateDraft(revision: ProductRevision, actorId: String): ProductRevision
    suspend fun publishDraft(
        revisionId: UUID,
        expectedRevision: Long,
        checkerId: String,
        reason: String,
        contentHash: String,
        at: Instant,
    ): ProductRevision
    suspend fun findPublished(specificationId: UUID, effectiveAt: Instant): ProductRevision?
}
