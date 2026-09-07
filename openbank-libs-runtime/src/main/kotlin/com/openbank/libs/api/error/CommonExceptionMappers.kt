// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.api.error

import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.authz.PolicyDecisionException
import io.quarkus.security.UnauthorizedException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.hibernate.exception.ConstraintViolationException
import org.hibernate.exception.DataException
import org.jboss.logging.Logger
import org.jboss.logging.MDC
import java.io.CharConversionException
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID

/**
 * Service-wide default exception mappers. Auto-registered for every service that pulls
 * in openbank-libs (via Jandex index on the libs JAR).
 *
 * Services may register narrower mappers for their own business exceptions; JAX-RS picks
 * the most specific mapper, so adding `AccountNotFoundExceptionMapper : ExceptionMapper<AccountNotFoundException>`
 * still wins over the generic [NoSuchElementExceptionMapper] here.
 *
 * traceId is taken from the correlation MDC key set by [com.openbank.libs.web.CorrelationIdRequestFilter]
 * so error responses correlate to log lines for the same request. Falls back to a fresh UUID.
 */
private fun traceId(): String = (MDC.get("correlationId") as? String) ?: UUID.randomUUID().toString()

private fun apiError(status: Int, code: String, message: String, details: List<FieldError>? = null) = ApiError(
    traceId = traceId(),
    status = status,
    code = code,
    message = message,
    timestamp = Instant.now(),
    details = details,
)

@Provider
class IllegalArgumentExceptionMapper : ExceptionMapper<IllegalArgumentException> {
    override fun toResponse(exception: IllegalArgumentException): Response = Response.status(400)
        .entity(apiError(400, ErrorCode.VALIDATION_ERROR.code, exception.message ?: "Invalid request"))
        .build()
}

@Provider
class IllegalStateExceptionMapper : ExceptionMapper<IllegalStateException> {
    private val log = Logger.getLogger(IllegalStateExceptionMapper::class.java)
    override fun toResponse(exception: IllegalStateException): Response {
        log.warnf(exception, "business rule violation: %s", exception.message)
        return Response.status(422)
            .entity(apiError(422, "BUSINESS_RULE_VIOLATION", exception.message ?: "Business rule violation"))
            .build()
    }
}

/**
 * `DateTimeException` (and its `DateTimeParseException` subclass) → **400**.
 *
 * It extends `RuntimeException`, **not** `IllegalArgumentException`, so before this it fell all the
 * way through to [GenericExceptionMapper] and every unparseable date rendered as a 500. Resources
 * parse dates straight off the query string —
 * `date?.let { LocalDate.parse(it) }` — and `?date=` or `?date=null` are non-null strings, so the
 * `?.let` runs and `parse` throws.
 *
 * The first full authenticated-fuzz run hit this on five money-path endpoints (#3038): balance
 * reconciliation, fx CNB ingest, interest accrue-all / capitalize / accruals. A client sending a bad
 * date is a client error, and reporting it as 5xx inflates the error budget of those services and
 * keeps the fuzz lane red forever.
 *
 * Safe to map globally, unlike a general "bad input" guess: a `DateTimeException` reaching a
 * *resource boundary* is always a rejected value, never a server fault. A genuine server-side clock
 * or zone bug would not surface here as an escaping exception from request handling.
 */
@Provider
class DateTimeExceptionMapper : ExceptionMapper<DateTimeException> {
    override fun toResponse(exception: DateTimeException): Response =
        Response.status(ErrorCode.VALIDATION_ERROR.httpStatus)
            .entity(
                apiError(
                    ErrorCode.VALIDATION_ERROR.httpStatus,
                    ErrorCode.VALIDATION_ERROR.code,
                    exception.message ?: "Invalid date or time value",
                ),
            )
            .build()
}

