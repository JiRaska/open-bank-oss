// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.rest

import com.openbank.libs.security.Roles
import com.openbank.tax.application.usecase.CorporateRegisterEntryNotFoundException
import com.openbank.tax.application.usecase.CorporateRegisterService
import com.openbank.tax.application.usecase.ProposeCorporateFact
import com.openbank.tax.domain.corporate.CorporateFact
import com.openbank.tax.domain.corporate.CorporateRegisterEntry
import com.openbank.tax.domain.model.TaxValidationException
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The corporate register (#12425): figures no system produces, maintained by operators four-eyes
 * and read by the ČNB returns (PSP 32-04, 50-04, 40-01). Reads are open to oversight roles; every
 * change is operator-only, and the proposer of an entry can never decide it.
 */
@Path("/api/v1/corporate-register")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "CorporateRegister", description = "Operator-maintained corporate figures for statutory returns")
class CorporateRegisterResource(private val service: CorporateRegisterService) {
    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @RolesAllowed(Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Every register entry of an entity, any status — the audit trail")
    suspend fun list(@QueryParam("entityId") entityId: String?): Response {
        val entity = requireNotNull(entityId) { "query parameter 'entityId' is required" }
        return Response.ok(service.entries(entity).map { it.toResponse() }).build()
    }

    @GET
    @Path("/effective")
    @RolesAllowed(Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "The approved figure of each fact effective for a period (absent = none approved)")
    suspend fun effective(
        @QueryParam("entityId") entityId: String?,
        @QueryParam("periodStart") periodStart: String?,
        @QueryParam("periodEnd") periodEnd: String?,
    ): Response {
        val entity = requireNotNull(entityId) { "query parameter 'entityId' is required" }
        val values = service.effective(
            entity,
            CorporateFact.entries,
            date("periodStart", periodStart),
            date("periodEnd", periodEnd),
        )
        return Response.ok(values.mapKeys { it.key.name }).build()
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(Roles.OPERATOR)
    @Operation(summary = "Propose a figure (maker); it feeds no return until another operator approves it")
    suspend fun propose(request: ProposeCorporateFactRequest?): Response {
        val body = requireNotNull(request) { "request body is required" }
        val fact = requireNotNull(body.fact) { "fact is required" }
        val entry = service.propose(
            ProposeCorporateFact(
                entityId = requireNotNull(body.entityId) { "entityId is required" },
                fact = runCatching { CorporateFact.valueOf(fact) }.getOrNull()
                    ?: throw TaxValidationException("unknown fact '$fact'; one of ${CorporateFact.entries}"),
                value = requireNotNull(body.value) { "value is required" },
                effectiveFrom = date("effectiveFrom", body.effectiveFrom),
                reason = requireNotNull(body.reason) { "reason is required" },
                evidence = requireNotNull(body.evidence) { "evidence is required" },
            ),
            actingPrincipal(),
        )
        return Response.status(Response.Status.CREATED).entity(entry.toResponse()).build()
    }

    @POST
    @Path("/{id}/approve")
    @RolesAllowed(Roles.OPERATOR)
    @Operation(summary = "Approve a proposed figure (checker; never the proposer)")
    suspend fun approve(@PathParam("id") id: String): Response =
        Response.ok(service.approve(parseId(id), actingPrincipal()).toResponse()).build()

    @POST
    @Path("/{id}/reject")
    @RolesAllowed(Roles.OPERATOR)
    @Operation(summary = "Reject a proposed figure (checker; never the proposer)")
    suspend fun reject(@PathParam("id") id: String): Response =
        Response.ok(service.reject(parseId(id), actingPrincipal()).toResponse()).build()

    private fun date(name: String, value: String?): LocalDate {
        requireNotNull(value) { "'$name' is required" }
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("'$name' must be an ISO date (YYYY-MM-DD)", e)
        }
    }

    private fun parseId(value: String): UUID = runCatching { UUID.fromString(value) }.getOrNull()
        ?: throw WebApplicationException("id must be a UUID (got '$value')", Response.Status.BAD_REQUEST)

    private fun actingPrincipal(): String {
        val principal = identity.principal
        val subject = (principal as? JsonWebToken)?.subject ?: principal?.name
        if (subject.isNullOrBlank()) {
            throw WebApplicationException("Cannot resolve the acting principal", Response.Status.UNAUTHORIZED)
        }
        return subject
    }
}

data class ProposeCorporateFactRequest(
    val entityId: String? = null,
    val fact: String? = null,
    val value: BigDecimal? = null,
    val effectiveFrom: String? = null,
    val reason: String? = null,
    val evidence: String? = null,
)

data class CorporateRegisterEntryResponse(
    val id: UUID,
    val entityId: String,
    val fact: String,
    val value: BigDecimal,
    val effectiveFrom: LocalDate,
    val version: Int,
    val reason: String,
    val evidence: String,
    val status: String,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
)

private fun CorporateRegisterEntry.toResponse() = CorporateRegisterEntryResponse(
    id, entityId, fact.name, value, effectiveFrom, version, reason, evidence, status.name,
    proposedBy, proposedAt, decidedBy, decidedAt,
)

@Provider
class CorporateRegisterEntryNotFoundExceptionMapper : ExceptionMapper<CorporateRegisterEntryNotFoundException> {
    override fun toResponse(exception: CorporateRegisterEntryNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to exception.message)).build()
}
