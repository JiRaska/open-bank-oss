// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.copilot.infrastructure.rest

import com.openbank.copilot.infrastructure.authz.OpaToolGate
import com.openbank.copilot.infrastructure.persistence.ProposalTokenStore
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.jwt.JsonWebToken
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Path("/api/v1/copilot/actions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
class ActionConfirmResource {
    private val log = Logger.getLogger(ActionConfirmResource::class.java)

    @Inject
    lateinit var tokenStore: ProposalTokenStore

    @Inject
    lateinit var opaGate: OpaToolGate

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var clock: Clock

    @POST
    @Path("/{tokenId}/confirm")
    @Authenticated
    @Operation(
        summary = "Confirm a copilot action proposal",
        description = "Exchanges a one-time proposal token for a confirmed action event.",
    )
    @APIResponses(
        APIResponse(responseCode = "200", description = "Action confirmed"),
        APIResponse(responseCode = "403", description = "OPA policy denied or token ownership mismatch"),
        APIResponse(responseCode = "404", description = "Token not found"),
        APIResponse(responseCode = "422", description = "Token expired"),
    )
    fun confirm(@PathParam("tokenId") tokenIdStr: String): Response = runBlocking {
        val customerId = customerSubject()
            ?: return@runBlocking Response.status(Response.Status.UNAUTHORIZED)
                .entity(mapOf("error" to "authenticated token is missing a usable subject"))
                .build()

        val tokenId = runCatching { UUID.fromString(tokenIdStr) }.getOrElse {
            return@runBlocking Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "PROPOSAL_NOT_FOUND"))
                .build()
        }

        val token = tokenStore.find(tokenId)
            ?: return@runBlocking Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "PROPOSAL_NOT_FOUND"))
                .build()

        if (token.customerId != customerId) {
            log.warnf(
                "ActionConfirmResource: ownership mismatch token=%s jwt_sub=%s token_customer=%s",
                tokenId,
                customerId,
                token.customerId,
            )
            return@runBlocking Response.status(Response.Status.FORBIDDEN)
                .entity(mapOf("error" to "PROPOSAL_NOT_FOUND"))
                .build()
        }

        if (Instant.now(clock).isAfter(token.expiresAt)) {
            tokenStore.delete(tokenId)
            return@runBlocking Response.status(HTTP_UNPROCESSABLE_ENTITY)
                .entity(mapOf("error" to "PROPOSAL_EXPIRED"))
                .build()
        }

        val denied = checkOpaGate(tokenId, token.toolName, customerId)
        if (denied != null) return@runBlocking denied

        val actionId = UUID.randomUUID()
        log.infof(
            "ActionConfirmResource: confirmed token=%s tool=%s customer=%s actionId=%s",
            tokenId,
            token.toolName,
            customerId,
            actionId,
        )
        tokenStore.delete(tokenId)
        Response.ok(mapOf("status" to "CONFIRMED", "actionId" to actionId.toString())).build()
    }

    private fun checkOpaGate(tokenId: UUID, toolName: String, customerId: String): Response? = try {
        opaGate.authorize(toolName, customerId, null)
        null
    } catch (e: WebApplicationException) {
        log.warnf(
            e,
            "ActionConfirmResource: OPA denied confirm for token=%s tool=%s customer=%s",
            tokenId,
            toolName,
            customerId,
        )
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to "ACTION_DENIED")).build()
    }

    private companion object {
        const val HTTP_UNPROCESSABLE_ENTITY = 422
    }

    private fun customerSubject(): String? {
        val principal = identity.principal
        return (principal as? JsonWebToken)?.subject?.takeIf { it.isNotBlank() }
            ?: principal?.name?.takeIf { it.isNotBlank() }
    }
}
