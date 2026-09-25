// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.port.`in`.DraftDealCommand
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.DealState
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
import java.time.LocalDate
import java.util.UUID

/**
 * Money-market deals (ADR-0315). Roles are literal realm names (#10618), like risk-engine's
 * ROLE_RISK: ROLE_TREASURY_DEALER drafts, submits and cancels; ROLE_TREASURY_APPROVER approves,
 * rejects, settles, matures and reverses. RBAC here, OPA (`treasury_rest_ext.rego`, human-only)
 * behind it, and the domain's four-eyes / non-human checks behind both — the domain is the one
 * that holds when `AUTHZ_ENFORCE` is off.
 *
 * NOTE the annotation order: `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Treasury", description = "Money-market deals with four-eyes booking (ADR-0315)")
@Path("/api/v1/treasury")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER)
@Suppress("TooManyFunctions")
class TreasuryResource {

    @Inject
    lateinit var deals: TreasuryDealUseCase

    @Inject
    lateinit var identity: SecurityIdentity

    /** After a journal-posting transition, answer with the journal references too. */
    private suspend fun view(id: UUID): DealResponse = deals.get(id).let { DealResponse.from(it.deal, it.journals) }

    /**
     * Money-path idempotency (#8351): every command POST requires `Idempotency-Key`. Declared
     * nullable and checked here — a non-null Kotlin header parameter is a 500 for the absent case.
     */
    private fun requireKey(key: String?): String = requireNotNull(key) { "header '$IDEMPOTENCY_KEY' is required" }

    private fun actor(): Actor = Actor.fromPrincipalName(identity.principal.name)

    @GET
    @Path("/deals")
    @Operation(summary = "Dealer blotter: list deals, optionally filtered by state")
    @Authorize(action = "treasury.deal.read")
    suspend fun list(@QueryParam("state") state: DealState?): List<DealResponse> =
        deals.list(state).map { DealResponse.from(it) }

    @POST
    @Path("/deals")
    @RolesAllowed(DEALER)
    @Operation(summary = "Draft a deal (DRAFT); nothing posts")
    @Authorize(action = "treasury.deal.draft")
    suspend fun draft(@HeaderParam("Idempotency-Key") key: String?, request: DraftDealRequest): Response {
        val deal = deals.draft(
            DraftDealCommand(
                product = requireNotNull(request.product) { "product is required" },
                counterpartyId = requireNotNull(request.counterpartyId) { "counterpartyId is required" },
                currency = requireNotNull(request.currency) { "currency is required" },
                principal = requireNotNull(request.principal) { "principal is required" },
                rate = requireNotNull(request.rate) { "rate is required" },
                tradeDate = request.tradeDate,
                valueDate = requireNotNull(request.valueDate) { "valueDate is required" },
                maturityDate = request.maturityDate,
                rationale = request.rationale,
            ),
            actor(),
            requireKey(key),
        )
        return Response.status(Response.Status.CREATED).entity(DealResponse.from(deal)).build()
    }

    @GET
    @Path("/deals/{id}")
    @Operation(summary = "One deal with its lifecycle timeline and ledger journal references")
    @Authorize(action = "treasury.deal.read", resource = "#id")
    suspend fun get(@PathParam("id") id: UUID): DealResponse =
        deals.get(id).let { DealResponse.from(it.deal, it.journals) }

    @POST
    @Path("/deals/{id}/submit")
    @RolesAllowed(DEALER)
    @Operation(summary = "Submit for approval (PENDING_APPROVAL); runs the counterparty-limit check")
    @Authorize(action = "treasury.deal.submit", resource = "#id")
    suspend fun submit(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        DealResponse.from(deals.submit(id, actor(), requireKey(key)))

    @POST
    @Path("/deals/{id}/cancel")
    @RolesAllowed(DEALER, APPROVER)
    @Operation(summary = "Cancel a DRAFT or PENDING_APPROVAL deal")
    @Authorize(action = "treasury.deal.cancel", resource = "#id")
    suspend fun cancel(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        DealResponse.from(deals.cancel(id, actor(), requireKey(key)))

    @POST
    @Path("/deals/{id}/approve")
    @RolesAllowed(APPROVER)
    @Operation(
        summary = "Four-eyes approval, books the deal (422: approver is creator/submitter, or limit breach)",
    )
    @Authorize(action = "treasury.deal.approve", resource = "#id")
    suspend fun approve(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        DealResponse.from(deals.approve(id, actor(), requireKey(key)))

    @POST
    @Path("/deals/{id}/reject")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Reject a pending deal back to DRAFT with a reason")
    @Authorize(action = "treasury.deal.reject", resource = "#id")
    suspend fun reject(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): DealResponse = DealResponse.from(
        deals.reject(id, requireNotNull(request.reason) { "reason is required" }, actor(), requireKey(key)),
    )

    @POST
    @Path("/deals/{id}/settle")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Settle a BOOKED deal on or after its value date; posts the settlement journal")
    @Authorize(action = "treasury.deal.settle", resource = "#id")
    suspend fun settle(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        deals.settle(id, actor(), requireKey(key)).let { view(id) }

    @POST
    @Path("/deals/{id}/mature")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Mature a SETTLED deal on or after its maturity date; posts principal and interest")
    @Authorize(action = "treasury.deal.mature", resource = "#id")
    suspend fun mature(@PathParam("id") id: UUID, @HeaderParam("Idempotency-Key") key: String?): DealResponse =
        deals.mature(id, actor(), requireKey(key)).let { view(id) }

    @POST
    @Path("/deals/{id}/reverse")
    @RolesAllowed(APPROVER)
    @Operation(summary = "Reverse a BOOKED or SETTLED deal; a settled one gets an offsetting journal")
    @Authorize(action = "treasury.deal.reverse", resource = "#id")
    suspend fun reverse(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") key: String?,
        request: ReasonRequest,
    ): DealResponse =
        deals.reverse(id, requireNotNull(request.reason) { "reason is required" }, actor(), requireKey(key))
            .let { view(id) }

    @GET
    @Path("/counterparties")
    @Operation(summary = "Counterparty limits with current exposure and headroom per currency")
    @Authorize(action = "treasury.counterparty.read")
    suspend fun counterparties(): List<CounterpartyResponse> = deals.counterparties().map(CounterpartyResponse::from)

    @GET
    @Path("/positions")
    @Operation(summary = "Daily position per currency: placed, borrowed, at ČNB, net")
    @Authorize(action = "treasury.position.read")
    suspend fun positions(@QueryParam("asOf") asOf: LocalDate?): PositionsResponse {
        val date = asOf ?: LocalDate.now()
        return PositionsResponse(date, deals.positions(date).map(CurrencyPositionResponse::from))
    }
}

/** Realm roles (#10618), literal like risk-engine's: adding them to libs Roles.ALL is fleet-wide. */
const val DEALER = "ROLE_TREASURY_DEALER"
const val IDEMPOTENCY_KEY = "Idempotency-Key"
const val APPROVER = "ROLE_TREASURY_APPROVER"
