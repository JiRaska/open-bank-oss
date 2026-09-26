// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import com.openbank.productcatalog.application.CatalogConflictException
import com.openbank.productcatalog.application.CatalogNotFoundException
import com.openbank.productcatalog.application.ProductUpdateConflictException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #10911 phase 2: proves the shared libs-runtime mappers reproduce the deleted local
 * `CatalogNotFoundExceptionMapper` / `CatalogConflictExceptionMapper` /
 * `ProductUpdateConflictExceptionMapper` byte-for-byte on status, `code` and `message` (the fields
 * the wire contract / pact matchers actually pin -- traceId's source is unchanged here: both the
 * deleted local mappers and the shared ones read the correlation MDC first, falling back to a
 * random id).
 *
 * This test FAILS TO COMPILE if `CatalogNotFoundException` / `CatalogConflictException` /
 * `ProductUpdateConflictException` stop extending the libs-domain bases (the mapper parameter
 * types no longer match) -- that is the "fails without the fix" proof: reverting the exception
 * classes to plain `RuntimeException` breaks this file at compile time (confirmed manually before
 * committing: "type mismatch: inferred type is CatalogNotFoundException but
 * ResourceNotFoundException was expected").
 *
 * `ProductNotFoundException` / `DuplicateProductCodeException` are deliberately NOT migrated: their
 * mappers return the legacy `{"error": message}` body (preserved for the v1 Pact), which is not the
 * `ApiError` envelope the shared mappers produce.
 */
class ProductCatalogExceptionMapperEquivalenceTest {

    @Test
    fun `CatalogNotFoundException maps to 404 CATALOG_NOT_FOUND, same as the deleted local mapper`() {
        val exception = CatalogNotFoundException("catalog schema not found: card-config-v3")

        val response = ResourceNotFoundExceptionMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(404)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(404)
        assertThat(error.code).isEqualTo("CATALOG_NOT_FOUND")
        assertThat(error.message).isEqualTo("catalog schema not found: card-config-v3")
        assertThat(error.traceId).isNotBlank()
    }

    @Test
    fun `CatalogConflictException maps to 409 CATALOG_CONFLICT, same as the deleted local mapper`() {
        val exception = CatalogConflictException("catalog already published")

        val response = ResourceConflictExceptionMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(409)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(409)
        assertThat(error.code).isEqualTo("CATALOG_CONFLICT")
        assertThat(error.message).isEqualTo("catalog already published")
        assertThat(error.traceId).isNotBlank()
    }

    @Test
    fun `ProductUpdateConflictException maps to 409 CONCURRENT_MODIFICATION, same as the deleted local mapper`() {
        val exception = ProductUpdateConflictException("Product was modified concurrently")

        val response = ResourceConflictExceptionMapper().toResponse(exception)

        assertThat(response.status).isEqualTo(409)
        val error = response.entity as ApiError
        assertThat(error.status).isEqualTo(409)
        assertThat(error.code).isEqualTo("CONCURRENT_MODIFICATION")
        assertThat(error.message).isEqualTo("Product was modified concurrently")
        assertThat(error.traceId).isNotBlank()
    }
}