@Provider
class NoSuchElementExceptionMapper : ExceptionMapper<NoSuchElementException> {
    override fun toResponse(exception: NoSuchElementException): Response = Response.status(404)
        .entity(apiError(404, ErrorCode.NOT_FOUND.code, exception.message ?: "Resource not found"))
        .build()
}

// ADR-0155: a checker can never decide their own PendingApproval — ApprovalStore.decide
// enforces this itself (defense-in-depth), surfaced here as a plain 403. Formerly duplicated
// verbatim across 10+ services (issue #1394) plus a divergent {"code","message"}-shaped copy in
// notification-service; a per-service copy of this exact type would collide non-deterministically
// with this one (issue #526's defect class), so this is now the ONLY registered mapper for it.
@Provider
class SelfApprovalNotAllowedMapper : ExceptionMapper<SelfApprovalNotAllowedException> {
    override fun toResponse(exception: SelfApprovalNotAllowedException): Response =
        Response.status(ErrorCode.FORBIDDEN.httpStatus)
            .entity(
                apiError(ErrorCode.FORBIDDEN.httpStatus, ErrorCode.FORBIDDEN.code, exception.message ?: "Forbidden"),
            )
            .build()
}

// decide()/markExecuted() reject re-deciding or re-consuming an approval that isn't in the
// expected status (e.g. an EXECUTED approval flipped back to APPROVED and replayed). See
// SelfApprovalNotAllowedMapper above for why this lives here instead of per-service.
@Provider
class InvalidApprovalStateMapper : ExceptionMapper<InvalidApprovalStateException> {
    override fun toResponse(exception: InvalidApprovalStateException): Response =
        Response.status(ErrorCode.CONFLICT.httpStatus)
            .entity(apiError(ErrorCode.CONFLICT.httpStatus, ErrorCode.CONFLICT.code, exception.message ?: "Conflict"))
            .build()
}

// A PDP outage (OPA sidecar unreachable, or no PolicyDecisionPoint wired while enforcing) is an
// availability failure, not a client error. AuthorizeInterceptor throws PolicyDecisionException; map
// it to 503 with its own code so an outage reads as "the platform is broken", not laundered into a
// 422 BUSINESS_RULE_VIOLATION ("the caller sent something invalid"). Keyed on the concrete domain
// type, this is immune to whatever the Kotlin suspend/coroutine bridge does to WebApplicationException
// subtypes at the JAX-RS boundary — the reason a thrown ServiceUnavailableException (503) was
// surfacing as 422 (issue #1797).
@Provider
class PolicyDecisionExceptionMapper : ExceptionMapper<PolicyDecisionException> {
    override fun toResponse(exception: PolicyDecisionException): Response =
        Response.status(ErrorCode.POLICY_DECISION_POINT_UNAVAILABLE.httpStatus)
            .entity(
                apiError(
                    ErrorCode.POLICY_DECISION_POINT_UNAVAILABLE.httpStatus,
                    ErrorCode.POLICY_DECISION_POINT_UNAVAILABLE.code,
                    exception.message ?: "Policy decision point unavailable",
                ),
            )
            .build()
}

// Neither jakarta.validation.ConstraintViolationException NOR the Hibernate persistence
// mappers below (DataExceptionMapper, ConstraintViolationExceptionMapper) are `@Provider` here:
// org.hibernate.exception.DataException/ConstraintViolationException are only on the runtime
// classpath of services that pull in a Hibernate/Panache extension — measured directly
// (issue #6240): auto-registering them crashed agent-service, analytics-sink and ap2-service
// at ArC init with `ClassNotFoundException: org.hibernate.exception.ConstraintViolationException`,
// the exact failure this comment already warned about for the jakarta.validation case. Services
// with an ORM register these two explicitly (`@Provider` on a thin subclass, or list the class in
// `quarkus.arc.additional-indexed-classes`); services without one never load them at all.

