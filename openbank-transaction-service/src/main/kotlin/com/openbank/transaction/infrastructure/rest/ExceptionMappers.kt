// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.rest

import com.openbank.transaction.application.usecase.TransactionNotFoundException
import com.openbank.transaction.application.usecase.TransactionUpdateConflictException
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

// 409 concurrent-modification conflict (#465): a state transition (complete/fail/reverse) raced
// another writer — the caller read a version the row no longer has. Dedicated type so the status
// is deterministic (see issue #526 on the IllegalStateException mapper collision between
// libs-runtime and services).
@Provider
class TransactionUpdateConflictExceptionMapper : ExceptionMapper<TransactionUpdateConflictException> {
    override fun toResponse(exception: TransactionUpdateConflictException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(mapOf("error" to (exception.message ?: "Transaction was modified concurrently")))
            .type(MediaType.APPLICATION_JSON)
            .build()
}
