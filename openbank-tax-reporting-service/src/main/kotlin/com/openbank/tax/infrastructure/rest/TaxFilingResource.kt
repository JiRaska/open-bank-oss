// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.rest

import com.openbank.libs.security.Roles
import com.openbank.tax.application.port.out.EpoRendererPort
import com.openbank.tax.application.usecase.TaxFilingNotFoundException
import com.openbank.tax.application.usecase.TaxFilingService
import com.openbank.tax.domain.model.FilingPeriod
import com.openbank.tax.domain.model.ObservedRemittance
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxFilingRecord
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
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.Response.Status.CONFLICT
import jakarta.ws.rs.core.Response.Status.NOT_FOUND
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.util.UUID

private const val UNPROCESSABLE_ENTITY = 422

/**
 * §38d withholding-tax filing (ADR-0180).
 *
 * Reads are open to auditor/viewer/operator/admin — a tax return is exactly the artefact an auditor
 * needs to see. Both state changes are operator-only and four-eyes separated: whoever assembled a
 * period may not also record it as filed.
 */
@Path("/api/v1/tax/filings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "TaxFiling", description = "Legacy monthly withholding workflow; annual §38d filing is not represented")
class TaxFilingResource(private val taxFilingService: TaxFilingService, private val epoRenderer: EpoRendererPort) {

    @Inject
    lateinit var identity: SecurityIdentity

    @GET
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "List filing periods, newest first")
    suspend fun list(): Response = Response.ok(taxFilingService.list().map { it.toResponse() }).build()

    @GET
    @Path("/overdue")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Legacy monthly records past their configured due date; not an annual filing alert")
    suspend fun overdue(): Response = Response.ok(taxFilingService.overdue().map { it.toResponse() }).build()

    @GET
    @Path("/{period}")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Get one filing period (YYYY-MM)")
    suspend fun get(@PathParam("period") period: String): Response =
        Response.ok(taxFilingService.get(parsePeriod(period)).toResponse()).build()

    @GET
    @Path("/{period}/remittances")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(summary = "Observed assembled batches; cash settlement is not verified here")
    suspend fun remittances(@PathParam("period") period: String): Response =
        Response.ok(taxFilingService.remittancesFor(parsePeriod(period)).map { it.toResponse() }).build()

    @POST
    @Path("/{period}/assemble")
    @RolesAllowed(Roles.OPERATOR)
    @Operation(summary = "Assemble the period, freezing its totals (OPEN → ASSEMBLED)")
    suspend fun assemble(@PathParam("period") period: String): Response {
        val record = taxFilingService.assemble(parsePeriod(period), by = actingPrincipal())
        return Response.ok(record.toResponse()).build()
    }

    @POST
    @Path("/{period}/filed")
    @RolesAllowed(Roles.OPERATOR)
    @Operation(
        summary = "Record a reference on the legacy monthly workflow " +
            "(ASSEMBLED → FILED; four-eyes, not annual submission evidence)",
    )
    suspend fun markFiled(@PathParam("period") period: String, request: MarkFiledRequest): Response {
        val record = taxFilingService.markFiled(parsePeriod(period), request.reference, by = actingPrincipal())
        return Response.ok(record.toResponse()).build()
    }

    @GET
    @Path("/export-capability")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN)
    @Operation(
        summary = "Whether EPO XML rendering is available; reports the truth rather than implying it",
    )
    fun exportCapability(): Response = Response.ok(
        ExportCapabilityResponse(
            epoXmlAvailable = epoRenderer.available,
            note = if (epoRenderer.available) {
                "EPO XML rendering is bound."
            } else {
                "EPO XML rendering is not built (ADR-0180 v1). This API contains legacy monthly " +
                    "batch totals only; it does not establish cash settlement or annual §38d " +
                    "Vyúčtování submission."
            },
        ),
    ).build()

    private fun parsePeriod(value: String): FilingPeriod {
        val parts = value.split("-")
        val period = runCatching { FilingPeriod(parts[0].toInt(), parts[1].toInt()) }.getOrNull()
        return period ?: throw WebApplicationException(
            "period must be YYYY-MM (got '$value')",
            Response.Status.BAD_REQUEST,
        )
    }

    private fun actingPrincipal(): String {
        val principal = identity.principal
        val subject = (principal as? JsonWebToken)?.subject ?: principal?.name
        if (subject.isNullOrBlank()) {
            throw WebApplicationException("Cannot resolve the acting principal", Response.Status.UNAUTHORIZED)
        }
        return subject
    }
}

data class MarkFiledRequest(val reference: String)

data class ExportCapabilityResponse(val epoXmlAvailable: Boolean, val note: String)

data class TaxFilingResponse(
    val id: UUID,
    val period: String,
    val status: String,
    val currency: String,
    val totalTaxAmount: BigDecimal,
    val remittanceCount: Int,
    val itemCount: Int,
    val dueDate: String,
    val assembledAt: String?,
    val assembledBy: String?,
    val filedAt: String?,
    val filedBy: String?,
    val filingReference: String?,
    val recordKind: String = LEGACY_MONTHLY_RECORD_KIND,
)

data class ObservedRemittanceResponse(
    val remittanceId: UUID,
    val period: String,
    val currency: String,
    val totalTaxAmount: BigDecimal,
    val itemCount: Int,
    val dueDate: String,
    val observedAt: String,
    val sourceStage: String = ASSEMBLED_BATCH_SOURCE_STAGE,
)

internal const val LEGACY_MONTHLY_RECORD_KIND = "LEGACY_MONTHLY_WORKFLOW"
internal const val ASSEMBLED_BATCH_SOURCE_STAGE = "BATCH_ASSEMBLED_ONLY"

internal fun TaxFilingRecord.toResponse() = TaxFilingResponse(
    id = id,
    period = period.label,
    status = status.name,
    currency = currency,
    totalTaxAmount = totalTaxAmount,
    remittanceCount = remittanceCount,
    itemCount = itemCount,
    dueDate = dueDate.toString(),
    assembledAt = assembledAt?.toString(),
    assembledBy = assembledBy,
    filedAt = filedAt?.toString(),
    filedBy = filedBy,
    filingReference = filingReference,
)

internal fun ObservedRemittance.toResponse() = ObservedRemittanceResponse(
    remittanceId = remittanceId,
    period = period.label,
    currency = currency,
    totalTaxAmount = totalTaxAmount,
    itemCount = itemCount,
    dueDate = dueDate.toString(),
    observedAt = observedAt.toString(),
)

// Dedicated domain exception types rather than IllegalArgument/IllegalState: libs-runtime already
// registers mappers for the JDK types, and two providers for one type resolve non-deterministically
// (ADR-0049 D4), which makes the status code a per-request lottery.
@Provider
class TaxFilingNotFoundExceptionMapper : ExceptionMapper<TaxFilingNotFoundException> {
    override fun toResponse(exception: TaxFilingNotFoundException): Response = Response.status(NOT_FOUND)
        .entity(mapOf("error" to (exception.message ?: "Not found")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class TaxConflictExceptionMapper : ExceptionMapper<TaxConflictException> {
    override fun toResponse(exception: TaxConflictException): Response = Response.status(CONFLICT)
        .entity(mapOf("error" to (exception.message ?: "Conflict")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}

@Provider
class TaxValidationExceptionMapper : ExceptionMapper<TaxValidationException> {
    override fun toResponse(exception: TaxValidationException): Response = Response.status(UNPROCESSABLE_ENTITY)
        .entity(mapOf("error" to (exception.message ?: "Unprocessable entity")))
        .type(MediaType.APPLICATION_JSON)
        .build()
}
