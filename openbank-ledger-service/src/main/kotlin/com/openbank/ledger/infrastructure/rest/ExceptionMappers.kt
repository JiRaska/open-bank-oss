// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.usecase.ClosedFiscalPeriodException
import com.openbank.ledger.application.usecase.GlAccountValidationException
import com.openbank.ledger.application.usecase.JournalNotFoundException
import com.openbank.ledger.application.usecase.JournalReversalConflictException
import com.openbank.ledger.application.usecase.YearCloseConflictException
import com.openbank.ledger.application.usecase.YearCloseNotFoundException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status.CONFLICT
import jakarta.ws.rs.core.Response.Status.NOT_FOUND
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

@Provider
class JournalNotFoundExceptionMapper : ExceptionMapper<JournalNotFoundException> {
    override fun toResponse(exception: JournalNotFoundException): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(mapOf("error" to (exception.message ?: "Not found")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class GlAccountValidationExceptionMapper : ExceptionMapper<GlAccountValidationException> {
    override fun toResponse(exception: GlAccountValidationException): Response = Response.status(422)
        .entity(mapOf("error" to (exception.message ?: "Invalid GL account")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class IllegalArgumentExceptionMapper : ExceptionMapper<IllegalArgumentException> {
    override fun toResponse(exception: IllegalArgumentException): Response = Response.status(422)
        .entity(mapOf("error" to (exception.message ?: "Unprocessable entity")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class IllegalStateExceptionMapper : ExceptionMapper<IllegalStateException> {
    private val log = Logger.getLogger(IllegalStateExceptionMapper::class.java)

    override fun toResponse(exception: IllegalStateException): Response {
        log.errorf(exception, "Illegal state: %s", exception.message)
        return Response.status(Response.Status.CONFLICT)
            .entity(mapOf("error" to (exception.message ?: "Conflict")))
            .type(MediaType.APPLICATION_JSON)
            .build()
    }
}

// 409 reversal conflict (#465): repeated or concurrent reversal of the same journal entry.
// Dedicated type so the status is deterministic — IllegalStateException has TWO registered
// mappers (libs-runtime 422 vs this service's 409) and JAX-RS picks between same-type
// providers non-deterministically (ADR-0049 D4).
@Provider
class JournalReversalConflictExceptionMapper : ExceptionMapper<JournalReversalConflictException> {
    override fun toResponse(exception: JournalReversalConflictException): Response = Response.status(CONFLICT)
        .entity(mapOf("error" to (exception.message ?: "Journal already reversed")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class YearCloseNotFoundExceptionMapper : ExceptionMapper<YearCloseNotFoundException> {
    override fun toResponse(exception: YearCloseNotFoundException): Response = Response.status(NOT_FOUND)
        .entity(mapOf("error" to (exception.message ?: "Not found")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// 409 fail-closed (ADR-0078 D5): attested-year immutability, hash drift at attestation,
// unbalanced GL, attempt to attest a fiscal year that has not ended.
@Provider
class YearCloseConflictExceptionMapper : ExceptionMapper<YearCloseConflictException> {
    override fun toResponse(exception: YearCloseConflictException): Response = Response.status(CONFLICT)
        .entity(mapOf("error" to (exception.message ?: "Conflict")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// 409 period lock (#869): a posting or reversal targeted an ATTESTED (closed) fiscal year.
@Provider
class ClosedFiscalPeriodExceptionMapper : ExceptionMapper<ClosedFiscalPeriodException> {
    override fun toResponse(exception: ClosedFiscalPeriodException): Response = Response.status(CONFLICT)
        .entity(mapOf("error" to (exception.message ?: "Fiscal period is closed")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// ExceptionMapper<Exception> (GlobalExceptionMapper) is intentionally NOT declared here.
// openbank-libs auto-registers GenericExceptionMapper (Exception → 500, correlation-aware) and
// WebApplicationExceptionMapper (WebApplicationException → pass-through) via Jandex. A second
// @Provider for the same type would collide non-deterministically (ADR-0049 D4).
//
// Ledger-specific status codes:
//   IllegalArgumentException → 422 (GL validation failure, not a generic 400)
//   IllegalStateException    → 409 Conflict (double-entry invariant violation)
// These override libs' defaults intentionally and are kept above.
