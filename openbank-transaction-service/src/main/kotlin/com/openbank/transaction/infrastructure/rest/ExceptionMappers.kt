// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.transaction.application.usecase.TransactionNotFoundException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

@Provider
class TransactionNotFoundExceptionMapper : ExceptionMapper<TransactionNotFoundException> {
    override fun toResponse(exception: TransactionNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND)
            .entity(mapOf("error" to (exception.message ?: "Not found")))
            .type(MediaType.APPLICATION_JSON)
            .build()
}

// NB: IllegalArgumentException is intentionally NOT mapped here. The canonical
// mapping lives in openbank-libs CommonExceptionMappers (IllegalArgumentException
// -> 400 VALIDATION_ERROR, IllegalStateException -> 422 BUSINESS_RULE_VIOLATION).
// A second service-local mapper for the same type would collide with the shared
// one non-deterministically, so domain invariants that must surface as 422 throw
// IllegalStateException (check(...)) rather than IllegalArgumentException.

// ExceptionMapper<Exception> (GlobalExceptionMapper) is intentionally NOT declared here.
// openbank-libs auto-registers GenericExceptionMapper (Exception → 500, correlation-aware) and
// WebApplicationExceptionMapper (WebApplicationException → pass-through) via Jandex. A second
// @Provider for the same type collides non-deterministically (ADR-0049 D4).
