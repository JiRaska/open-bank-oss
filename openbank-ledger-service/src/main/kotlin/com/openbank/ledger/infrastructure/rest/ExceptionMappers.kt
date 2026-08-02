// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.usecase.AccountingDayNotFoundException
import com.openbank.ledger.application.usecase.ClosedAccountingDayException
import com.openbank.ledger.application.usecase.ClosedFiscalPeriodException
import com.openbank.ledger.application.usecase.ClosedPeriodConflictException
import com.openbank.ledger.application.usecase.ClosedPeriodNotFoundException
import com.openbank.ledger.application.usecase.FrozenPeriodException
import com.openbank.ledger.application.usecase.GlAccountValidationException
import com.openbank.ledger.application.usecase.JournalNotFoundException
import com.openbank.ledger.application.usecase.JournalReversalConflictException
import com.openbank.ledger.application.usecase.YearCloseConflictException
import com.openbank.ledger.application.usecase.YearCloseNotFoundException
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.domain.model.LedgerValidationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status.CONFLICT
import jakarta.ws.rs.core.Response.Status.NOT_FOUND
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger

// Not in jakarta.ws.rs.core.Response.Status (JAX-RS's base enum stops short of 422).
private const val UNPROCESSABLE_ENTITY = 422

@Provider
class JournalNotFoundExceptionMapper : ExceptionMapper<JournalNotFoundException> {
    override fun toResponse(exception: JournalNotFoundException): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(mapOf("error" to (exception.message ?: "Not found")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class GlAccountValidationExceptionMapper : ExceptionMapper<GlAccountValidationException> {
    override fun toResponse(exception: GlAccountValidationException): Response = Response.status(UNPROCESSABLE_ENTITY)
        .entity(mapOf("error" to (exception.message ?: "Invalid GL account")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// LedgerValidationException/LedgerConflictException (domain layer) replace bare
// IllegalArgumentException/IllegalStateException (issue #526): a service-local
// ExceptionMapper for a JDK type openbank-libs-runtime already maps collides
// non-deterministically (JAX-RS has no defined tie-breaker between two same-type
// providers) — the 422/409 below were a per-request lottery against libs' 400/422, not
// the "intentional override" this file previously claimed.
@Provider
class LedgerValidationExceptionMapper : ExceptionMapper<LedgerValidationException> {
    override fun toResponse(exception: LedgerValidationException): Response = Response.status(UNPROCESSABLE_ENTITY)
        .entity(mapOf("error" to (exception.message ?: "Unprocessable entity")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class LedgerConflictExceptionMapper : ExceptionMapper<LedgerConflictException> {
    private val log = Logger.getLogger(LedgerConflictExceptionMapper::class.java)

    override fun toResponse(exception: LedgerConflictException): Response {
        log.errorf(exception, "Ledger conflict: %s", exception.message)
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

// 404 (ADR-0207 D2): the requested accounting day has not been opened.
@Provider
class AccountingDayNotFoundExceptionMapper : ExceptionMapper<AccountingDayNotFoundException> {
    override fun toResponse(exception: AccountingDayNotFoundException): Response = Response.status(NOT_FOUND)
        .entity(mapOf("error" to (exception.message ?: "Accounting day not found")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// 409 day lock (ADR-0207 D3): a posting targeted an accounting day that is no longer OPEN.
// Deliberately a DIFFERENT type from ClosedFiscalPeriodException even though both map to 409 —
// the two locks have different granularity and different remedies (a closed day is corrected
// forward into the current open day; a closed fiscal year needs an adjustment posting), and the
// caller can only tell them apart if the exception does.
@Provider
class ClosedAccountingDayExceptionMapper : ExceptionMapper<ClosedAccountingDayException> {
    override fun toResponse(exception: ClosedAccountingDayException): Response = Response.status(CONFLICT)
        .entity(mapOf("error" to (exception.message ?: "Accounting day is closed")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// 404 (ADR-0096 D1): no statutory close record for the requested period.
@Provider
class ClosedPeriodNotFoundExceptionMapper : ExceptionMapper<ClosedPeriodNotFoundException> {
    override fun toResponse(exception: ClosedPeriodNotFoundException): Response = Response.status(NOT_FOUND)
        .entity(mapOf("error" to (exception.message ?: "Closed period not found")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// 409 fail-closed (ADR-0096 D1): frozen immutability, hash drift at freeze, unbalanced GL,
// or an attempt to close a period that has not ended.
@Provider
class ClosedPeriodConflictExceptionMapper : ExceptionMapper<ClosedPeriodConflictException> {
    override fun toResponse(exception: ClosedPeriodConflictException): Response = Response.status(CONFLICT)
        .entity(mapOf("error" to (exception.message ?: "Conflict")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// 409 period lock (ADR-0096 D1): a posting targeted a FROZEN statutory period. A third distinct
// 409 type alongside ClosedAccountingDayException (day) and ClosedFiscalPeriodException (year) --
// same status, different granularity and different remedy, and the caller can only tell them
// apart if the exception does.
@Provider
class FrozenPeriodExceptionMapper : ExceptionMapper<FrozenPeriodException> {
    override fun toResponse(exception: FrozenPeriodException): Response = Response.status(CONFLICT)
        .entity(mapOf("error" to (exception.message ?: "Accounting period is frozen")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

// SelfApprovalNotAllowedMapper / InvalidApprovalStateMapper (403/409) moved to
// openbank-libs-runtime's CommonExceptionMappers (issue #1394) — a service-local copy of the
// same exact type would collide non-deterministically with the shared one (issue #526).

// ExceptionMapper<Exception> (GlobalExceptionMapper) is intentionally NOT declared here.
// openbank-libs auto-registers GenericExceptionMapper (Exception → 500, correlation-aware) and
// WebApplicationExceptionMapper (WebApplicationException → pass-through) via Jandex. A second
// @Provider for the same type would collide non-deterministically (ADR-0049 D4, issue #526) —
// the same reason IllegalArgumentException/IllegalStateException are no longer mapped directly
// here either (see LedgerValidationException/LedgerConflictException above): a service-local
// mapper for a libs-owned JDK type is a per-request lottery, not a reliable override. Ledger's
// 422/409 status codes are still ledger-specific — they're just reached through a dedicated
// domain exception type now, which JAX-RS resolves unambiguously.
