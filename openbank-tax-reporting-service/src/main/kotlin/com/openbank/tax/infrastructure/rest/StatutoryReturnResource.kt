// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.rest

import com.openbank.libs.security.Roles
import com.openbank.tax.application.port.out.ReturnDataPort
import com.openbank.tax.application.port.out.ReturnDataUnavailableException
import com.openbank.tax.application.port.out.ReturnWireRendererPort
import com.openbank.tax.application.usecase.ReturnBreach
import com.openbank.tax.application.usecase.StatutoryReturnNotFoundException
import com.openbank.tax.application.usecase.StatutoryReturnService
import com.openbank.tax.domain.returns.ReturnCatalogue
import com.openbank.tax.domain.returns.ReturnValidationException
import com.openbank.tax.domain.returns.StatutoryReturn
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status.NOT_FOUND
import jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.util.UUID

private const val UNPROCESSABLE = 422

/**
 * Catalogue-defined statutory returns (ADR-0336) — pension ČNB PSP/PEF first.
 *
 * Reads are open to auditor/viewer/operator/admin. Every state change is operator-only and
 * four-eyes separated: whoever assembled a return may not approve it.
 */
@Path("/api/v1/statutory-returns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "StatutoryReturn", description = "Catalogue-defined regulatory returns (ADR-0336)")
class StatutoryReturnResource(
    private val service: StatutoryReturnService,
    private val dataPort: ReturnDataPort,
    private val renderer: ReturnWireRendererPort,
) {
    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "List return revisions, newest period first")
    suspend fun list(): Response = Response.ok(service.list().map { it.toResponse() }).build()

    @GET
    @Path("/catalogues")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "The jurisdiction return catalogues this deployment reports under")
    fun catalogues(): Response = Response.ok(service.catalogues().map { it.toResponse() }).build()

    @GET
    @Path("/breaches")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(
        summary = "Returns past their statutory deadline without a submitted revision, including never-assembled ones",
    )
    suspend fun breaches(): Response = Response.ok(service.breaches().map { it.toResponse() }).build()

    @GET
    @Path("/capability")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(
        summary = "Whether a data source and a wire renderer are bound; reports the truth rather than implying it",
    )
    fun capability(): Response = Response.ok(
        ReturnCapabilityResponse(
            dataSourceAvailable = dataPort.available,
            wireFormatAvailable = renderer.available,
            note = "ADR-0336: until the pension read models exist no return can be assembled; the regulator " +
                "wire file is not rendered — an operator submits the attested figures and records the reference.",
        ),
    ).build()

    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Get one return revision")
    suspend fun get(@PathParam("id") id: String): Response = Response.ok(service.get(parseId(id)).toResponse()).build()

    @POST
    @Path("/assemble")
    @RolesAllowed(Roles.OPERATOR)
    @Operation(summary = "Assemble and validate a return from sourced figures (→ ASSEMBLED)")
    suspend fun assemble(request: AssembleReturnRequest): Response {
        val assembled = service.assemble(
            request.catalogueId,
            request.returnCode,
            request.entityId,
            request.period,
            actingPrincipal(),
        )
        return Response.ok(assembled.toResponse()).build()
    }

    @POST
    @Path("/{id}/approve")
    @RolesAllowed(Roles.OPERATOR)
    @Operation(summary = "Approve and attest the content hash (ASSEMBLED → APPROVED; four-eyes)")
    suspend fun approve(@PathParam("id") id: String): Response =
        Response.ok(service.approve(parseId(id), actingPrincipal()).toResponse()).build()

    @POST
    @Path("/{id}/submitted")
    @RolesAllowed(Roles.OPERATOR)
    @Operation(
        summary = "Record the regulator submission reference (APPROVED → SUBMITTED; refused if content ≠ attestation)",
    )
    suspend fun submitted(@PathParam("id") id: String, request: RecordSubmissionRequest): Response =
        Response.ok(service.submit(parseId(id), request.reference, actingPrincipal()).toResponse()).build()

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

