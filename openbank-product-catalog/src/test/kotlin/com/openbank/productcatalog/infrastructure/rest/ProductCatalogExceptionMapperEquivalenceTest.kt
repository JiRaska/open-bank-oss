// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.DomainExceptionMapper
import com.openbank.libs.api.error.ProblemDetail
import com.openbank.libs.api.error.ResourceConflictExceptionMapper
import com.openbank.libs.api.error.ResourceNotFoundExceptionMapper
import com.openbank.productcatalog.application.CatalogConflictException
import com.openbank.productcatalog.application.CatalogNotFoundException
import com.openbank.productcatalog.application.DuplicateProductCodeException
import com.openbank.productcatalog.application.ProductNotFoundException
import com.openbank.productcatalog.application.ProductUpdateConflictException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves that the shared libs-runtime mappers preserve the established status, code, and message
 * when local mappers are deleted. The ADR-0326 pilot additionally pins the legacy `error` member
 * and the typed RFC 9457 representation for product-not-found and duplicate-code responses.
 *
 * These tests fail to compile if any migrated exception stops extending its libs-domain base.
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

    @Test
    fun `ProductNotFoundException keeps its legacy error and gains the typed 404 representation`() {
        val message = "Product prod-1 not found"

        val response = DomainExceptionMapper().toResponse(ProductNotFoundException(message))

        assertThat(response.status).isEqualTo(404)
        val problem = response.entity as ProblemDetail
        assertThat(problem.status).isEqualTo(404)
        assertThat(problem.error).isEqualTo(message)
        assertThat(problem.code).isEqualTo("PRODUCT_NOT_FOUND")
        assertThat(problem.message).isEqualTo(message)
        assertThat(problem.type).isEqualTo("urn:openbank:error:product-not-found")
        assertThat(problem.title).isEqualTo("The product does not exist")
        assertThat(problem.detail).isEqualTo(message)
        assertThat(problem.instance).isEqualTo("urn:openbank:error-occurrence:${problem.correlationId}")
        assertThat(problem.retryable).isFalse()
        assertThat(problem.traceId).isEqualTo(problem.correlationId)
    }

    @Test
    fun `DuplicateProductCodeException keeps its legacy error and gains the typed 409 representation`() {
        val message = "Product with code 'CURRENT' already exists"

        val response = DomainExceptionMapper().toResponse(DuplicateProductCodeException(message))

        assertThat(response.status).isEqualTo(409)
        val problem = response.entity as ProblemDetail
        assertThat(problem.status).isEqualTo(409)
        assertThat(problem.error).isEqualTo(message)
        assertThat(problem.code).isEqualTo("PRODUCT_CODE_ALREADY_EXISTS")
        assertThat(problem.message).isEqualTo(message)
        assertThat(problem.type).isEqualTo("urn:openbank:error:product-code-already-exists")
        assertThat(problem.title).isEqualTo("The product code already exists")
        assertThat(problem.detail).isEqualTo(message)
        assertThat(problem.instance).isEqualTo("urn:openbank:error-occurrence:${problem.correlationId}")
        assertThat(problem.retryable).isFalse()
        assertThat(problem.traceId).isEqualTo(problem.correlationId)
    }
}
