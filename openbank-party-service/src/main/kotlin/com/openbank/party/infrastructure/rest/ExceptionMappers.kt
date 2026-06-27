// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.party.infrastructure.rest

import com.openbank.libs.api.error.ApiError
import com.openbank.libs.api.error.ErrorCode
import com.openbank.libs.flags.FeatureDisabledException
import com.openbank.party.application.usecase.PartyAlreadyExistsException
import com.openbank.party.application.usecase.PartyNotFoundException
import io.vertx.pgclient.PgException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import java.util.UUID

@Provider
class PartyNotFoundMapper : ExceptionMapper<PartyNotFoundException> {
    override fun toResponse(e: PartyNotFoundException) = Response.status(404)
        .entity(ApiError(UUID.randomUUID().toString(), 404, ErrorCode.NOT_FOUND.code, e.message ?: "Not found")).build()
}

@Provider
class PartyAlreadyExistsMapper : ExceptionMapper<PartyAlreadyExistsException> {
    override fun toResponse(e: PartyAlreadyExistsException) = Response.status(409)
        .entity(ApiError(UUID.randomUUID().toString(), 409, ErrorCode.CONFLICT.code, e.message ?: "Conflict")).build()
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
                    UUID.randomUUID().toString(),
                    Response.Status.CONFLICT.statusCode,
                    ErrorCode.CONFLICT.code,
                    "party already exists",
                ),
            ).build()
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
            ApiError(
                UUID.randomUUID().toString(),
                Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                "INTERNAL_ERROR",
                e.message ?: "Database error",
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
        ApiError(UUID.randomUUID().toString(), 404, ErrorCode.NOT_FOUND.code, "feature '${e.flag}' is not enabled"),
    ).build()
}
