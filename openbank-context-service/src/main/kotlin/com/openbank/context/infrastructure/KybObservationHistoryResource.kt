// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.infrastructure

import com.openbank.context.application.ContextAccessDenied
import com.openbank.context.application.ContextAuthorizationUnavailable
import com.openbank.context.application.ContextDisclosure
import com.openbank.context.application.ContextQueryService
import com.openbank.context.application.ContextReadResult
import com.openbank.context.domain.InvestigationContext
import com.openbank.context.domain.Investigator
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** References only. KYB remains the authority for owner details and their current restrictions. */
@Path("/api/v1/context/kyb-cases")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_KYC", "ROLE_ADMIN")
class KybObservationHistoryResource(
    private val queries: ContextQueryService,
    private val references: KybObservationReferenceRepository,
    private val identity: SecurityIdentity,
    private val clock: Clock,
) {
    @GET
    @Path("/{id}/access")
    suspend fun access(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
    ): Response {
        require(caseId == id.toString()) { "caseId must identify the KYB case" }
        require(purpose == PURPOSE) { "KYB_OWNERSHIP_REVIEW is required" }
        return try {
            queries.kybCaseEvidence(
                id.toString(),
                Investigator(identity.principal.name, identity.roles.sorted()),
                InvestigationContext(caseId, purpose, clock.instant()),
            ) {
                ContextReadResult(Response.noContent().header("Cache-Control", "no-store").build(), null)
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        }
    }

    @GET
    @Path("/{id}/ownership-observations")
    suspend fun history(
        @PathParam("id") id: UUID,
        @HeaderParam("X-Investigation-Case-Id") caseId: String?,
        @HeaderParam("X-Investigation-Purpose") purpose: String?,
        @QueryParam("knownAt") knownAt: String?,
    ): Response {
        val now = clock.instant()
        val known = try {
            knownAt?.let(Instant::parse) ?: now
        } catch (exception: DateTimeParseException) {
            throw IllegalArgumentException("knownAt must use RFC 3339", exception)
        }
        require(known <= now) { "historical evidence cannot be read in the future" }
        require(caseId == id.toString()) { "caseId must identify the KYB case" }
        require(purpose == PURPOSE) { "KYB_OWNERSHIP_REVIEW is required" }
        return try {
            queries.kybCaseEvidence(
                id.toString(),
                Investigator(identity.principal.name, identity.roles.sorted()),
                InvestigationContext(caseId, purpose, now, known),
            ) {
                val history = references.history(id, known)
                val evidenceRefs = history.observations.map { "kyb-observation:${it.observationId}:${it.revision}" }
                ContextReadResult(
                    Response.ok(history).header("Cache-Control", "no-store").build(),
                    ContextDisclosure(evidenceRefs, evidenceRefs.size, history.truncated),
                )
            }
        } catch (_: ContextAccessDenied) {
            Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build()
        } catch (_: ContextAuthorizationUnavailable) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").build()
        }
    }

    private companion object {
        const val PURPOSE = "KYB_OWNERSHIP_REVIEW"
    }
}
