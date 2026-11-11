// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.rest

import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.audit.decision.InputDigest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.authz.ServiceAccountIdentity
import com.openbank.sca.application.port.`in`.ConsumeScaCommand
import com.openbank.sca.application.port.`in`.ConsumeScaUseCase
import com.openbank.sca.application.usecase.ScaConsumerScopeViolationException
import com.openbank.sca.domain.model.ConsumerScope
import com.openbank.sca.domain.model.ReservedNamespace
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.UUID

data class ConsumeStatutoryApprovalRequest(
    val partyId: UUID?,
    val companyPartyId: UUID?,
    val approvalRequestId: String?,
    val payloadSha256: String?,
)

@Path("/api/v1/sca/challenges/{id}/consume-statutory-approval")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class StatutoryApprovalResource(
    private val consume: ConsumeScaUseCase,
    private val identity: SecurityIdentity,
    private val audit: AuditEventPublisher,
) {
    @POST
    @Authorize(action = "scaChallenge.consume.statutory", resource = "#id")
    suspend fun consume(@PathParam("id") id: UUID, body: ConsumeStatutoryApprovalRequest?): ScaChallengeResponse {
        if (!ServiceAccountIdentity.isPrincipal(identity, DELEGATION_PRINCIPAL)) {
            throw ForbiddenException("only delegation-service may verify statutory approval")
        }
        val actor = requireNotNull(body?.partyId) { "partyId is required" }
        val company = requireNotNull(body.companyPartyId) { "companyPartyId is required" }
        require(actor != company) { "the statutory signer must be a human acting for the company" }
        val requestId = requireNotNull(body.approvalRequestId) { "approvalRequestId is required" }
        val namespace = ReservedNamespace.DELEGATION_STATUTORY
        require(namespace.isWellFormed(requestId)) { "invalid statutory approval request identifier" }
        val digest = InputDigest(requireNotNull(body.payloadSha256) { "payloadSha256 is required" })
        val command = ConsumeScaCommand(
            challengeId = id,
            expectedPartyId = actor,
            amount = null,
            currency = null,
            creditor = null,
            approvalRequestId = requestId,
            payloadSha256 = digest.hex,
            scope = ConsumerScope.Reserved(namespace),
            statutoryPrincipalPartyId = company,
        )
        val approved = try {
            consume.consume(command)
        } catch (e: ScaConsumerScopeViolationException) {
            audit.auditConsume(DELEGATION_PRINCIPAL, actor, id, AuditResult.DENIED)
            throw e
        }
        audit.auditConsume(DELEGATION_PRINCIPAL, actor, id, AuditResult.SUCCESS)
        return ScaChallengeResponse.from(approved)
    }

    private companion object {
        const val DELEGATION_PRINCIPAL = "service-account-openbank-delegation"
    }
}