/**
 * A 401 as the standard error envelope, instead of Quarkus's bare `Not Authorized` string.
 *
 * Every other error these services emit is an [ApiError] document; an authentication failure is
 * plain text, because [io.quarkus.security.UnauthorizedException] is not a
 * [WebApplicationException] and never reaches [WebApplicationExceptionMapper] below — Quarkus
 * answers first. A client that parses the error body therefore breaks on the one response it is
 * most likely to meet. Measured through transaction-service's provider replay of the three
 * "missing or expired token" pacts, where pact-jvm reports
 * `Invalid JSON (1:2), found unexpected character 'N'` (issue #8993).
 *
 * NOT `@Provider`, deliberately, and for the same reason as the two persistence mappers above:
 * `quarkus-security` is `compileOnly` here, so auto-registering this class would load
 * `io.quarkus.security.UnauthorizedException` in every service that consumes libs-runtime and
 * crash the ones without the extension at ArC init — the #6240 failure. A service that has
 * security opts in with a thin `@Provider` subclass.
 */
open class UnauthorizedExceptionMapper : ExceptionMapper<UnauthorizedException> {
    override fun toResponse(exception: UnauthorizedException): Response =
        Response.status(ErrorCode.UNAUTHORIZED.httpStatus)
            .entity(
                apiError(
                    ErrorCode.UNAUTHORIZED.httpStatus,
                    ErrorCode.UNAUTHORIZED.code,
                    "Authentication required",
                ),
            )
            .build()
}

@Provider
class WebApplicationExceptionMapper : ExceptionMapper<WebApplicationException> {
    override fun toResponse(exception: WebApplicationException): Response {
        val status = exception.response?.status ?: 500
        val code = when (status) {
            401 -> ErrorCode.UNAUTHORIZED.code
            403 -> ErrorCode.FORBIDDEN.code
            404 -> ErrorCode.NOT_FOUND.code
            409 -> ErrorCode.CONFLICT.code
            else -> "HTTP_$status"
        }
        return Response.status(status)
            .entity(apiError(status, code, exception.message ?: "Request failed"))
            .build()
    }
}

/**
 * Last-resort mapper. Logs the full stack trace and returns a sanitised 500 — never leak
 * internal exception messages to the client, since they may contain SQL fragments,
 * stack traces or PII.
 */
/**
 * Persistence and decoding failures classified by CLASS NAME, walking the cause chain.
 *
 * By name, and that is the whole design rather than a shortcut. The typed mappers below
 * ([DataExceptionMapper], [ConstraintViolationExceptionMapper]) name `org.hibernate.exception`
 * types in their supertype, so `@Provider` on them makes ArC load those classes in EVERY consumer
 * of libs-runtime — including services with no ORM. Measured: it crashed agent-service,
 * analytics-sink and ap2-service at ArC init with
 * `ClassNotFoundException: org.hibernate.exception.ConstraintViolationException`. They are
 * therefore not auto-registered, which leaves them inert unless each ORM service opts in by hand —
 * roughly thirty services, one line each, and a new service silently starts out unprotected.
 *
 * [GenericExceptionMapper] already runs everywhere and holds no ORM reference at all. Matching a
 * String costs nothing on a classpath that lacks the class, so the classification lands once, for
 * every service, present and future.
 *
 * Matched on the FULLY-QUALIFIED name: `jakarta.validation.ConstraintViolationException` is a
 * DIFFERENT exception from Hibernate's with the same simple name, and conflating them would map a
 * bean-validation failure to 409 instead of the 400 it deserves.
 */
private val PERSISTENCE_STATUS: Map<String, ErrorCode> = mapOf(
    // The value cannot be represented by the column — too long, out of range, wrong shape. It came
    // from the request; a service storing its own computed value in a column it defined would be a
    // schema error, surfacing at migration time rather than per-request.
    "org.hibernate.exception.DataException" to ErrorCode.VALIDATION_ERROR,
    // Unique/foreign-key/check violation. A conflict from the caller's perspective either way —
    // see ConstraintViolationExceptionMapper for why 409 is chosen despite the ambiguity.
    "org.hibernate.exception.ConstraintViolationException" to ErrorCode.CONFLICT,
    // Raised while DECODING the entity, before any handler sees it.
    "java.io.CharConversionException" to ErrorCode.VALIDATION_ERROR,
)

