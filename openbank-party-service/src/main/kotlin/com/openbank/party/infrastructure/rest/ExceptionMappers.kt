// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.flags.FeatureDisabledException
import com.openbank.party.application.port.out.GdprAggregationAuthException
import com.openbank.party.application.usecase.PartyAlreadyExistsException
import com.openbank.party.application.usecase.PartyMergeRejectedException
import com.openbank.party.application.usecase.PartyNotFoundException
import io.quarkus.security.AuthenticationFailedException
import io.quarkus.security.ForbiddenException
import io.quarkus.security.UnauthorizedException
import io.vertx.pgclient.PgException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.time.Instant

@Provider
class PartyNotFoundMapper : ExceptionMapper<PartyNotFoundException> {
    override fun toResponse(e: PartyNotFoundException) = Response.status(404)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                404,
                ErrorCode.NOT_FOUND.code,
                e.message ?: "Not found",
                timestamp = Instant.now(),
            ),
        ).build()
}

@Provider
class PartyAlreadyExistsMapper : ExceptionMapper<PartyAlreadyExistsException> {
    override fun toResponse(e: PartyAlreadyExistsException) = Response.status(409)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                409,
                ErrorCode.CONFLICT.code,
                e.message ?: "Conflict",
                timestamp = Instant.now(),
            ),
        ).build()
}

/**
 * ADR-0179: a merge precondition failed (already merged, chain, or the duplicate still owns an
 * open account). 409 — the request is well-formed but the current state forbids it; the message
 * names the blocking condition so the operator knows which step to do first.
 */
@Provider
class PartyMergeRejectedMapper : ExceptionMapper<PartyMergeRejectedException> {
    override fun toResponse(e: PartyMergeRejectedException) = Response.status(Response.Status.CONFLICT)
        .entity(
            ApiError(
                // ADR-0106: trace/correlation identifiers are minted via Ids. The neighbouring
                // mappers predate the guard, which only flags newly added call sites.
                Ids.randomId().toString(),
                Response.Status.CONFLICT.statusCode,
                ErrorCode.CONFLICT.code,
                e.message ?: "Merge rejected",
                timestamp = Instant.now(),
            ),
        ).build()
}

/**
 * Maps a PostgreSQL unique-constraint violation (sqlState 23505) on rc_blind_index to 409.
 * Fires when two concurrent createParty calls race with the same RČ after pepper is enabled.
 * PgException bubbles up unwrapped through Panache reactive; all other 23505 violations
 * (e.g. email) are already caught upstream in PartyService, so we only land here for RČ races.
 */
@Provider
class PgUniqueConstraintMapper : ExceptionMapper<PgException> {
    override fun toResponse(e: PgException): Response {
        if (e.sqlState == "23505" && e.detail?.contains("rc_blind_index") == true) {
            return Response.status(Response.Status.CONFLICT).entity(
                ApiError(
                    Ids.randomId().toString(),
                    Response.Status.CONFLICT.statusCode,
                    ErrorCode.CONFLICT.code,
                    "party already exists",
                    timestamp = Instant.now(),
                ),
            ).build()
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
            ApiError(
                Ids.randomId().toString(),
                Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                "INTERNAL_ERROR",
                e.message ?: "Database error",
                timestamp = Instant.now(),
            ),
        ).build()
    }
}

/**
 * Maps a [FeatureDisabledException] (thrown by the libs FeatureFlagInterceptor when a
 * `@FeatureFlag`-gated endpoint is called while its flag is off) to 404 — the capability
 * is hidden rather than surfaced as a 5xx (ADR-0067 §6). Flipping the flag back on in
 * flag-as-code restores the endpoint with no redeploy.
 */
@Provider
class FeatureDisabledMapper : ExceptionMapper<FeatureDisabledException> {
    override fun toResponse(e: FeatureDisabledException) = Response.status(404).entity(
        ApiError(
            Ids.randomId().toString(),
            404,
            ErrorCode.NOT_FOUND.code,
            "feature '${e.flag}' is not enabled",
            timestamp = Instant.now(),
        ),
    ).build()
}

/**
 * Maps a [GdprAggregationAuthException] to 502. An Art. 15 export whose KYC/card hop was refused
 * on authz grounds is NOT a valid export — returning 200 with those sections absent would be
 * indistinguishable from a subject who genuinely holds no KYC case and no cards, and the data
 * subject would receive a silently incomplete Right-of-Access response. Fail loudly so the DPO
 * re-runs it rather than files it.
 */
@Provider
class GdprAggregationAuthMapper : ExceptionMapper<GdprAggregationAuthException> {
    override fun toResponse(e: GdprAggregationAuthException) = Response.status(BAD_GATEWAY).entity(
        ApiError(
            // Error-response correlation id, not a durable entity id — Ids.randomId() (ADR-0106).
            // The pre-existing mappers above still mint via bare Ids.randomId(); left
            // untouched (out of scope here, and the ADR-0106 guard is diff-scoped so it only
            // flags new call sites, not the ~100 pre-existing ones fleet-wide).
            Ids.randomId().toString(),
            BAD_GATEWAY,
            "GDPR_AGGREGATION_DENIED",
            e.message ?: "GDPR aggregation refused by a downstream service",
            timestamp = Instant.now(),
        ),
    ).build()

    companion object {
        private const val BAD_GATEWAY = 502
    }
}

// Security-abort responses must be the JSON error envelope like every other error here.
// Without these mappers Quarkus REST's built-in handling writes the exception MESSAGE as a
// plain-text entity while the response keeps the resource's negotiated application/json
// content-type — a 401 whose body is the literal text "Not Authenticated" under a JSON
// content-type. Any client parsing the error as JSON (and Pact-JVM's matching engine, which
// plans the body comparison from the content-type) chokes on it: same defect class measured
// on the anonymous IBAN-lookup pact replay in account-service (#8803); here it breaks the
// anonymous VoP lookup pact replay (#8875). A user @Provider mapper wins over Quarkus'
// built-in. These live in the SERVICE, not libs-runtime, on purpose: a shared-library
// @Provider naming an `io.quarkus.security` type would be loaded by ArC in every consumer,
// including services without quarkus-security on the classpath — the #6240 boot-failure
// class, enforced by the provider-type-classpath gate.
@Provider
class QuarkusUnauthorizedExceptionMapper : ExceptionMapper<UnauthorizedException> {
    override fun toResponse(exception: UnauthorizedException): Response = Response.status(Response.Status.UNAUTHORIZED)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                Response.Status.UNAUTHORIZED.statusCode,
                "UNAUTHORIZED",
                "Unauthorized",
                timestamp = Instant.now(),
            ),
        ).build()
}

@Provider
class QuarkusAuthenticationFailedExceptionMapper : ExceptionMapper<AuthenticationFailedException> {
    override fun toResponse(exception: AuthenticationFailedException): Response =
        Response.status(Response.Status.UNAUTHORIZED)
            .entity(
                ApiError(
                    Ids.randomId().toString(),
                    Response.Status.UNAUTHORIZED.statusCode,
                    "UNAUTHORIZED",
                    "Unauthorized",
                    timestamp = Instant.now(),
                ),
            ).build()
}

@Provider
class QuarkusForbiddenExceptionMapper : ExceptionMapper<ForbiddenException> {
    override fun toResponse(exception: ForbiddenException): Response = Response.status(Response.Status.FORBIDDEN)
        .entity(
            ApiError(
                Ids.randomId().toString(),
                Response.Status.FORBIDDEN.statusCode,
                "FORBIDDEN",
                "Forbidden",
                timestamp = Instant.now(),
            ),
        ).build()
}
