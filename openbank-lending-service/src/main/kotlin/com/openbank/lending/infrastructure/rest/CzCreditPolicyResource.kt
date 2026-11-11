// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.usecase.CzCreditPolicyActivationService
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.libs.decision.PolicyBundle
import com.openbank.libs.governance.MakerCheckerViolation
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.vertx.pgclient.PgException
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
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
) {
    @POST
    @Authorize(action = "lending.policy.propose")
    fun propose(bundle: PolicyBundle): Uni<Response> =
        activations.propose(bundle, staffActor()).map { Response.status(Response.Status.CREATED).entity(it).build() }

    @GET
    @Authorize(action = "lending.policy.read")
    fun pending(): Uni<Response> {
        staffActor()
        return activations.pending().map { Response.ok(it).header("Cache-Control", "no-store").build() }
    }

    @POST
    @Path("/{id}/decide")
    @Authorize(action = "lending.policy.decide", resource = "#id")
    fun decide(@PathParam("id") id: UUID, request: CzCreditPolicyDecisionRequest): Uni<Response> {
        val actor = staffActor()
        val approve = requireNotNull(request.approve) { "An explicit decision is required" }
        return activations.decide(id, approve, actor, request.reason)
            .map { Response.ok(it).build() }
            .onFailure(MakerCheckerViolation::class.java)
            .recoverWithItem { _ -> response(MAKER_CHECKER_STATUS, "The checker must differ from the maker") }
            .onFailure { activeRevisionConflict(it) }
            .recoverWithItem { _ ->
                response(Response.Status.CONFLICT.statusCode, "That policy version or effective day is already active")
            }
    }

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
        const val MAKER_CHECKER_STATUS = 422
        const val UNIQUE_VIOLATION = "23505"
        val ACTIVE_UNIQUES = setOf("uq_cz_policy_active_revision", "uq_cz_policy_active_day")
    }
}

data class CzCreditPolicyDecisionRequest(val approve: Boolean? = null, val reason: String? = null)