/**
 * The first classified failure in the cause chain, or null.
 *
 * The chain matters and is not defensive padding: Hibernate Reactive completes failures through
 * `CompletableFuture`, so the exception arriving at the resource boundary is routinely a wrapper
 * with the real cause one or more levels down. Measured on run 32504892635 — the same
 * `DataException` that a typed mapper caught directly in one run reached
 * [GenericExceptionMapper] wrapped in the next.
 *
 * Bounded by [MAX_CAUSE_DEPTH].
 */
/** Cause chains are not trusted to be acyclic, and a self-referencing link is also guarded. */
private const val MAX_CAUSE_DEPTH = 8

private fun classifyByName(exception: Throwable): ErrorCode? {
    var current: Throwable? = exception
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        PERSISTENCE_STATUS[current.javaClass.name]?.let { return it }
        if (current.cause === current) return null
        current = current.cause
        depth++
    }
    return null
}

@Provider
class GenericExceptionMapper : ExceptionMapper<Exception> {
    private val log = Logger.getLogger(GenericExceptionMapper::class.java)
    override fun toResponse(exception: Exception): Response {
        val id = traceId()

        classifyByName(exception)?.let { code ->
            // ERROR, not WARN: a 4xx is correct for the caller AND a missing validation in this
            // service. Fixing the status must not quieten the second half — the only reason the
            // value reached the database is that nothing checked it earlier.
            log.errorf(exception, "unvalidated input reached persistence (traceId=%s)", id)
            return Response.status(code.httpStatus)
                .entity(
                    ApiError(
                        traceId = id,
                        status = code.httpStatus,
                        code = code.code,
                        // Never the exception message: it carries the SQL statement — table names,
                        // column names, query shape — and this path is reached by exactly the
                        // caller who would collect it.
                        message = if (code == ErrorCode.CONFLICT) {
                            "The request conflicts with existing data"
                        } else {
                            "A submitted value is not valid"
                        },
                        timestamp = Instant.now(),
                    ),
                )
                .build()
        }

        log.errorf(exception, "Unhandled exception (traceId=%s)", id)
        return Response.status(500)
            .entity(
                ApiError(
                    traceId = id,
                    status = 500,
                    code = ErrorCode.INTERNAL_ERROR.code,
                    message = "An unexpected error occurred. Please contact support with traceId=$id.",
                    timestamp = Instant.now(),
                ),
            )
            .build()
    }
}

/**
 * Persistence and decoding failures that are the CALLER's input, rendered as 4xx instead of 500.
 *
 * Same lineage as [DateTimeExceptionMapper], which exists because an earlier authenticated-fuzz
 * run found five money-path endpoints answering 500 to an unparseable date (#3038). Fuzzing past
 * authentication found the next layer (#5913): once a 403 stops answering first, malformed input
 * reaches the handler, the handler passes it to Hibernate unvalidated, and Postgres rejects it —
 * at which point the caller is told the server broke.
 *
 * Measured on run 32498876292, after the NUL-byte guard (#5995) removed 60 of these:
 *
 *     sdd       value too long for type character varying(35)   (22001)
 *     interest  date out of range                              (22008)
 *     sdd       duplicate key violates uq_sdd_mandate_reference
 *     dispute   insert on "dispute_evidence" violates foreign key
 *     sdd       Unsupported UCS-4 endianness (3412) detected
 *
 * The bar for mapping globally is the one [DateTimeExceptionMapper] sets: the exception reaching a
 * *resource boundary* must always be a rejected value, never a server fault. Each type below is
 * held to it individually, and the one that does not clearly pass says so.
 */

