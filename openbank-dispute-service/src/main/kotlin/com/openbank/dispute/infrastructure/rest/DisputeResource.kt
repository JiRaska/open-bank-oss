// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.rest

import com.openbank.dispute.application.port.`in`.*
import com.openbank.dispute.domain.model.*
import com.openbank.libs.authz.Authorize
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@Path("/api/v1/disputes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Disputes", description = "Dispute and chargeback management")
@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
class DisputeResource(
    private val openUseCase: OpenDisputeUseCase,
    private val updateUseCase: UpdateDisputeUseCase,
    private val getUseCase: GetDisputeUseCase,
) {
    @POST
    @Operation(summary = "Open a new dispute")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    fun open(request: OpenDisputeRequest): Uni<Response> =
        openUseCase.open(request).map { Response.status(201).entity(it).build() }

    @GET
    @Authorize(action = "dispute.list")
    @Operation(summary = "List disputes by status")
    fun list(@QueryParam("status") status: String?): Uni<List<Dispute>> = if (status != null) {
        getUseCase.listByStatus(DisputeStatus.valueOf(status))
    } else {
        getUseCase.listByStatus(DisputeStatus.OPEN)
    }

    @GET
    @Path("/{id}")
    @Authorize(action = "dispute.read", resource = "#id")
    @Operation(summary = "Get dispute by ID")
    fun get(@PathParam("id") id: UUID): Uni<Response> =
        getUseCase.getDispute(id).map { it?.let { d -> Response.ok(d).build() } ?: Response.status(404).build() }

    @GET
    @Path("/reference/{ref}")
    @Authorize(action = "dispute.read", resource = "#ref")
    @Operation(summary = "Get dispute by reference")
    fun getByRef(@PathParam("ref") ref: String): Uni<Response> =
        getUseCase.getByReference(ref).map { it?.let { d -> Response.ok(d).build() } ?: Response.status(404).build() }

    @GET
    @Path("/account/{accountId}")
    @Authorize(action = "dispute.list", resource = "#accountId")
    @Operation(summary = "List disputes for an account")
    fun listByAccount(@PathParam("accountId") accountId: UUID): Uni<List<Dispute>> = getUseCase.listByAccount(accountId)

    @PUT
    @Path("/{id}")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "dispute.update", resource = "#id")
    @Operation(summary = "Update dispute status/resolution")
    fun update(@PathParam("id") id: UUID, request: UpdateDisputeRequest): Uni<Response> =
        updateUseCase.update(id, request).map { Response.ok(it).build() }

    @POST
    @Path("/{id}/evidence")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Operation(summary = "Add evidence to a dispute")
    fun addEvidence(@PathParam("id") id: UUID, evidence: DisputeEvidence): Uni<Response> =
        updateUseCase.addEvidence(id, evidence).map { Response.status(201).entity(it).build() }

    @POST
    @Path("/{id}/withdraw")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Operation(summary = "Withdraw a dispute")
    fun withdraw(@PathParam("id") id: UUID, @QueryParam("actor") actor: String?): Uni<Response> =
        updateUseCase.withdraw(id, requiredActor(actor)).map { Response.ok(it).build() }

    @POST
    @Path("/{id}/escalate")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Operation(summary = "Escalate a dispute")
    fun escalate(@PathParam("id") id: UUID, @QueryParam("actor") actor: String?): Uni<Response> =
        updateUseCase.escalate(id, requiredActor(actor)).map { Response.ok(it).build() }

    @POST
    @Path("/{id}/resolve")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "dispute.resolve", resource = "#id")
    @Operation(
        summary = "Record a dispute's remediation outcome",
        description = "Evidence-backed resolution (UPHELD/REJECTED/PARTIAL, ADR-0117). An " +
            "UPHELD or PARTIAL outcome emits a dispute.remediation_requested event for a future " +
            "downstream consumer; this service does not itself move money.",
    )
    fun resolve(@PathParam("id") id: UUID, request: ResolveDisputeRequest): Uni<Response> =
        updateUseCase.resolve(id, request).map { Response.ok(it).build() }
            .onFailure(IllegalArgumentException::class.java).recoverWithItem { e ->
                Response.status(UNPROCESSABLE_ENTITY).entity(mapOf("error" to e.message)).build()
            }
            .onFailure(IllegalStateException::class.java).recoverWithItem { e ->
                Response.status(Response.Status.CONFLICT).entity(mapOf("error" to e.message)).build()
            }

    @GET
    @Path("/{id}/timeline")
    @Authorize(action = "dispute.read", resource = "#id")
    @Operation(summary = "Get dispute timeline")
    fun getTimeline(@PathParam("id") id: UUID): Uni<List<DisputeTimelineEvent>> = getUseCase.getTimeline(id)

    @GET
    @Path("/{id}/evidence")
    @Authorize(action = "dispute.read", resource = "#id")
    @Operation(summary = "Get dispute evidence")
    fun getEvidence(@PathParam("id") id: UUID): Uni<List<DisputeEvidence>> = getUseCase.getEvidence(id)

    @GET
    @Path("/{id}/evidence/verify")
    @RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_API")
    @Authorize(action = "dispute.read", resource = "#id")
    @Operation(summary = "Walk and verify a dispute's evidence hash chain (ADR-0117/ADR-0133 pattern)")
    fun verifyEvidenceChain(@PathParam("id") id: UUID): Uni<EvidenceChainVerification> =
        getUseCase.verifyEvidenceChain(id)

    /**
     * `actor` is who withdrew or escalated the dispute — it is written to the timeline as
     * attribution, so it is genuinely required and has no sensible default.
     *
     * The parameter MUST be declared nullable for this guard to be reachable: JAX-RS injects `null`
     * for an absent query parameter, and on a non-`suspend` handler a non-nullable Kotlin type
     * compiles to an `Intrinsics.checkNotNullParameter` at offset 0 — the NPE lands before the first
     * statement of the body and the generic mapper answers 500 (issue #3104/#3624). Written against
     * the old signature, `requireNotNull` would have compiled to nothing.
     *
     * The throw is synchronous (the argument is evaluated before the `Uni` is built), so it escapes
     * the handler rather than the reactive pipeline, and libs-runtime's
     * `IllegalArgumentExceptionMapper` renders 400 + `ApiError`. Note this is deliberately NOT
     * routed through `resolve`'s `onFailure(IllegalArgumentException)` → 422: a missing parameter is
     * a malformed request, not a business-rule violation.
     */
    private fun requiredActor(actor: String?): String {
        requireNotNull(actor) { "query parameter 'actor' is required" }
        return actor
    }

    companion object {
        /** RFC 4918 status code; not present in JAX-RS' [Response.Status] enum. */
        private const val UNPROCESSABLE_ENTITY = 422
    }
}
