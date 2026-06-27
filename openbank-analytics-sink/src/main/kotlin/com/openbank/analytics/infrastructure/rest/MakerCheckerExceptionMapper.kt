// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.rest

import com.openbank.libs.analytics.MakerCheckerViolation
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * Translates a [MakerCheckerViolation] (self-approval, illegal transition, missing proposal) into
 * HTTP 409 Conflict with a clear message, rather than a 500. Keeps the four-eyes rule's failures
 * legible to an operator without leaking a stack trace.
 */
@Provider
class MakerCheckerExceptionMapper : ExceptionMapper<MakerCheckerViolation> {
    override fun toResponse(exception: MakerCheckerViolation): Response =
        Response.status(Response.Status.CONFLICT)
            .type(MediaType.APPLICATION_JSON)
            .entity(mapOf("error" to "maker_checker_violation", "message" to (exception.message ?: "")))
            .build()
}
