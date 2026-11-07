// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.usecase.FixingService
import com.openbank.treasury.application.usecase.FixingStatus
import com.openbank.treasury.application.usecase.FixingView
import com.openbank.treasury.domain.marketdata.BatchStatus
import com.openbank.treasury.domain.marketdata.IndexObservation
import com.openbank.treasury.domain.marketdata.MarketDataFiles
import com.openbank.treasury.domain.marketdata.ObservationBatch
import com.openbank.treasury.domain.model.Actor
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D5 / contract sections 4 and 8.5: fixings and market data. Back office imports and
 * proposes manual fixings; an approver decides (four-eyes in the domain and in the database); a
 * run fixes only through the quality gate. RBAC here, OPA (`treasury_rest_ext.rego`) behind it.
 * Dates are parsed in the body so a malformed one is a 400, not a 404.
 */
@Tag(name = "Treasury", description = "Treasury fixings and market data (ADR-0344 D5)")
@Path("/api/v1/treasury")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE)
@Suppress("TooManyFunctions") // one endpoint per contract operation
class MarketDataResource {

    @Inject
    lateinit var fixings: FixingService

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var objectMapper: ObjectMapper

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    private fun date(raw: String?, name: String): LocalDate = runCatching {
        LocalDate.parse(requireNotNull(raw) { "query parameter '$name' is required" })
    }.getOrElse { throw IllegalArgumentException("'$name' must be an ISO date (yyyy-MM-dd)", it) }