/**
 * `DataException` → **400**. The value cannot be represented by the column: too long, out of range,
 * wrong shape. It came from the request, and no server-side fault produces it — a service storing
 * its own computed value in a column it defined would be a schema error, which surfaces at
 * migration time, not per-request.
 *
 * The message is NOT echoed to the caller. It carries the SQL statement, so returning it would leak
 * column names, table names and the query shape to an anonymous caller — a fuzzing client is
 * precisely who would collect it.
 */
class DataExceptionMapper : ExceptionMapper<DataException> {
    private val log = Logger.getLogger(DataExceptionMapper::class.java)

    override fun toResponse(exception: DataException): Response {
        // ERROR, not WARN: a 400 here is correct for the caller AND a missing validation in this
        // service. Mapping the status must not quieten the second half — the whole reason this
        // reached Postgres is that nothing checked it earlier.
        log.errorf(exception, "unvalidated value reached the database: %s", exception.sqlException?.sqlState)
        return Response.status(ErrorCode.VALIDATION_ERROR.httpStatus)
            .entity(
                apiError(
                    ErrorCode.VALIDATION_ERROR.httpStatus,
                    ErrorCode.VALIDATION_ERROR.code,
                    "A submitted value is not valid for its field",
                ),
            )
            .build()
    }
}

/**
 * `ConstraintViolationException` → **409**.
 *
 * This is the one that does not pass the "never a server fault" bar cleanly, and it is mapped
 * anyway — with the reason written down rather than glossed. A unique violation on a
 * caller-supplied reference (`uq_sdd_mandate_reference`) is a conflict the caller can resolve. A
 * foreign-key violation naming a parent that does not exist (`dispute_evidence`) is the caller
 * pointing at something absent. But a service inserting a child under an id IT computed wrongly
 * would produce the identical exception, and that IS a server fault now reported as 409.
 *
 * 500 is not the safer answer to that ambiguity: it is wrong for the two measured cases, which are
 * both ordinary client conflicts, and it tells every caller the server broke when it did not. The
 * ambiguity is handled where it can actually be resolved — the log line below carries the
 * constraint name, so the rarer server-side case stays diagnosable instead of being flattened into
 * a status code.
 */
class ConstraintViolationExceptionMapper : ExceptionMapper<ConstraintViolationException> {
    private val log = Logger.getLogger(ConstraintViolationExceptionMapper::class.java)

    override fun toResponse(exception: ConstraintViolationException): Response {
        // The constraint name is the only thing that distinguishes "the caller sent a duplicate"
        // from "this service computed a bad foreign key", so it is logged even though it is
        // deliberately not returned.
        log.errorf(exception, "constraint violated at the database: %s", exception.constraintName)
        return Response.status(ErrorCode.CONFLICT.httpStatus)
            .entity(
                apiError(
                    ErrorCode.CONFLICT.httpStatus,
                    ErrorCode.CONFLICT.code,
                    "The request conflicts with existing data",
                ),
            )
            .build()
    }
}

/**
 * `CharConversionException` → **400**. Raised while DECODING the request entity, before any handler
 * sees it — `Unsupported UCS-4 endianness (3412)` is a body whose byte-order mark claims an
 * encoding the parser cannot read. Unambiguously the caller's bytes; nothing server-side can
 * produce it during request handling.
 *
 * It extends `IOException`, not `IllegalArgumentException`, which is why it fell through to
 * [GenericExceptionMapper] and rendered as 500 — the same reason `DateTimeException` did.
 */
@Provider
class CharConversionExceptionMapper : ExceptionMapper<CharConversionException> {
    override fun toResponse(exception: CharConversionException): Response =
        Response.status(ErrorCode.VALIDATION_ERROR.httpStatus)
            .entity(
                apiError(
                    ErrorCode.VALIDATION_ERROR.httpStatus,
                    ErrorCode.VALIDATION_ERROR.code,
                    "The request body could not be decoded",
                ),
            )
            .build()
}
