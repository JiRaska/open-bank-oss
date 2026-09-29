// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/**
 * The customer's personal AML profile (AML Act §9 declaration + FATCA/CRS self-certification),
 * proxied to party-service.
 *
 * The profile belongs to the HUMAN, so the party id comes from the customer's token only:
 * - never from the body — the body is forwarded as the declaration, and any `partyId` in it is
 *   dropped before forwarding;
 * - never from `X-Acting-For` — the profile-switch header is deliberately not honoured here (no
 *   [ActingForResolver] is consulted), so a customer acting for a company still reads and declares
 *   their OWN profile, which is exactly what business onboarding needs before the company
 *   questionnaire;
 * - a merged identity is followed to its survivor ([PartyMergeResolver]), as everywhere else.
 *
 * party-service's status and body are passed through unchanged — a 400 names the rule that
 * failed, so the app can show it next to the field.
 */
@Path("/customer/v1/me/aml-profile")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerAmlProfileResource(
    private val upstream: UpstreamClient,
    private val partyMergeResolver: PartyMergeResolver,
) {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    @GET
    @Authorize(action = "customer.amlProfile.read")
    @Blocking
    fun get(): Response {
        val human = humanPartyId()
        return upstream.get(profileUrl(human), human.toString())
    }

    @PUT
    @Authorize(action = "customer.amlProfile.write")
    @Blocking
    fun put(body: String?): Response {
        val human = humanPartyId()
        val node = body?.takeIf { it.isNotBlank() }
            ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() } as? ObjectNode
            ?: return Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to "BAD_REQUEST", "message" to "the AML profile must be a JSON object"))
                .type(MediaType.APPLICATION_JSON)
                .build()
        // The subject is the token's party — a client-supplied id has no say, and is not forwarded.
        node.remove(CLIENT_PARTY_FIELDS)
        return upstream.put(profileUrl(human), human.toString(), objectMapper.writeValueAsString(node))
    }

    private fun profileUrl(party: UUID) = "$partyServiceUrl/api/v1/parties/$party/aml-profile"

    private fun humanPartyId(): UUID {
        val claim = CustomerEdgeResource.resolvePartyIdClaim(jwt.getClaim<String>("party_id"), jwt.subject)
            ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val claimed = runCatching { UUID.fromString(claim) }
            .getOrElse { throw ForbiddenException("party_id claim is not a UUID") }
        return partyMergeResolver.resolve(claimed)
    }

    private companion object {
        val CLIENT_PARTY_FIELDS = listOf("partyId", "id")
    }
}
