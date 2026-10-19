// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.usecase.CorrectionService
import com.openbank.treasury.application.usecase.CorrectionView
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.CorrectionTerms
import com.openbank.treasury.domain.model.PostedDelta
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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The partial terms of `POST /deals/{id}/corrections` (contract 6): absent = unchanged. */
data class CorrectionChangesRequest(
    val legs: List<LegSpecDto>? = null,
    val rate: BigDecimal? = null,
    val calendars: List<String>? = null,
    val businessDayConvention: String? = null,
    val note: String? = null,
)

data class CorrectionRequest(
    val changes: CorrectionChangesRequest? = null,
    /** True: unwind the deal completely (reverse everything it posted; the deal ends REVERSED). */
    val fullUnwind: Boolean? = null,
    val reason: String? = null,
    val effectiveFrom: LocalDate? = null,
    /** The corrected exposure breaches the counterparty limit: ask for a senior override instead of being refused. */
    val requestLimitOverride: Boolean? = null,
)

data class CorrectionDto(
    val correctionId: UUID,
    val dealId: UUID,
    val status: String,
    val baseVersion: Int,
    val newVersion: Int?,
    val fullUnwind: Boolean,
    val reason: String,
    val effectiveFrom: LocalDate,
    val changes: CorrectionTerms,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val posted: List<PostedDelta>,
    val limitOverrideRequested: Boolean,
    val limitOverrideBy: String?,
    val limitOverrideCoversExposureUpTo: BigDecimal?,
    /** `{ newFlows, changedFlows, deltaJournals }` as computed when proposed. */
    val preview: JsonNode?,
) {
    companion object {
        fun from(v: CorrectionView, mapper: ObjectMapper) = v.correction.let { c ->
            CorrectionDto(
                c.correctionId,
                c.dealId,
                c.status.name,
                c.baseVersion,
                c.newVersion,
                c.fullUnwind,
                c.reason,
                c.effectiveFrom,
                c.terms,
                c.proposedBy.id,
                c.proposedAt,
                c.decidedBy?.id,
                c.decidedAt,
                c.decisionReason,
                c.posted,
                c.limitOverrideRequested,
                c.limitOverride?.by?.id,
                c.limitOverride?.coversExposureUpTo,
                v.preview?.let(mapper::readTree),
            )
        }
    }
}

/**
 * ADR-0344 D7 corrections of a booked deal. Back office or the dealer proposes (an AI agent only
 * through its charter, and only a proposal); the approver decides. The domain refuses as decider the
 * proposer, every term author of the deal and any non-human principal.
 */
@Tag(name = "Treasury", description = "Treasury deal corrections (ADR-0344 D7)")
@Path("/api/v1/treasury/deals/{id}/corrections")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE)
class CorrectionResource {

    @Inject
    lateinit var corrections: CorrectionService

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var mapper: ObjectMapper

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    private fun requireKey(key: String?): String = requireNotNull(key) { "header '$IDEMPOTENCY_KEY' is required" }

    @GET
    @Operation(summary = "The deal's corrections, oldest first")
    @Authorize(action = "treasury.deal.read", resource = "#id")
    suspend fun list(@PathParam("id") id: UUID): List<CorrectionDto> =
        corrections.list(id).map { CorrectionDto.from(it, mapper) }

    @GET
    @Path("/{correctionId}")
    @Operation(summary = "One correction with its preview")
    @Authorize(action = "treasury.deal.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID, @PathParam("correctionId") correctionId: UUID): CorrectionDto =
        CorrectionDto.from(corrections.get(id, correctionId), mapper)

    @POST
    @RolesAllowed(DEALER, BACK_OFFICE)
    @Operation(summary = "Propose a correction (PENDING, with a preview of the new flows and the delta journals)")
    @Authorize(action = "treasury.deal.correction.propose", resource = "#id")
    suspend fun propose(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: CorrectionRequest,
    ): Response {
        val ch = request.changes
        val terms = CorrectionTerms(
            legs = ch?.legs?.map { it.toDomain() },
            rate = ch?.rate,
            calendars = ch?.calendars,
            businessDayConvention = ch?.businessDayConvention,
            note = ch?.note,
        )
        val v = corrections.propose(
            id,
            terms,
            request.fullUnwind ?: false,
            requireNotNull(request.reason) { "reason is required" },
            requireNotNull(request.effectiveFrom) { "effectiveFrom is required" },
            actor(),
            requireKey(key),
            request.requestLimitOverride ?: false,
        )
        return Response.status(Response.Status.CREATED).entity(CorrectionDto.from(v, mapper)).build()
    }

    @POST
    @Path("/{correctionId}/approve")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Approve a pending correction (four-eyes; never a term author, never an AI agent)")
    @Authorize(action = "treasury.deal.correction.approve", resource = "#id")
    suspend fun approve(
        @PathParam("id") id: UUID,
        @PathParam("correctionId") correctionId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
    ): CorrectionDto = CorrectionDto.from(corrections.approve(id, correctionId, actor(), requireKey(key)), mapper)

    @POST
    @Path("/{correctionId}/override-limit")
    @RolesAllowed(SENIOR_APPROVER)
    @Operation(
        summary = "A senior overrides the counterparty-limit breach the corrected terms cause (never a term author)",
    )
    @Authorize(action = "treasury.deal.correction.override-limit", resource = "#id")
    suspend fun overrideLimit(
        @PathParam("id") id: UUID,
        @PathParam("correctionId") correctionId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): CorrectionDto = CorrectionDto.from(
        corrections.overrideLimit(
            id,
            correctionId,
            requireNotNull(request.reason) { "reason is required" },
            actor(),
            requireKey(key),
        ),
        mapper,
    )

    @POST
    @Path("/{correctionId}/reject")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Reject a pending correction with a reason")
    @Authorize(action = "treasury.deal.correction.reject", resource = "#id")
    suspend fun reject(
        @PathParam("id") id: UUID,
        @PathParam("correctionId") correctionId: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): CorrectionDto = CorrectionDto.from(
        corrections.reject(
            id,
            correctionId,
            requireNotNull(request.reason) { "reason is required" },
            actor(),
            requireKey(key),
        ),
        mapper,
    )
}
