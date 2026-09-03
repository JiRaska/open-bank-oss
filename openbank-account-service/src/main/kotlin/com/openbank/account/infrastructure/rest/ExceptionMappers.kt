// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.out.AccountScreeningUnavailableException
import com.openbank.account.application.usecase.AccountNotEmptyException
import com.openbank.account.application.usecase.AccountNotFoundException
import com.openbank.account.application.usecase.AccountOpeningBlockedByScreeningException
import com.openbank.account.application.usecase.AccountUpdateConflictException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.logging.Log
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant
import java.util.UUID

// Generic IllegalArgument/IllegalState/Exception mappers are provided by openbank-libs
// (com.openbank.libs.api.error.CommonExceptionMappers) with log-correlated traceIds.
// Declaring them here too is a non-deterministic JAX-RS @Provider collision (ADR-0048/0049 D4).
@Provider
class AccountNotFoundExceptionMapper : ExceptionMapper<AccountNotFoundException> {
    override fun toResponse(exception: AccountNotFoundException): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(
            ApiError(
                traceId = UUID.randomUUID().toString(),
                status = 404,
                code = "ACCOUNT_NOT_FOUND",
                message = exception.message ?: "Account not found",
                timestamp = Instant.now(),
            ),
        )
        .build()
}

// 409 concurrent-modification conflict (#465): a lifecycle update (freeze/unfreeze/close/
// activate/goal) raced another writer — the caller read a version the row no longer has.
// Dedicated type so the status is deterministic (see issue #526 on the IllegalStateException
// mapper collision between libs-runtime and services).
@Provider
class AccountUpdateConflictExceptionMapper : ExceptionMapper<AccountUpdateConflictException> {
    override fun toResponse(exception: AccountUpdateConflictException): Response =
        Response.status(Response.Status.CONFLICT)
            .entity(
                ApiError(
                    traceId = Ids.randomId().toString(),
                    status = 409,
                    code = "CONCURRENT_MODIFICATION",
                    message = exception.message ?: "Account was modified concurrently",
                    timestamp = Instant.now(),
                ),
            )
            .build()
}

// 422 — closing an account that still holds money is a well-formed request against the wrong
// state, not a conflict with another writer (409) or a bad request shape (400).
@Provider
class AccountNotEmptyExceptionMapper : ExceptionMapper<AccountNotEmptyException> {
    override fun toResponse(exception: AccountNotEmptyException): Response = Response.status(UNPROCESSABLE_ENTITY)
        .entity(
            ApiError(
                traceId = Ids.randomId().toString(),
                status = UNPROCESSABLE_ENTITY,
                code = "ACCOUNT_NOT_EMPTY",
                message = exception.message ?: "Account still holds money",
                timestamp = Instant.now(),
            ),
        )
        .build()
}

// 503 — the sanctions gate fails closed (ADR-0032 §C): when sanctions-service is unreachable the
// account is NOT opened. That is a dependency-availability failure, not a client error, and it was
// answering 500 INTERNAL_ERROR because no mapper claimed the type. Same reasoning as libs-runtime's
// PolicyDecisionExceptionMapper, which maps a PDP outage to 503 precisely so an outage reads as
// "the platform is broken" rather than being laundered into a caller-facing 4xx.
//
// Retry-After is safe to advertise here and only here: `POST /api/v1/accounts` requires an
// Idempotency-Key, and this failure occurs strictly before any write (no account row, no
// idempotency record, no outbox entry), so replaying the identical request is a genuine retry
// rather than a second open. The message is the exception's own constant text — it carries no
// caller data and no upstream detail; the cause is deliberately not surfaced.
@Provider
class AccountScreeningUnavailableExceptionMapper : ExceptionMapper<AccountScreeningUnavailableException> {
    override fun toResponse(exception: AccountScreeningUnavailableException): Response {
        // SanctionsScreeningAdapter already logs the cause at WARN/ERROR at the throw site, so this
        // logs only what it alone knows: the traceId the caller is about to be handed.
        val traceId = Ids.randomId().toString()
        Log.warnf("account opening refused — sanctions screening unavailable (traceId=%s)", traceId)
        return Response.status(SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, SCREENING_RETRY_AFTER_SECONDS)
            .entity(
                ApiError(
                    traceId = traceId,
                    status = SERVICE_UNAVAILABLE,
                    code = "SCREENING_UNAVAILABLE",
                    message = "Sanctions screening is unavailable; account opening is blocked. Retry later.",
                    timestamp = Instant.now(),
                ),
            )
            .build()
    }
}

// 422 — a sanctions HIT/REVIEW is a policy refusal of a well-formed request, the same class as
// ProductNotEligibleException three lines below it in AccountService. It answered 500 for the same
// reason as the mapper above: no mapper claimed the type.
//
// Deliberately NOT solved by re-parenting the exception to IllegalStateException, which is what
// ProductNotEligibleException does and is otherwise the smaller change. libs-runtime's
// IllegalStateExceptionMapper echoes `exception.message` into the response body, and THIS message
// embeds `matched: <name>` — the sanctions-list name the screen hit. That one-word fix would have
// turned the 422 into a sanctions-screening disclosure to the caller. (Today's 500 does not leak
// it: GenericExceptionMapper never echoes a message. The leak is what the obvious fix introduces,
// which is why the response body is asserted in AccountOpeningScreeningStatusIT and not just the
// status.) So the body is a fixed string and the matched name stays in the WARN line below —
// nothing else records it, since a blocked open persists no row at all.
@Provider
class AccountOpeningBlockedByScreeningExceptionMapper : ExceptionMapper<AccountOpeningBlockedByScreeningException> {
    override fun toResponse(exception: AccountOpeningBlockedByScreeningException): Response {
        val traceId = Ids.randomId().toString()
        Log.warnf("account opening blocked by sanctions screening (traceId=%s): %s", traceId, exception.message)
        return Response.status(UNPROCESSABLE_ENTITY)
            .entity(
                ApiError(
                    traceId = traceId,
                    status = UNPROCESSABLE_ENTITY,
                    code = "ACCOUNT_OPENING_BLOCKED",
                    message = "Account opening is blocked by compliance screening.",
                    timestamp = Instant.now(),
                ),
            )
            .build()
    }
}

// SelfApprovalNotAllowedMapper / InvalidApprovalStateMapper (403/409) moved to
// openbank-libs-runtime's CommonExceptionMappers (issue #1394) — a service-local copy of the
// same exact type would collide non-deterministically with the shared one (issue #526).

private const val UNPROCESSABLE_ENTITY = 422
private const val SERVICE_UNAVAILABLE = 503

// Seconds. A sanctions-service outage is a restart/redeploy-shaped event, not a rate limit — long
// enough that a client honouring it does not add load to a service that is already down.
private const val SCREENING_RETRY_AFTER_SECONDS = "30"
