// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.rest

import com.openbank.clearing.application.port.`in`.GetBatchUseCase
import com.openbank.clearing.application.port.`in`.GetItemUseCase
import com.openbank.clearing.application.port.`in`.GetPositionsUseCase
import com.openbank.clearing.application.port.`in`.ReconcileUseCase
import com.openbank.clearing.application.port.`in`.SubmitPaymentUseCase
import com.openbank.clearing.application.port.`in`.TriggerClearingUseCase
import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.ClearingStatus
import com.openbank.clearing.domain.model.PaymentRail
import com.openbank.clearing.domain.model.SettlementPosition
import com.openbank.clearing.domain.model.SubmitPaymentRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
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
import java.util.UUID

/**
 * Access control (K7 / ADR-0018): clearing aggregates many payments into settlement, so the prior
 * class-level `@PermitAll` was a money-path exposure. Replaced with per-operation least-privilege
 * roles: submission is for the payment-ops/service identity; reads are open to payment-ops, viewers
 * and operators; **settlement and cycle triggering are restricted to payment-ops/admin** (these are
 * the high-blast-radius actions — four-eyes via ADR-0034 MakerChecker is a tracked follow-up).
 * Enforced by Quarkus OIDC and locked by `ClearingSecurityContractTest`.
 *
 * ADR-0034 Phase 5 (issue #266): every endpoint now also carries `@Authorize` under the
 * `clearingBatch.*` action namespace — the pre-existing convention from `settleBatch`
 * (`clearingBatch.settle`), kept as-is rather than renamed to the `clearing.` money-path
 * scope. `rules.yaml` normalises `openbank-clearing-service` to the `clearing` scope for
 * the base `four_eyes` rule, so a fleet-wide prefix fix (tracked separately, issue #395/#396)
 * is needed before four-eyes can auto-fire for this rail; until then dual-control on
 * `settleBatch`/`triggerCycle` is enforced only via the narrow `clearing_rest_ext.rego`
 * role checks below, not the shared `four_eyes_required` augmentation.
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
    private val reconcileUseCase: ReconcileUseCase,
) {

    @POST
    @Path("/submit")
    // No M2M grant: this carried the dead `ROLE_SERVICE` name until #2442 made the M2M role real
    // (ROLE_API, granted to service-account-openbank-services). The post-grant audit found NO
    // service in this repo that calls it — agent-service is the only holder of a clearing-service
    // REST client and it uses `GET /batches` only — so admitting a service token here would widen a
    // money-path submit for a caller that does not exist. Add Roles.API back in the same change
    // that adds the caller.
    @RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.submit")
    @Operation(summary = "Submit payment for clearing")
    fun submit(request: SubmitPaymentRequest): Uni<Response> = submitUseCase.submit(request)
        .map { Response.status(Response.Status.CREATED).entity(it).build() }

    @GET
    @Path("/batches")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.list")
    @Operation(summary = "List clearing batches")
    fun listBatches(
        @QueryParam("status") status: String?,
        @QueryParam("page") @DefaultValue("0") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
    ): Uni<List<ClearingBatch>> = getBatchUseCase.listBatches(status?.let { ClearingStatus.valueOf(it) }, page, size)

    @GET
    @Path("/batches/{id}")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.read", resource = "#id")
    @Operation(summary = "Get clearing batch by ID")
    fun getBatch(@PathParam("id") id: UUID): Uni<Response> = getBatchUseCase.getBatch(id)
        .map { it?.let { b -> Response.ok(b).build() } ?: Response.status(404).build() }

    @GET
    @Path("/batches/{id}/items")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.readItems", resource = "#id")
    @Operation(summary = "List items in a clearing batch")
    fun getBatchItems(@PathParam("id") id: UUID): Uni<List<ClearingItem>> = getItemUseCase.listItemsByBatch(id)

    @POST
    @Path("/batches/{id}/settle")
    @RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.settle", resource = "#id")
    @Operation(summary = "Settle a clearing batch")
    fun settleBatch(@PathParam("id") id: UUID): Uni<Response> = triggerUseCase.settleBatch(id)
        .map { Response.ok(it).build() }

    @POST
    @Path("/cycle/trigger")
    @RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.triggerCycle")
    @Operation(summary = "Trigger a clearing cycle for a payment rail")
    fun triggerCycle(@QueryParam("rail") @DefaultValue("SEPA_SCT") rail: String): Uni<Response> =
        triggerUseCase.triggerClearingCycle(PaymentRail.valueOf(rail))
            .map { Response.ok(it).build() }

    @GET
    @Path("/positions/{cycleId}")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.readPositions")
    @Operation(summary = "Get settlement positions for a cycle")
    fun getPositions(@PathParam("cycleId") cycleId: String): Uni<List<SettlementPosition>> =
        positionsUseCase.getPositions(cycleId)

    @GET
    @Path("/items/{id}")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.readItem", resource = "#id")
    @Operation(summary = "Get clearing item by ID")
    fun getItem(@PathParam("id") id: UUID): Uni<Response> = getItemUseCase.getItem(id)
        .map { it?.let { i -> Response.ok(i).build() } ?: Response.status(404).build() }

    @GET
    @Path("/items/by-payment/{paymentId}")
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.readItemsByPayment", resource = "#paymentId")
    @Operation(summary = "Get clearing items by payment ID")
    fun getItemsByPayment(@PathParam("paymentId") paymentId: UUID): Uni<List<ClearingItem>> =
        getItemUseCase.listItemsByPayment(paymentId)

    @GET
    @Path("/batches/{id}/reconcile")
    @RolesAllowed(Roles.PAYMENTS, Roles.ADMIN)
    @Authorize(action = "clearingBatch.reconcile", resource = "#id")
    @Operation(summary = "Run internal reconciliation check for a settled batch")
    fun reconcile(@PathParam("id") id: UUID): Uni<Response> = reconcileUseCase.reconcileBatch(id)
        .map { report ->
            if (report.clean) {
                Response.ok(report).build()
            } else {
                Response.status(Response.Status.CONFLICT).entity(report).build()
            }
        }
        .onFailure(IllegalArgumentException::class.java)
        .recoverWithItem { e -> Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build() }
}
