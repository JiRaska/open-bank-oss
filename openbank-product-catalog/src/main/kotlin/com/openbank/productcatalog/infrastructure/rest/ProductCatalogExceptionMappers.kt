// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.productcatalog.application.CatalogConflictException
import com.openbank.productcatalog.application.CatalogForbiddenException
import com.openbank.productcatalog.application.CatalogNotFoundException
import com.openbank.productcatalog.application.CatalogPreconditionFailedException
import com.openbank.productcatalog.application.CatalogPreconditionRequiredException
import com.openbank.productcatalog.application.CatalogValidationException
import com.openbank.productcatalog.application.DuplicateProductCodeException
import com.openbank.productcatalog.application.ProductNotFoundException
import com.openbank.productcatalog.application.ProductUpdateConflictException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.MDC
import java.time.Instant

@Provider
class DuplicateProductCodeExceptionMapper : ExceptionMapper<DuplicateProductCodeException> {
    override fun toResponse(exception: DuplicateProductCodeException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(mapOf("error" to (exception.message ?: "Product code already exists")))
            .build()
}

@Provider
class ProductUpdateConflictExceptionMapper : ExceptionMapper<ProductUpdateConflictException> {
    override fun toResponse(exception: ProductUpdateConflictException): Response = conflict(
        code = "CONCURRENT_MODIFICATION",
        message = exception.message ?: "Product was modified concurrently",
    )
}

/** Preserve the established v1 `{error}` body used by bank clients and its Pact. */
@Provider
class ProductNotFoundExceptionMapper : ExceptionMapper<ProductNotFoundException> {
    override fun toResponse(exception: ProductNotFoundException): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(mapOf("error" to (exception.message ?: "Product not found")))
        .build()
}

@Provider
class CatalogNotFoundExceptionMapper : ExceptionMapper<CatalogNotFoundException> {
    override fun toResponse(exception: CatalogNotFoundException): Response = apiError(
        status = Response.Status.NOT_FOUND.statusCode,
        code = "CATALOG_NOT_FOUND",
        message = exception.message ?: "Catalog resource not found",
    )
}

@Provider
class CatalogValidationExceptionMapper : ExceptionMapper<CatalogValidationException> {
    override fun toResponse(exception: CatalogValidationException): Response = Response.status(UNPROCESSABLE_ENTITY)
        .entity(
            mapOf(
                "type" to "https://openbank.dev/problems/catalog-validation",
                "title" to "Catalog content is invalid",
                "status" to UNPROCESSABLE_ENTITY,
                "violations" to exception.violations,
            ),
        ).build()
}

@Provider
class CatalogPreconditionRequiredExceptionMapper : ExceptionMapper<CatalogPreconditionRequiredException> {
    override fun toResponse(exception: CatalogPreconditionRequiredException): Response = apiError(
        status = PRECONDITION_REQUIRED,
        code = "PRECONDITION_REQUIRED",
        message = exception.message ?: "If-Match is required",
    )
}

@Provider
class CatalogPreconditionFailedExceptionMapper : ExceptionMapper<CatalogPreconditionFailedException> {
    override fun toResponse(exception: CatalogPreconditionFailedException): Response = apiError(
        status = Response.Status.PRECONDITION_FAILED.statusCode,
        code = "PRECONDITION_FAILED",
        message = exception.message ?: "Catalog resource changed",
    )
}

@Provider
class CatalogConflictExceptionMapper : ExceptionMapper<CatalogConflictException> {
    override fun toResponse(exception: CatalogConflictException): Response = apiError(
        status = Response.Status.CONFLICT.statusCode,
        code = "CATALOG_CONFLICT",
        message = exception.message ?: "Catalog state conflicts with the requested operation",
    )
}

@Provider
class CatalogForbiddenExceptionMapper : ExceptionMapper<CatalogForbiddenException> {
    override fun toResponse(exception: CatalogForbiddenException): Response = apiError(
        status = Response.Status.FORBIDDEN.statusCode,
        code = "FOUR_EYES_REQUIRED",
        message = exception.message ?: "A different operator must approve this change",
    )
}

private fun conflict(code: String, message: String): Response = Response.status(Response.Status.CONFLICT)
    .entity(
        ApiError(
            traceId = (MDC.get("correlationId") as? String) ?: Ids.randomId().toString(),
            status = Response.Status.CONFLICT.statusCode,
            code = code,
            message = message,
            timestamp = Instant.now(),
        ),
    )
    .build()

private fun apiError(status: Int, code: String, message: String): Response = Response.status(status)
    .entity(
        ApiError(
            traceId = (MDC.get("correlationId") as? String) ?: Ids.randomId().toString(),
            status = status,
            code = code,
            message = message,
            timestamp = Instant.now(),
        ),
    ).build()

private const val UNPROCESSABLE_ENTITY = 422
private const val PRECONDITION_REQUIRED = 428
