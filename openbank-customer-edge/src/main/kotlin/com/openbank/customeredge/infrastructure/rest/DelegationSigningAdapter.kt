// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.application.port.out.BusinessSigningPort
import com.openbank.customeredge.application.port.out.SigningReply
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/**
 * [BusinessSigningPort] over delegation-service's internal REST routes, through the edge's shared
 * [UpstreamClient] (service token, loopback/`.svc` host allow-list, timeouts) like every other
 * upstream here. Path segments are only UUIDs or a closed status vocabulary, never caller text.
 */
@ApplicationScoped
@Suppress("TooManyFunctions")
class DelegationSigningAdapter(private val upstream: UpstreamClient, private val objectMapper: ObjectMapper) :
    BusinessSigningPort {

    // Same property and default as CustomerDelegationResource — one service, one address.
    @ConfigProperty(
        name = "openbank.edge.delegation-service-url",
        defaultValue = "http://delegation-service.delegation.svc:8126",
    )
    lateinit var delegationServiceUrl: String

    private fun entities(entity: UUID) = "$delegationServiceUrl/api/v1/entities/$entity"

    private fun reply(r: Response) = SigningReply(r.status, (r.entity as? String).orEmpty())

    private fun json(vararg pairs: Pair<String, Any?>): String =
        objectMapper.writeValueAsString(pairs.filter { it.second != null }.toMap())

    override fun evaluate(entity: UUID, body: String) =
        reply(upstream.post("${entities(entity)}/signing/evaluate", entity.toString(), body))

    override fun policy(entity: UUID) = reply(upstream.get("${entities(entity)}/signing-policy", entity.toString()))

    override fun changePolicy(entity: UUID, body: String) =
        reply(upstream.put("${entities(entity)}/signing-policy", entity.toString(), body))

    override fun trustedPayees(entity: UUID) =
        reply(upstream.get("${entities(entity)}/trusted-payees", entity.toString()))

    override fun addTrustedPayee(entity: UUID, body: String) =
        reply(upstream.post("${entities(entity)}/trusted-payees", entity.toString(), body))

    override fun removeTrustedPayee(entity: UUID, payeeId: String, initiator: UUID) = reply(
        upstream.delete(
            "${entities(entity)}/trusted-payees/$payeeId",
            entity.toString(),
            json("initiatorPartyId" to initiator.toString()),
        ),
    )

    override fun createApproval(entity: UUID, body: String) =
        reply(upstream.post("${entities(entity)}/approval-requests", entity.toString(), body))

    override fun approvals(entity: UUID, status: String?, signer: UUID?): SigningReply {
        val query = listOfNotNull(status?.let { "status=$it" }, signer?.let { "signer=$it" })
            .joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return reply(upstream.get("${entities(entity)}/approval-requests$query", entity.toString()))
    }

    override fun approval(entity: UUID, approvalId: UUID) =
        reply(upstream.get("${entities(entity)}/approval-requests/$approvalId", entity.toString()))

    override fun sign(entity: UUID, approvalId: UUID, signer: UUID, scaChallengeId: UUID) = reply(
        upstream.post(
            "${entities(entity)}/approval-requests/$approvalId/signatures",
            entity.toString(),
            json("partyId" to signer.toString(), "scaChallengeId" to scaChallengeId.toString()),
        ),
    )

    override fun reject(entity: UUID, approvalId: UUID, signer: UUID, reason: String?) = reply(
        upstream.post(
            "${entities(entity)}/approval-requests/$approvalId/rejection",
            entity.toString(),
            json("partyId" to signer.toString(), "reason" to reason),
        ),
    )

    override fun releaseClaim(entity: UUID, approvalId: UUID) = reply(
        upstream.post("${entities(entity)}/approval-requests/$approvalId/release-claim", entity.toString(), "{}"),
    )

    override fun releaseResult(entity: UUID, approvalId: UUID, body: String) = reply(
        upstream.post("${entities(entity)}/approval-requests/$approvalId/release-result", entity.toString(), body),
    )

    override fun pendingFor(human: UUID) = reply(
        upstream.get("$delegationServiceUrl/api/v1/parties/$human/approval-requests/pending", human.toString()),
    )
}
