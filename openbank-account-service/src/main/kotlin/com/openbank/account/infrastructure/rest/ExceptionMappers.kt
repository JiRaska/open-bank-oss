// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.rest

import com.openbank.account.application.port.out.AccountScreeningUnavailableException
import com.openbank.account.application.usecase.AccountNotEmptyException
import com.openbank.account.application.usecase.AccountNotFoundException
import com.openbank.account.application.usecase.AccountOpeningBlockedByScreeningException
import com.openbank.account.application.usecase.AccountUpdateConflictException
import com.openbank.account.application.usecase.AuthorizationNotFoundException
import com.openbank.account.application.usecase.AuthorizationNotOnAccountException
import com.openbank.libs.api.error.ApiError
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.ForbiddenException
import io.quarkus.security.UnauthorizedException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
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

// 404 — revoking an authorization that does not exist, or that exists on a DIFFERENT account.
//
// Both types previously had no mapper at all, so they fell through to libs-runtime's
// GenericExceptionMapper and every miss answered 500 INTERNAL_ERROR. Measured on the
// authenticated-fuzz lane (#5913, run 33720692606, traceId 0466cbbc-17c8-4dee-b40a-94c5bfdcd5d9):
//
//     DELETE /api/v1/accounts/{accountId}/authorizations/{authorizationId}
//     ERROR [GenericExceptionMapper] Unhandled exception:
//       AuthorizationNotFoundException: Authorization not found: e3e70682-...
//
// A "not found" is the caller's fact, not a server fault. account-service is money-path, so
// reporting it as 5xx inflates the error budget and buries real faults in the same bucket.
//
// A service-local @Provider on the service's own domain type is the pattern libs-runtime's
// CommonExceptionMappers header sanctions (it names AccountNotFoundExceptionMapper as the
// example) and the shape the three mappers above and ProposalExceptionMappers already use.
// Issue #526 forbids the opposite case — a second mapper for a type libs-runtime ALREADY
// registers (IllegalArgumentException / IllegalStateException / Exception), where the two
// @Providers collide non-deterministically per request. Neither type here is one of those.

/**
 * Both mappers answer with a byte-identical body ON PURPOSE.
 *
 * `AuthorizationNotOnAccountException` means the id resolves to a real row owned by some other
 * account. Echoing that — the exception's own message names both ids — would turn this endpoint
 * into an existence oracle: an operator scoped to account A could tell "this authorization id
 * exists somewhere" from "this id does not exist" by reading the status or the message. So the
 * distinction stays in the log, where it is diagnosable, and never reaches the wire. Same
 * reasoning the sibling account-access endpoint applies to an ownership mismatch.
 */
private const val AUTHORIZATION_NOT_FOUND_MESSAGE = "Authorization not found on this account"

private fun authorizationNotFound() = Response.status(Response.Status.NOT_FOUND)
    .entity(
        ApiError(
            traceId = Ids.randomId().toString(),
            status = 404,
            code = "AUTHORIZATION_NOT_FOUND",
            message = AUTHORIZATION_NOT_FOUND_MESSAGE,
            timestamp = Instant.now(),
        ),
    )
    .build()

@Provider
class AuthorizationNotFoundExceptionMapper : ExceptionMapper<AuthorizationNotFoundException> {
    override fun toResponse(exception: AuthorizationNotFoundException): Response = authorizationNotFound()
}

@Provider
class AuthorizationNotOnAccountExceptionMapper : ExceptionMapper<AuthorizationNotOnAccountException> {
    private val log = Logger.getLogger(AuthorizationNotOnAccountExceptionMapper::class.java)

    override fun toResponse(exception: AuthorizationNotOnAccountException): Response {
        // WARN, not silence: a caller walking authorization ids against an account they hold is
        // the shape this 404 deliberately hides from them, and the log is the only place left
        // where the two cases are still distinguishable.
        log.warnf("authorization/account mismatch on revoke: %s", exception.message)
        return authorizationNotFound()
    }
}

// SelfApprovalNotAllowedMapper / InvalidApprovalStateMapper (403/409) moved to
// openbank-libs-runtime's CommonExceptionMappers (issue #1394) — a service-local copy of the
// same exact type would collide non-deterministically with the shared one (issue #526).

// --- Sanctions-screening outcomes (#8512) -----------------------------------------------------
//
// Both screening exceptions previously extended a bare RuntimeException with no mapper, so
// both rendered 500 INTERNAL_ERROR through GenericExceptionMapper. Neither is a server fault:
// one is an upstream-dependency outage (the gate fails closed, ADR-0032 §C), the other a
// well-formed request correctly refused by policy. The 500 also logged every routine refusal
// at ERROR with a full stack trace, inflating a money-path service's error budget.
//
// THE TRAP THE ISSUE NAMES, and why these are dedicated mappers rather than a one-word
// re-parenting to IllegalStateException: libs-runtime's IllegalStateExceptionMapper answers
// 422 with `message = exception.message`, and AccountOpeningBlockedByScreeningException's
// message carries the MATCHED SANCTIONS NAME and the partyId. Correct status, free
// sanctions-list oracle on the wire. These bodies therefore name neither the matched name nor
// the party; the distinguishing detail goes to WARN (no stack), where compliance already
// reads it today.

