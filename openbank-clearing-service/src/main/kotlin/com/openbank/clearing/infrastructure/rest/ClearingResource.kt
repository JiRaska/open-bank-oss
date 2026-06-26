// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearing.infrastructure.rest

import com.openbank.clearing.application.port.`in`.*
import com.openbank.clearing.domain.model.*
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

/**
 * Access control (K7 / ADR-0018): clearing aggregates many payments into settlement, so the prior
 * class-level `@PermitAll` was a money-path exposure. Replaced with per-operation least-privilege
 * roles: submission is for the payment-ops/service identity; reads are open to payment-ops, viewers
 * and operators; **settlement and cycle triggering are restricted to payment-ops/admin** (these are
 * the high-blast-radius actions — four-eyes via ADR-0034 MakerChecker is a tracked follow-up).
 * Enforced by Quarkus OIDC and locked by ClearingResourceSecurityTest.
 */
@Path("/api/v1/clearing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Clearing", description = "Payment clearing & settlement")
class ClearingResource(
    private val submitUseCase: SubmitPaymentUseCase,
    private val getBatchUseCase: GetBatchUseCase,
    private val getItemUseCase: GetItemUseCase,
    private val triggerUseCase: TriggerClearingUseCase,
    private val positionsUseCase: GetPositionsUseCase,
) {

    @POST
    @Path("/submit")
    @RolesAllowed(Roles.SERVICE, Roles.PAYMENTS, Roles.ADMIN)
    @Operation(summary = "Submit payment for clearing")
    fun submit(request: SubmitPaymentRequest): Uni<Response> = submitUseCase.submit(request)
        .map { Response.status(Response.Status.CREATED).entity(it).build() }
        .onFailure().recoverWithItem { e -> Response.serverError().entity(mapOf("error" to e.message)).build() }

    @GET
    @Path("/batches")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Operation(summary = "List clearing batches")
    fun listBatches(
        @QueryParam("status") status: String?,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
    ): Uni<List<ClearingBatch>> = getBatchUseCase.listBatches(status?.let { ClearingStatus.valueOf(it) }, page, size)

    @GET
    @Path("/batches/{id}")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Operation(summary = "Get clearing batch by ID")
    fun getBatch(@PathParam("id") id: UUID): Uni<Response> = getBatchUseCase.getBatch(id)
        .map { it?.let { b -> Response.ok(b).build() } ?: Response.status(404).build() }

    @GET
    @Path("/batches/{id}/items")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Operation(summary = "List items in a clearing batch")
    fun getBatchItems(@PathParam("id") id: UUID): Uni<List<ClearingItem>> = getItemUseCase.listItemsByBatch(id)

    @POST
    @Path("/batches/{id}/settle")
    @RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.settle", resource = "#id")
    @Operation(summary = "Settle a clearing batch")
    fun settleBatch(@PathParam("id") id: UUID): Uni<Response> = triggerUseCase.settleBatch(id)
        .map { Response.ok(it).build() }
        .onFailure().recoverWithItem { e -> Response.serverError().entity(mapOf("error" to e.message)).build() }

    @POST
    @Path("/cycle/trigger")
    @RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)
    @Operation(summary = "Trigger a clearing cycle for a payment rail")
    fun triggerCycle(@QueryParam("rail") @DefaultValue("SEPA_SCT") rail: String): Uni<Response> =
        triggerUseCase.triggerClearingCycle(PaymentRail.valueOf(rail))
            .map { Response.ok(it).build() }
            .onFailure().recoverWithItem { e -> Response.serverError().entity(mapOf("error" to e.message)).build() }

    @GET
    @Path("/positions/{cycleId}")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Operation(summary = "Get settlement positions for a cycle")
    fun getPositions(@PathParam("cycleId") cycleId: String): Uni<List<SettlementPosition>> =
        positionsUseCase.getPositions(cycleId)

    @GET
    @Path("/items/{id}")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Operation(summary = "Get clearing item by ID")
    fun getItem(@PathParam("id") id: UUID): Uni<Response> = getItemUseCase.getItem(id)
        .map { it?.let { i -> Response.ok(i).build() } ?: Response.status(404).build() }

    @GET
    @Path("/items/by-payment/{paymentId}")
    @RolesAllowed(Roles.SERVICE, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Operation(summary = "Get clearing items by payment ID")
    fun getItemsByPayment(@PathParam("paymentId") paymentId: UUID): Uni<List<ClearingItem>> =
        getItemUseCase.listItemsByPayment(paymentId)
}
