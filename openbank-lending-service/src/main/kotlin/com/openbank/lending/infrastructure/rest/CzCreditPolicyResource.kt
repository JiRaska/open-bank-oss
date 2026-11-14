// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.CzCreditPolicyCommand
import com.openbank.lending.application.usecase.CzCreditPolicyActivationService
import com.openbank.lending.application.usecase.CzCreditPolicyCommandView
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.RequestFingerprints
import com.openbank.libs.util.Hex
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
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
import java.security.MessageDigest
import java.util.UUID

@Path("/api/v1/lending/risk/policy/proposals")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CREDIT_RISK")
class CzCreditPolicyResource(
    private val activations: CzCreditPolicyActivationService,
    private val identity: SecurityIdentity,
    private val mapper: ObjectMapper,
) {
    @POST
    @Authorize(action = "lending.policy.propose")
    fun propose(@HeaderParam("Idempotency-Key") idempotencyKey: String?, bundle: PolicyBundle?): Uni<Response> {
        val actor = staffActor()
        val payload = requireNotNull(bundle) { "A policy bundle is required" }
        val command = command(actor, idempotencyKey, PROPOSALS_PATH, payload)
        return activations.propose(payload, actor, command).map { jsonResponse(Response.Status.CREATED, it) }
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
    fun decide(
        @PathParam("id") id: UUID,
        @HeaderParam("Idempotency-Key") idempotencyKey: String?,
        request: CzCreditPolicyDecisionRequest?,
    ): Uni<Response> {
        val actor = staffActor()
        val payload = requireNotNull(request) { "A policy decision is required" }
        val approve = requireNotNull(payload.approve) { "An explicit decision is required" }
        val command = command(actor, idempotencyKey, "$PROPOSALS_PATH/$id/decide", payload)
        return activations.decide(id, approve, actor, payload.reason, command)
            .map { jsonResponse(Response.Status.OK, it) }
            .onFailure(MakerCheckerViolation::class.java)
            .recoverWithItem { _ -> response(MAKER_CHECKER_STATUS, "The checker must differ from the maker") }
            .onFailure { constraintViolation(it, UNIQUE_VIOLATION, ACTIVE_UNIQUES) }
            .recoverWithItem { _ ->
                response(Response.Status.CONFLICT.statusCode, "That policy version or effective day is already active")
            }
            .onFailure { constraintViolation(it, CHECK_VIOLATION, FUTURE_CHECK) }
            .transform { IllegalArgumentException("A credit policy revision must take effect on a future day", it) }
    }

    private fun command(actor: String, key: String?, path: String, body: Any): CzCreditPolicyCommand {
        val scopedKey = IdempotencyScope("lending-credit-policy", actor)
            .storeKey(requireNotNull(key) { "Idempotency-Key header is required" })
        val keyDigest = Hex.lower(MessageDigest.getInstance("SHA-256").digest(scopedKey.toByteArray(Charsets.UTF_8)))
        return CzCreditPolicyCommand(keyDigest, RequestFingerprints.of(mapper, "POST", path, body))
    }

    private fun jsonResponse(status: Response.Status, command: CzCreditPolicyCommandView): Response {
        val result = Response.status(status)
            .entity(mapper.writeValueAsString(command.view))
            .type(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
        if (command.replayed) result.header("X-Idempotency-Replayed", "true")
        return result.build()
    }

    private fun staffActor(): String {
        if (!ServiceAccountIdentity.isHumanStaff(identity)) {
            throw ForbiddenException("Credit policy authoring requires an interactive staff identity")
        }
        return (identity.principal as JsonWebToken).subject
    }

    private fun response(status: Int, error: String) = Response.status(status).entity(mapOf("error" to error)).build()

    private fun constraintViolation(error: Throwable, sqlState: String, constraints: Set<String>): Boolean =
        generateSequence(error) { it.cause }.any {
            when (it) {
                is ConstraintViolationException -> it.sqlState == sqlState && it.constraintName in constraints
                is PgException -> it.sqlState == sqlState && it.constraint in constraints
                else -> false
            }
        }

    private companion object {
        const val PROPOSALS_PATH = "/api/v1/lending/risk/policy/proposals"
        const val MAKER_CHECKER_STATUS = 422
        const val UNIQUE_VIOLATION = "23505"
        const val CHECK_VIOLATION = "23514"
        val FUTURE_CHECK = setOf("ck_cz_policy_future_approval")
        val ACTIVE_UNIQUES = setOf("uq_cz_policy_active_revision", "uq_cz_policy_active_day")
    }
}

data class CzCreditPolicyDecisionRequest(val approve: Boolean? = null, val reason: String? = null)
