// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/**
 * The profile switcher's list (ADR-0284 D4/D6): the human's PERSONAL profile plus one BUSINESS
 * profile per entity they hold an active mandate for. Always keyed off the token — the header
 * that switches profiles is deliberately NOT honoured here, so a client can always find its way
 * back to the personal profile.
 *
 * The personal profile is present even when the human has no product of their own yet (a
 * business-only customer): it carries `hasProducts` so the app can render "open your first
 * account" instead of an empty home.
 */
@Path("/customer/v1/profiles")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerProfilesResource(
    private val upstream: UpstreamClient,
    private val actingFor: ActingForResolver,
    private val partyMergeResolver: PartyMergeResolver,
) {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    @ConfigProperty(name = "openbank.edge.account-service-url")
    lateinit var accountServiceUrl: String

    @GET
    @Blocking
    fun profiles(): Response {
        val human = humanPartyId()
        val personal = upstream.get("$partyServiceUrl/api/v1/parties/$human", human.toString())
        val personalNode = (personal.entity as? String)?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
        val accounts = upstream.get("$accountServiceUrl/api/v1/accounts?partyId=$human", human.toString())
        val hasProducts = (accounts.entity as? String)?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
            ?.let { n -> (if (n.isArray) n else n.path("data").takeIf { it.isArray } ?: n.path("items")).size() > 0 }
            ?: false
        val business = actingFor.profilesOf(human).map { p ->
            mapOf(
                "kind" to "BUSINESS",
                "partyId" to p["partyId"],
                "partyType" to p["partyType"],
                "displayName" to (p["tradingName"] ?: p["legalName"]),
                "legalName" to p["legalName"],
                "status" to p["status"],
                "kycStatus" to p["kycStatus"],
                "registrationNumber" to p["registrationNumber"],
                "registrationCountry" to p["registrationCountry"],
                "legalForm" to p["legalForm"],
                "role" to p["role"],
                "authority" to p["authority"],
            )
        }
        val result = listOf(
            mapOf(
                "kind" to "PERSONAL",
                "partyId" to human,
                "partyType" to "INDIVIDUAL",
                "displayName" to personalNode?.path("legalName")?.asText(null),
                "legalName" to personalNode?.path("legalName")?.asText(null),
                "status" to personalNode?.path("status")?.asText(null),
                "kycStatus" to personalNode?.path("kycStatus")?.asText(null),
                "hasProducts" to hasProducts,
            ),
        ) + business
        return Response.ok(mapOf("profiles" to result)).build()
    }

    private fun humanPartyId(): UUID {
        val claim = CustomerEdgeResource.resolvePartyIdClaim(jwt.getClaim<String>("party_id"), jwt.subject)
            ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val claimed = runCatching {
            UUID.fromString(claim)
        }.getOrElse { throw ForbiddenException("party_id claim is not a UUID") }
        return partyMergeResolver.resolve(claimed)
    }
}
