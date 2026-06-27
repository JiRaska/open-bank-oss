// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.infrastructure.rest

import com.openbank.tppregistry.application.usecase.TppAlreadyExistsException
import com.openbank.tppregistry.application.usecase.EbaSyncUnavailableException
import com.openbank.tppregistry.application.usecase.TppNotFoundException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class TppNotFoundMapper : ExceptionMapper<TppNotFoundException> {
    override fun toResponse(e: TppNotFoundException): Response =
        Response.status(404).entity(mapOf("error" to "NOT_FOUND", "message" to e.message)).build()
}

@Provider
class TppAlreadyExistsMapper : ExceptionMapper<TppAlreadyExistsException> {
    override fun toResponse(e: TppAlreadyExistsException): Response =
        Response.status(409).entity(mapOf("error" to "CONFLICT", "message" to e.message)).build()
}

// IllegalArgumentException is intentionally NOT mapped here. openbank-libs auto-registers
// IllegalArgumentExceptionMapper (ADR-0049 D4): a single @Provider for that type across the
// classpath returns the canonical, correlation-aware ApiError (traceId/code/status) the
// openapi already documents — instead of this service's bespoke {"error":"BAD_REQUEST"} body.
// Two @Provider mappers for the same exception type was a non-deterministic JAX-RS registration.

@Provider
class EbaSyncUnavailableMapper : ExceptionMapper<EbaSyncUnavailableException> {
    override fun toResponse(e: EbaSyncUnavailableException): Response =
        Response.status(503).entity(mapOf("error" to "SERVICE_UNAVAILABLE", "message" to "EBA sync is temporarily unavailable")).build()
}
