// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.usecase.CzCreditPolicyActivationService
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.RequestFingerprints
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.pgclient.PgException
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.jwt.JsonWebToken
import org.hibernate.exception.ConstraintViolationException
import java.util.UUID

@Path("/api/v1/lending/risk/policy/proposals")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CREDIT_RISK")
class CzCreditPolicyResource(
    private val activations: CzCreditPolicyActivationService,
    private val identity: SecurityIdentity,
    private val idempotencyStore: IdempotencyStore,
    private val mapper: ObjectMapper,
) {
    @POST
    @Authorize(action = "lending.policy.propose")
    suspend fun propose(@HeaderParam("Idempotency-Key") idempotencyKey: String?, bundle: PolicyBundle?): Response {
        val actor = staffActor()
        val key = scopedKey(actor, idempotencyKey)
        val payload = requireNotNull(bundle) { "A policy bundle is required" }
        val fingerprint = RequestFingerprints.of(mapper, "POST", PROPOSALS_PATH, payload)
        return idempotencyStore.submitOnce(key, fingerprint, REPLAY_TTL_SECONDS) {
            jsonResponse(Response.Status.CREATED, activations.propose(payload, actor).awaitSuspending())
        }
    }

    @GET
    @Authorize(action = "lending.policy.read")
    fun pending(): Uni<Response> {
        staffActor()
        return activations.pending().map { Response.ok(it).header("Cache-Control", "no-store").build() }
    }

    @POST
    @Path("/{id}/decide")
    @Authorize(action = "lending.policy.decide", resource = "#id")
    suspend fun decide(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: CzCreditPolicyDecisionRequest?,
    ): Response {
        val actor = staffActor()
        val key = scopedKey(actor, idempotencyKey)
        val payload = requireNotNull(request) { "A policy decision is required" }
        val approve = requireNotNull(payload.approve) { "An explicit decision is required" }
        val fingerprint = RequestFingerprints.of(mapper, "POST", "$PROPOSALS_PATH/$id/decide", payload)
        return idempotencyStore.submitOnce(key, fingerprint, REPLAY_TTL_SECONDS) {
            activations.decide(id, approve, actor, payload.reason)
                .map { jsonResponse(Response.Status.OK, it) }
                .onFailure(MakerCheckerViolation::class.java)
                .recoverWithItem { _ -> response(MAKER_CHECKER_STATUS, "The checker must differ from the maker") }
                .onFailure { activeRevisionConflict(it) }
                .recoverWithItem { _ ->
                    response(
                        Response.Status.CONFLICT.statusCode,
                        "That policy version or effective day is already active",
                    )
                }.awaitSuspending()
        }
    }

    private fun scopedKey(actor: String, key: String?): String = IdempotencyScope("lending-credit-policy", actor)
        .storeKey(requireNotNull(key) { "Idempotency-Key header is required" })

    private fun jsonResponse(status: Response.Status, body: Any): Response = Response.status(status)
        .entity(mapper.writeValueAsString(body)).type(MediaType.APPLICATION_JSON).build()

    private fun staffActor(): String {
        if (!ServiceAccountIdentity.isHumanStaff(identity)) {
            throw ForbiddenException("Credit policy authoring requires an interactive staff identity")
        }
        return (identity.principal as JsonWebToken).subject
    }

    private fun response(status: Int, error: String) = Response.status(status).entity(mapOf("error" to error)).build()

    private fun activeRevisionConflict(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        when (it) {
            is ConstraintViolationException -> it.sqlState == UNIQUE_VIOLATION && it.constraintName in ACTIVE_UNIQUES
            is PgException -> it.sqlState == UNIQUE_VIOLATION && it.constraint in ACTIVE_UNIQUES
            else -> false
        }
    }

    private companion object {
        const val PROPOSALS_PATH = "/api/v1/lending/risk/policy/proposals"
        const val REPLAY_TTL_SECONDS = 300L
        const val MAKER_CHECKER_STATUS = 422
        const val UNIQUE_VIOLATION = "23505"
        val ACTIVE_UNIQUES = setOf("uq_cz_policy_active_revision", "uq_cz_policy_active_day")
    }
}

data class CzCreditPolicyDecisionRequest(val approve: Boolean? = null, val reason: String? = null)