data class AssembleReturnRequest(
    val catalogueId: String,
    val returnCode: String,
    val entityId: String,
    val period: String,
)

data class RecordSubmissionRequest(val reference: String)

data class ReturnCapabilityResponse(
    val dataSourceAvailable: Boolean,
    val wireFormatAvailable: Boolean,
    val note: String,
)

data class StatutoryReturnResponse(
    val id: UUID,
    val catalogueId: String,
    val catalogueVersion: Int,
    val returnCode: String,
    val entityId: String,
    val periodicity: String,
    val period: String,
    val revision: Int,
    val status: String,
    val datapoints: Map<String, BigDecimal>,
    val contentHash: String,
    val dueDate: String,
    val assembledBy: String,
    val assembledAt: String,
    val approvedBy: String?,
    val approvedAt: String?,
    val attestedHash: String?,
    val submittedBy: String?,
    val submittedAt: String?,
    val submissionReference: String?,
)

data class ReturnDefinitionResponse(
    val code: String,
    val name: String,
    val scope: String,
    val periodicity: String,
    val deadlineDaysAfterPeriodEnd: Int,
    val legalBasis: String,
    val datapoints: List<String>,
)

data class ReturnCatalogueResponse(
    val id: String,
    val version: Int,
    val jurisdiction: String,
    val wireFormatVerified: Boolean,
    val returns: List<ReturnDefinitionResponse>,
)

data class ReturnBreachResponse(
    val catalogueId: String,
    val returnCode: String,
    val entityId: String,
    val period: String,
    val dueDate: String,
    val kind: String,
)

private fun StatutoryReturn.toResponse() = StatutoryReturnResponse(
    id = id,
    catalogueId = catalogueId,
    catalogueVersion = catalogueVersion,
    returnCode = returnCode,
    entityId = entityId,
    periodicity = period.periodicity.name,
    period = period.label,
    revision = revision,
    status = status.name,
    datapoints = datapoints,
    contentHash = contentHash,
    dueDate = dueDate.toString(),
    assembledBy = assembledBy,
    assembledAt = assembledAt.toString(),
    approvedBy = approvedBy,
    approvedAt = approvedAt?.toString(),
    attestedHash = attestedHash,
    submittedBy = submittedBy,
    submittedAt = submittedAt?.toString(),
    submissionReference = submissionReference,
)

private fun ReturnCatalogue.toResponse() = ReturnCatalogueResponse(
    id = id,
    version = version,
    jurisdiction = jurisdiction,
    wireFormatVerified = wireFormatVerified,
    returns = returns.map {
        ReturnDefinitionResponse(
            it.code,
            it.name,
            it.scope.name,
            it.periodicity.name,
            it.deadlineDaysAfterPeriodEnd,
            it.legalBasis,
            it.datapoints,
        )
    },
)

private fun ReturnBreach.toResponse() =
    ReturnBreachResponse(catalogueId, returnCode, entityId, period, dueDate.toString(), kind.name)

@Provider
class StatutoryReturnNotFoundExceptionMapper : ExceptionMapper<StatutoryReturnNotFoundException> {
    override fun toResponse(exception: StatutoryReturnNotFoundException): Response = Response.status(NOT_FOUND)
        .entity(mapOf("error" to (exception.message ?: "Not found")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class ReturnValidationExceptionMapper : ExceptionMapper<ReturnValidationException> {
    override fun toResponse(exception: ReturnValidationException): Response = Response.status(UNPROCESSABLE)
        .entity(
            mapOf(
                "error" to "Return failed validation",
                "findings" to exception.findings.map { mapOf("rule" to it.ruleId, "message" to it.message) },
            ),
        )
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class ReturnDataUnavailableExceptionMapper : ExceptionMapper<ReturnDataUnavailableException> {
    override fun toResponse(exception: ReturnDataUnavailableException): Response = Response.status(SERVICE_UNAVAILABLE)
        .entity(mapOf("error" to (exception.message ?: "Data source unavailable")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}