    private fun batchId(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrElse { throw IllegalArgumentException("batch id must be a UUID") }

    @GET
    @Path("/fixings")
    @Operation(summary = "The fixings of one date: DUE, FIXED or BLOCKED by the quality gate")
    @Authorize(action = "treasury.fixing.read")
    suspend fun list(@QueryParam("date") date: String?, @QueryParam("status") status: String?): List<FixingView> =
        fixings.fixings(
            date(date, "date"),
            status?.let { s ->
                requireNotNull(
                    FixingStatus.entries.firstOrNull {
                        it.name == s
                    },
                ) { "status must be one of ${FixingStatus.entries}" }
            },
        )

    @POST
    @Path("/fixings/run")
    @RolesAllowed(BACK_OFFICE)
    @Operation(summary = "Fix every due index of the date that passes the quality gate; block the rest")
    @Authorize(action = "treasury.fixing.run")
    suspend fun run(@QueryParam("date") date: String?): Map<String, Any?> {
        val r = fixings.run(date(date, "date"))
        return mapOf(
            "date" to r.date,
            "fixedFlows" to r.fixedFlows,
            "fixedIndices" to r.fixedIndices,
            "blocked" to r.blocked,
            "failures" to r.failures.size,
        )
    }

    @POST
    @Path("/fixings/{index}/{date}/manual")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(BACK_OFFICE)
    @Operation(summary = "Propose a manual fixing (PENDING until a different human approves it)")
    @Authorize(action = "treasury.fixing.manual")
    suspend fun manual(
        @PathParam("index") index: String,
        @PathParam("date") date: String,
        request: ManualFixingRequest,
    ): Response {
        val batch = fixings.proposeManual(
            index,
            date(date, "date"),
            requireNotNull(request.rate) { "rate is required" },
            requireNotNull(request.reason) { "reason is required" },
            requireNotNull(request.source) { "source is required" },
            actor(),
        )
        return Response.status(Response.Status.CREATED).entity(BatchResponse.from(batch)).build()
    }

    @POST
    @Path("/fixings/{index}/{date}/manual/{batchId}/approve")
    @RolesAllowed(APPROVER, SENIOR_APPROVER)
    @Operation(summary = "Approve a manual fixing (four-eyes: never its proposer, never an AI agent)")
    @Authorize(action = "treasury.marketdata.approve")
    suspend fun approveManual(
        @PathParam("index") index: String,
        @PathParam("date") date: String,
        @PathParam("batchId") batchId: String,
    ): BatchResponse = BatchResponse.from(fixings.approve(batchId(batchId), actor(), index to date(date, "date")))

    @POST
    @Path("/fixings/{index}/{date}/manual/{batchId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(APPROVER, SENIOR_APPROVER)
    @Operation(summary = "Reject a manual fixing")
    @Authorize(action = "treasury.marketdata.approve")
    suspend fun rejectManual(
        @PathParam("index") index: String,
        @PathParam("date") date: String,
        @PathParam("batchId") batchId: String,
        request: RejectRequest,
    ): BatchResponse = BatchResponse.from(
        fixings.reject(
            batchId(batchId),
            requireNotNull(request.reason) { "reason is required" },
            actor(),
            index to date(date, "date"),
        ),
    )

    @GET
    @Path("/market-data/factors")
    @Operation(summary = "ACTIVE index observations (factor history), optionally of one index")
    @Authorize(action = "treasury.marketdata.read")
    suspend fun factors(
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
        @QueryParam("index") index: String?,
        @QueryParam("limit") limit: Int?,
    ): List<ObservationResponse> = fixings.history(index, date(from, "from"), date(to, "to"), boundedLimit(limit)).map {
        ObservationResponse.from(it)
    }

    @GET
    @Path("/market-data/export")
    @Produces("text/csv")
    @Operation(summary = "CSV export of ACTIVE observations (formula-injection neutralised)")
    @Authorize(action = "treasury.marketdata.read")
    suspend fun export(
        @QueryParam("from") from: String?,
        @QueryParam("to") to: String?,
        @QueryParam("index") index: String?,
    ): Response =
        Response.ok(MarketDataFiles.toCsv(fixings.history(index, date(from, "from"), date(to, "to"), MAX_EXPORT)))
            .header("Content-Disposition", "attachment; filename=\"market-data.csv\"")
            .build()

    @POST
    @Path("/market-data/import")
    @Consumes("text/csv", MediaType.APPLICATION_JSON)
    @RolesAllowed(BACK_OFFICE)
    @Operation(summary = "Import index observations (CSV index,date,value or JSON); a PENDING batch for four-eyes")
    @Authorize(action = "treasury.marketdata.import")
    suspend fun import(
        @HeaderParam("Content-Type") contentType: String?,
        @QueryParam("reason") reason: String?,
        body: ByteArray?,
    ): Response {
        val bytes = requireNotNull(body) { "an import body is required" }
        require(bytes.size <= MarketDataFiles.MAX_BYTES) {
            "an import file is at most ${MarketDataFiles.MAX_BYTES} bytes"
        }
        val rows = if (contentType?.startsWith(MediaType.APPLICATION_JSON) ==
            true
        ) {
            json(bytes)
        } else {
            MarketDataFiles.parseCsv(bytes)
        }
        val batch = fixings.proposeImport(
            rows,
            requireNotNull(reason) { "query parameter 'reason' is required" },
            MarketDataFiles.sha256(bytes),
            actor(),
        )
        return Response.status(Response.Status.CREATED).entity(BatchResponse.from(batch)).build()
    }

    private fun json(bytes: ByteArray): List<Triple<String, LocalDate, BigDecimal>> {
        val node: JsonNode = runCatching { objectMapper.readTree(bytes) }
            .getOrElse { throw IllegalArgumentException("the import body is not valid JSON") }
        require(node.isArray && node.size() in 1..MarketDataFiles.MAX_ROWS) {
            "a JSON import is an array of 1..${MarketDataFiles.MAX_ROWS} rows"
        }
        return node.mapIndexed { i, r ->
            MarketDataFiles.row(i + 1, r.get("index")?.asText(), r.get("date")?.asText(), r.get("value")?.asText())
        }
    }

    @GET
    @Path("/market-data/imports")
    @Operation(summary = "Market-data batches (imports and manual fixings), newest first")
    @Authorize(action = "treasury.marketdata.read")
    suspend fun batches(@QueryParam("status") status: String?, @QueryParam("limit") limit: Int?): List<BatchResponse> =
        fixings.batches(
            status?.let { s ->
                requireNotNull(
                    BatchStatus.entries.firstOrNull {
                        it.name == s
                    },
                ) { "status must be one of ${BatchStatus.entries}" }
            },
            boundedLimit(limit),
        ).map { BatchResponse.from(it) }

    @POST
    @Path("/market-data/imports/{batchId}/approve")
    @RolesAllowed(APPROVER, SENIOR_APPROVER)
    @Operation(summary = "Approve an import: its observations become ACTIVE (four-eyes)")
    @Authorize(action = "treasury.marketdata.approve")
    suspend fun approveImport(@PathParam("batchId") batchId: String): BatchResponse =
        BatchResponse.from(fixings.approve(batchId(batchId), actor()))

    @POST
    @Path("/market-data/imports/{batchId}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(APPROVER, SENIOR_APPROVER)
    @Operation(summary = "Reject an import")
    @Authorize(action = "treasury.marketdata.approve")
    suspend fun rejectImport(@PathParam("batchId") batchId: String, request: RejectRequest): BatchResponse =
        BatchResponse.from(
            fixings.reject(
                batchId(batchId),
                requireNotNull(request.reason) {
                    "reason is required"
                },
                actor(),
            ),
        )

    @GET
    @Path("/market-data/quality")
    @Operation(summary = "Suspicious (stale, jump) and missing values for the indices due on a date")
    @Authorize(action = "treasury.marketdata.read")
    suspend fun quality(@QueryParam("date") date: String?): Map<String, Any?> {
        val r = fixings.quality(date(date, "date"))
        return mapOf(
            "date" to r.date,
            "suspicious" to r.suspicious.map {
                mapOf(
                    "index" to it.index,
                    "date" to it.fixingDate,
                    "value" to it.observation?.value,
                    "previous" to it.previous,
                    "jumpBp" to it.jumpBp,
                    "staleDays" to it.staleDays,
                    "reason" to it.reason,
                )
            },
            "missing" to
                r.missing.map { (v, deals) -> mapOf("index" to v.index, "date" to v.fixingDate, "dealCount" to deals) },
        )
    }

    private fun boundedLimit(limit: Int?): Int {
        val l = limit ?: DEFAULT_LIMIT
        require(l in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        return l
    }

    private companion object {
        const val DEFAULT_LIMIT = 500
        const val MAX_LIMIT = 5000
        const val MAX_EXPORT = 20_000
    }
}

data class ManualFixingRequest(val rate: BigDecimal? = null, val reason: String? = null, val source: String? = null)

data class RejectRequest(val reason: String? = null)

data class ObservationResponse(
    val index: String,
    val date: LocalDate,
    val value: BigDecimal,
    val source: String,
    val status: String,
    val batchId: UUID,
    val sha256: String,
) {
    companion object {
        fun from(o: IndexObservation) =
            ObservationResponse(o.index, o.date, o.value, o.source.name, o.status.name, o.batchId, o.sha256)
    }
}

data class BatchResponse(
    val batchId: UUID,
    val kind: String,
    val status: String,
    val reason: String,
    val fileSha256: String?,
    val rowCount: Int,
    val rows: List<ObservationResponse>,
    val createdBy: String,
    val createdAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
) {
    companion object {
        private const val MAX_ROWS_SHOWN = 100

        fun from(b: ObservationBatch) = BatchResponse(
            batchId = b.batchId,
            kind = b.kind.name,
            status = b.status.name,
            reason = b.reason,
            fileSha256 = b.fileSha256,
            rowCount = b.rows.size,
            rows = b.rows.take(MAX_ROWS_SHOWN).map { ObservationResponse.from(it) },
            createdBy = b.createdBy.id,
            createdAt = b.createdAt,
            decidedBy = b.decidedBy?.id,
            decidedAt = b.decidedAt,
            decisionReason = b.decisionReason,
        )
    }
}
