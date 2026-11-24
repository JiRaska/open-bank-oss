// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.sca.application.port.`in`.GetScaUseCase
import com.openbank.sca.application.usecase.ScaChallengeNotFoundException
import com.openbank.sca.domain.model.ReservedNamespace
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaPurpose
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** The narrow projection delegation-service can use to verify a completed connect approval. */
data class McpConnectReceipt(
    val challengeId: UUID,
    val partyId: UUID,
    val decidedByPartyId: UUID,
    val purpose: ScaPurpose,
    val approvalRequestId: String,
    val contextSha256: String,
    val consumedAt: String,
) {
    companion object {
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        fun from(challenge: ScaChallenge): McpConnectReceipt? {
            val consumedAt = challenge.consumedAt ?: return null
            val decider = challenge.decidedByPartyId ?: return null
            val linking = challenge.dynamicLinkingData ?: return null
            val approvalId = linking.approvalRequestId ?: return null
            val hash = linking.payloadSha256 ?: return null
            if (challenge.purpose != ScaPurpose.MCP_CLIENT_CONNECT || !challenge.isCompleted()) return null
            if (decider != challenge.partyId || challenge.onBehalfOfPartyId != null) return null
            if (challenge.decidedByCredentialId.isNullOrBlank()) return null
            if (!ReservedNamespace.MCP_CONNECT.isWellFormed(approvalId) || !SHA256.matches(hash)) return null
            return McpConnectReceipt(
                challenge.id,
                challenge.partyId,
                decider,
                challenge.purpose,
                approvalId,
                hash.lowercase(),
                consumedAt.toString(),
            )
        }
    }
}

@Path("/api/v1/sca/challenges/{id}/mcp-connect-receipt")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class McpConnectReceiptResource(private val getSca: GetScaUseCase, private val identity: SecurityIdentity) {
    @GET
    @Authorize(action = "scaChallenge.readMcpConnectReceipt", resource = "#id")
    suspend fun getMcpConnectReceipt(@PathParam("id") id: UUID): Response {
        if (!ServiceAccountIdentity.isPrincipal(identity, "service-account-openbank-delegation")) {
            throw ForbiddenException("MCP connect receipt requires the delegation service identity")
        }
        val receipt = McpConnectReceipt.from(readChallenge(id))?.takeIf { it.challengeId == id }
            ?: throw ScaChallengeNotFoundException(id)
        return Response.ok(receipt).header("Cache-Control", "no-store").build()
    }

    private suspend fun readChallenge(id: UUID): ScaChallenge = runCatching { getSca.getChallenge(id) }
        .getOrElse { failure ->
            throw when (failure) {
                is CancellationException, is ScaChallengeNotFoundException -> failure
                is Exception -> ServiceUnavailableException(
                    Response.status(Response.Status.SERVICE_UNAVAILABLE).build(),
                    failure,
                )
                else -> failure
            }
        }
}
