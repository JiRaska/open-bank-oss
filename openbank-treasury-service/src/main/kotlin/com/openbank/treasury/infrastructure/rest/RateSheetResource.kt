// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.domain.marketdata.RateSheet
import com.openbank.treasury.domain.marketdata.RateSheetRate
import com.openbank.treasury.domain.marketdata.RateSheetStatus
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.infrastructure.marketdata.RateSheetStore
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
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class RateSheetRequest(
    val name: String? = null,
    val effectiveFrom: LocalDate? = null,
    val rates: List<RateSheetRateRequest>? = null,
)

data class RateSheetRateRequest(val label: String? = null, val value: BigDecimal? = null)

data class RateSheetResponse(
    val id: UUID,
    val name: String,
    val effectiveFrom: LocalDate,
    val status: String,
    val rates: List<RateSheetRate>,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
) {
    companion object {
        fun from(s: RateSheet) = RateSheetResponse(
            s.id,
            s.name,
            s.effectiveFrom,
            s.status.name,
            s.rates,
            s.proposedBy.id,
            s.proposedAt,
            s.decidedBy?.id,
            s.decidedAt,
            s.decisionReason,
        )
    }
}

/**
 * Contract 8.5 internal rate sheets: back office proposes, a DIFFERENT human approver approves or
 * rejects (four-eyes; never an AI agent) — the same roles and OPA actions as market-data imports and
 * their approval. `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Treasury", description = "Treasury internal rate sheets (contract 8.5)")
@Path("/api/v1/treasury/market-data/internal-rate-sheets")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE)
class RateSheetResource {

    @Inject
    lateinit var store: RateSheetStore

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var clock: Clock

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    private fun id(raw: String): UUID = runCatching {
        UUID.fromString(raw)
    }.getOrElse { throw IllegalArgumentException("rate-sheet id must be a UUID") }

    @GET
    @Operation(summary = "Internal rate sheets, newest first (status filter; limit 1..500, default 100)")
    @Authorize(action = "treasury.marketdata.read")
    suspend fun list(@QueryParam("status") status: String?, @QueryParam("limit") limit: Int?): List<RateSheetResponse> {
        val l = limit ?: DEFAULT_LIMIT
        require(l in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        val s = status?.let { v ->
            requireNotNull(
                RateSheetStatus.entries.firstOrNull {
                    it.name == v
                },
            ) { "status must be one of ${RateSheetStatus.entries}" }
        }
        return store.list(s, l).map(RateSheetResponse::from)
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(BACK_OFFICE)
    @Operation(summary = "Propose a rate sheet — PENDING until a different human approves it")
    @Authorize(action = "treasury.marketdata.import")
    suspend fun propose(request: RateSheetRequest?): Response {
        val r = requireNotNull(request) { "a request body is required" }
        val rates = requireNotNull(r.rates) { "'rates' is required" }.map {
            RateSheetRate(
                requireNotNull(it.label) {
                    "every rate needs a label"
                },
                requireNotNull(it.value) { "every rate needs a value" },
            )
        }
        val sheet = RateSheet.propose(
            requireNotNull(r.name) { "'name' is required" },
            requireNotNull(r.effectiveFrom) { "'effectiveFrom' is required" },
            rates,
            actor(),
            clock.instant(),
        )
        store.propose(sheet)
        return Response.status(Response.Status.CREATED).entity(RateSheetResponse.from(sheet)).build()
    }

    @POST
    @Path("/{id}/approve")
    @RolesAllowed(APPROVER, SENIOR_APPROVER)
    @Operation(summary = "Approve a rate sheet (four-eyes: never its proposer, never an AI agent)")
    @Authorize(action = "treasury.marketdata.approve")
    suspend fun approve(@PathParam("id") id: String): RateSheetResponse {
        val actor = actor()
        val at = clock.instant()
        return RateSheetResponse.from(store.decide(id(id)) { it.approve(actor, at) })
    }

    @POST
    @Path("/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(APPROVER, SENIOR_APPROVER)
    @Operation(summary = "Reject a rate sheet with a reason")
    @Authorize(action = "treasury.marketdata.approve")
    suspend fun reject(@PathParam("id") id: String, request: RejectRequest?): RateSheetResponse {
        val reason = requireNotNull(request?.reason) { "reason is required" }
        val actor = actor()
        val at = clock.instant()
        return RateSheetResponse.from(store.decide(id(id)) { it.reject(actor, reason, at) })
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
    }
}
