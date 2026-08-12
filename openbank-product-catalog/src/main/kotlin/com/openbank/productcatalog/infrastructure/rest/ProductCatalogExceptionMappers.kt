// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.domain.identifiers.Ids
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