private const val SCREENING_REFUSED_MESSAGE = "Account opening refused by screening policy"

@Provider
class AccountOpeningBlockedByScreeningExceptionMapper : ExceptionMapper<AccountOpeningBlockedByScreeningException> {
    private val log = Logger.getLogger(AccountOpeningBlockedByScreeningExceptionMapper::class.java)

    override fun toResponse(exception: AccountOpeningBlockedByScreeningException): Response {
        // The matched name stays server-side. A caller who could vary the submitted name and
        // read back whether it matched would have a free sanctions-list oracle (#8512).
        log.warnf("account opening blocked by screening: %s", exception.message)
        return Response.status(UNPROCESSABLE_ENTITY)
            .entity(
                ApiError(
                    traceId = Ids.randomId().toString(),
                    status = UNPROCESSABLE_ENTITY,
                    code = "ACCOUNT_OPENING_BLOCKED",
                    message = SCREENING_REFUSED_MESSAGE,
                    timestamp = Instant.now(),
                ),
            )
            .build()
    }
}

@Provider
class AccountScreeningUnavailableExceptionMapper : ExceptionMapper<AccountScreeningUnavailableException> {
    private val log = Logger.getLogger(AccountScreeningUnavailableExceptionMapper::class.java)

    override fun toResponse(exception: AccountScreeningUnavailableException): Response {
        // WARN, not ERROR: an upstream outage is an availability fact the caller can retry,
        // not a defect in this service. No stack trace — the cause is a connection failure.
        log.warnf("sanctions screening unavailable, account opening blocked: %s", exception.cause?.message)
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .header("Retry-After", "30")
            .entity(
                ApiError(
                    traceId = Ids.randomId().toString(),
                    status = Response.Status.SERVICE_UNAVAILABLE.statusCode,
                    code = "SCREENING_UNAVAILABLE",
                    message = "Sanctions screening is temporarily unavailable; retry later",
                    timestamp = Instant.now(),
                ),
            )
            .build()
    }
}

// Security-abort responses must be the JSON error envelope like every other error here.
// Without these mappers Quarkus REST's built-in handling writes the exception MESSAGE as a
// plain-text entity while the response keeps the resource's negotiated application/json
// content-type — a 401 whose body is the literal text "Not Authenticated" under a JSON
// content-type. Any client parsing the error as JSON (and Pact-JVM's matching engine, which
// plans the body comparison from the content-type) chokes on it: measured on the anonymous
// IBAN-lookup pact replay (#8803). A user @Provider mapper wins over Quarkus' built-in.
// These live in the SERVICE, not libs-runtime, on purpose: a shared-library @Provider naming
// an `io.quarkus.security` type would be loaded by ArC in every consumer, including services
// without quarkus-security on the classpath — the #6240 boot-failure class, enforced by the
// provider-type-classpath gate. Fleet-wide registration is tracked as a follow-up (#8875 is
// the party-service instance of the same latent defect).
@Provider
class QuarkusUnauthorizedExceptionMapper : ExceptionMapper<UnauthorizedException> {
    override fun toResponse(exception: UnauthorizedException): Response = Response.status(Response.Status.UNAUTHORIZED)
        .entity(
            ApiError(
                traceId = Ids.randomId().toString(),
                status = Response.Status.UNAUTHORIZED.statusCode,
                code = "UNAUTHORIZED",
                message = "Unauthorized",
                timestamp = Instant.now(),
            ),
        )
        .build()
}

@Provider
class QuarkusAuthenticationFailedExceptionMapper : ExceptionMapper<AuthenticationFailedException> {
    override fun toResponse(exception: AuthenticationFailedException): Response =
        Response.status(Response.Status.UNAUTHORIZED)
            .entity(
                ApiError(
                    traceId = Ids.randomId().toString(),
                    status = Response.Status.UNAUTHORIZED.statusCode,
                    code = "UNAUTHORIZED",
                    message = "Unauthorized",
                    timestamp = Instant.now(),
                ),
            )
            .build()
}

@Provider
class QuarkusForbiddenExceptionMapper : ExceptionMapper<ForbiddenException> {
    override fun toResponse(exception: ForbiddenException): Response = Response.status(Response.Status.FORBIDDEN)
        .entity(
            ApiError(
                traceId = Ids.randomId().toString(),
                status = Response.Status.FORBIDDEN.statusCode,
                code = "FORBIDDEN",
                message = "Forbidden",
                timestamp = Instant.now(),
            ),
        )
        .build()
}

private const val UNPROCESSABLE_ENTITY = 422
