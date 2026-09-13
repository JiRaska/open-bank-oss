// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.rest.EdgeJson.instant
import com.openbank.customeredge.infrastructure.rest.EdgeJson.int
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/**
 * Lístky (ADR-0282) for the customer app, proxying `openbank-loyalty-service` with the edge M2M
 * token. The party is the token's ([CustomerPartyResolver]); loyalty-service's party-scoped paths
 * are built from it and from nothing the client sends.
 *
 * Every response is a PROJECTION. The operator-facing shape carries `ruleVersion`, lot-level
 * `remainingLeaves`, `partyId` and `earnedTotal`, none of which the app contract includes.
 *
 * `grantStatus` is passed through verbatim. GRANTED means the benefit is owed and published for its
 * delivering engine, never that it was applied (`Benefit.kt`), so this resource invents no
 * "active"/"applied" state either.
 */
@Path("/customer/v1/loyalty")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerLoyaltyResource(private val upstream: UpstreamClient, private val parties: CustomerPartyResolver) {
    @ConfigProperty(name = "openbank.edge.loyalty-service-url")
    lateinit var loyaltyServiceUrl: String

    @Context
    lateinit var requestHeaders: HttpHeaders

    /** Balance, this year's earnings, the next expiry and the ledger history. */
    @GET
    @Authorize(action = "customer.loyalty.read")
    @Blocking
    fun summary(): Response {
        val party = party()
        val response = upstream.get("$loyaltyServiceUrl$API/parties/$party", party.toString())
        if (response.status != Response.Status.OK.statusCode) return EdgeJson.upstreamFailure(response, SERVICE)
        val node = EdgeJson.parse(response)?.takeIf { it.isObject } ?: return badUpstream()
        return EdgeJson.ok(
            mapOf(
                "balance" to (node.int("balance") ?: 0),
                "earnedThisYear" to (node.int("earnedThisYear") ?: 0),
                "nextExpiry" to node.instant("nextExpiry")?.toString(),
                "history" to node.path("history").map { e ->
                    mapOf(
                        "id" to e.text("id"),
                        "type" to e.text("type"),
                        "leaves" to (e.int("leaves") ?: 0),
                        "earnSourceId" to e.text("earnSourceId"),
                        "benefitId" to e.text("benefitId"),
                        "occurredAt" to e.text("occurredAt"),
                        "expiresAt" to e.text("expiresAt"),
                    )
                },
            ),
        )
    }

    /** The reviewed benefit catalogue. Prices are in Lístky only — there is no currency to show. */
    @GET
    @Path("/benefits")
    @Authorize(action = "customer.loyalty.read")
    @Blocking
    fun benefits(): Response = catalogue("/benefits") { b ->
        mapOf(
            "id" to b.text("id"),
            "engine" to b.text("engine"),
            "priceLeaves" to b.int("priceLeaves"),
            "validityDays" to b.int("validityDays"),
            "description" to b.text("description"),
        )
    }

    /** The reviewed earn catalogue. A listed source is not a claim that anything awards it yet. */
    @GET
    @Path("/earn-sources")
    @Authorize(action = "customer.loyalty.read")
    @Blocking
    fun earnSources(): Response = catalogue("/earn-sources") { s ->
        mapOf("id" to s.text("id"), "leaves" to s.int("leaves"), "validityDays" to s.int("validityDays"))
    }

    /**
     * Redeem Lístky for a catalogue benefit. The caller's `Idempotency-Key` is forwarded unchanged:
     * loyalty-service keys grants on (party, key), so a retry resolves to the grant it already made
     * (ALREADY_GRANTED) instead of burning twice.
     *
     * 200 for both GRANTED and ALREADY_GRANTED (upstream distinguishes 201/200; the `outcome` field
     * carries that distinction for the app). 404 for an unknown benefit: loyalty-service answers
     * that case 400, and by the time the edge forwards a request it has already rejected the only
     * other 400 causes (missing key, malformed body) itself.
     */
    @POST
    @Path("/redemptions")
    @Authorize(action = "customer.loyalty.redeem")
    @Blocking
    fun redeem(body: String?, @HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return EdgeJson.error(BAD_REQUEST, "Idempotency-Key header is required")
        if (key.length > MAX_IDEMPOTENCY_KEY_LENGTH) return EdgeJson.error(BAD_REQUEST, "Idempotency-Key is too long")
        val benefitId = EdgeJson.parseObject(body)?.text("benefitId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return EdgeJson.error(BAD_REQUEST, "benefitId is required")
        if (!BENEFIT_ID.matches(benefitId)) return unknownBenefit()

        val party = party()
        val response = upstream.post(
            "$loyaltyServiceUrl$API/parties/$party/redeem",
            party.toString(),
            EdgeJson.mapper.writeValueAsString(mapOf("benefitId" to benefitId)),
            key,
        )
        val node = EdgeJson.parse(response)?.takeIf { it.isObject }
        return when (response.status) {
            Response.Status.OK.statusCode, Response.Status.CREATED.statusCode -> node?.let {
                EdgeJson.ok(
                    mapOf(
                        "outcome" to it.text("outcome"),
                        "grantId" to it.text("grantId"),
                        "benefitId" to it.text("benefitId"),
                        "grantStatus" to it.text("grantStatus"),
                    ),
                )
            } ?: badUpstream()
            Response.Status.CONFLICT.statusCode -> node?.let {
                EdgeJson.ok(
                    mapOf(
                        "outcome" to "INSUFFICIENT_LEAVES",
                        "requiredLeaves" to it.int("requiredLeaves"),
                        "availableLeaves" to it.int("availableLeaves"),
                    ),
                    Response.Status.CONFLICT.statusCode,
                )
            } ?: badUpstream()
            BAD_REQUEST -> unknownBenefit()
            else -> EdgeJson.upstreamFailure(response, SERVICE)
        }
    }

    /** The caller's grants, newest first. `validUntil` is the grant's expiry; null when it has none. */
    @GET
    @Path("/grants")
    @Authorize(action = "customer.loyalty.read")
    @Blocking
    fun grants(): Response {
        val party = party()
        val response = upstream.get("$loyaltyServiceUrl$API/parties/$party/grants", party.toString())
        if (response.status != Response.Status.OK.statusCode) return EdgeJson.upstreamFailure(response, SERVICE)
        val node = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return badUpstream()
        return EdgeJson.ok(
            node.map { g ->
                mapOf(
                    "grantId" to g.text("grantId"),
                    "benefitId" to g.text("benefitId"),
                    "grantStatus" to g.text("grantStatus"),
                    "grantedAt" to g.text("grantedAt"),
                    "validUntil" to g.text("expiresAt"),
                )
            },
        )
    }

    private fun catalogue(path: String, project: (JsonNode) -> Map<String, Any?>): Response {
        val party = party()
        val response = upstream.get("$loyaltyServiceUrl$API$path", party.toString())
        if (response.status != Response.Status.OK.statusCode) return EdgeJson.upstreamFailure(response, SERVICE)
        val node = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return badUpstream()
        return EdgeJson.ok(node.map(project))
    }

    private fun party(): UUID = parties.resolve(
        if (this::requestHeaders.isInitialized) {
            requestHeaders.getHeaderString(CustomerEdgeResource.ACTING_FOR_HEADER)
        } else {
            null
        },
    )

    private fun unknownBenefit() = EdgeJson.error(Response.Status.NOT_FOUND.statusCode, "unknown benefit")

    private fun badUpstream() = EdgeJson.error(Response.Status.BAD_GATEWAY.statusCode, "unexpected $SERVICE response")

    private companion object {
        const val API = "/api/v1/loyalty"
        const val SERVICE = "loyalty service"
        const val BAD_REQUEST = 400
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 255

        /** Catalogue ids are UPPER_SNAKE; anything else cannot name a benefit and never goes upstream. */
        val BENEFIT_ID = Regex("^[A-Z0-9_]{1,64}$")
    }
}
