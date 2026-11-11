// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openbank.customeredge.domain.model.CustomerIdentity
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Path("/customer/v1/delegations/statutory-operations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Blocking
@Suppress("TooManyFunctions") // Six customer actions share the same profile, request and cache boundaries.
class CustomerStatutoryDelegationResource(
    private val upstream: UpstreamClient,
    private val actingForResolver: ActingForResolver,
    private val mapper: ObjectMapper,
    private val jwt: JsonWebToken,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.edge.delegation-service-url")
    private val delegationServiceUrl: String,
    @ConfigProperty(name = "openbank.edge.sca-service-url")
    private val scaServiceUrl: String,
) {

    @POST
    @Path("/issue")
    @Authorize(action = "customer.delegation.statutory.issue")
    fun issue(
        body: JsonNode?,
        @HeaderParam(ACTING_FOR_HEADER) actingFor: String?,
        @HeaderParam("Idempotency-Key") requestKey: String?,
    ): Response {
        val customer = customer(actingFor)
        val draft = (body?.get("draft") as? ObjectNode)
            ?: throw IllegalArgumentException("draft must be a JSON object")
        val declared = draft.get("grantorPartyId")?.asText()?.takeIf { it.isNotBlank() }
        if (declared != null && declared != customer.partyId.toString()) {
            throw ForbiddenException("grantor must match the verified company profile")
        }
        draft.put("grantorPartyId", customer.partyId.toString())
        return post(customer, "/issue", body, requestKey(requestKey))
    }

    @POST
    @Path("/accept/{delegationId}")
    @Authorize(action = "customer.delegation.statutory.accept", resource = "#delegationId")
    fun accept(
        @PathParam("delegationId") delegationId: UUID,
        @HeaderParam(ACTING_FOR_HEADER) actingFor: String?,
        @HeaderParam("Idempotency-Key") requestKey: String?,
    ): Response = post(customer(actingFor), "/accept/$delegationId", null, requestKey(requestKey))

    @GET
    @Authorize(action = "customer.delegation.statutory.read")
    fun pending(@HeaderParam(ACTING_FOR_HEADER) actingFor: String?): Response = get(customer(actingFor), "")

    @GET
    @Path("/{id}")
    @Authorize(action = "customer.delegation.statutory.read", resource = "#id")
    fun get(@PathParam("id") id: UUID, @HeaderParam(ACTING_FOR_HEADER) actingFor: String?): Response =
        get(customer(actingFor), "/$id")

    @POST
    @Path("/{id}/challenge")
    @Authorize(action = "customer.delegation.statutory.challenge", resource = "#id")
    fun challenge(
        @PathParam("id") id: UUID,
        @HeaderParam(ACTING_FOR_HEADER) actingFor: String?,
        @HeaderParam("Idempotency-Key") requestKey: String?,
    ): Response {
        val customer = customer(actingFor)
        val key = requestKey(requestKey)
        val current = get(customer, "/$id")
        if (current.status != Response.Status.OK.statusCode) return current
        val operation = mapper.readTree(current.entity as String)
        val expiresAt = Instant.parse(operation.path("expiresAt").asText())
        val collecting = operation.path("status").asText() == "COLLECTING"
        val signed = operation.path("signedByCaller").asBoolean()
        if (!collecting || signed || !clock.instant().isBefore(expiresAt)) {
            return noStore(Response.status(Response.Status.CONFLICT).build())
        }
        val request = challengeBody(customer, operation, id)
        return noStore(
            upstream.post(
                "$scaServiceUrl/api/v1/sca/challenges",
                customer.human.toString(),
                mapper.writeValueAsString(request),
                key,
            ),
        )
    }

    @POST
    @Path("/{id}/sign")
    @Authorize(action = "customer.delegation.statutory.sign", resource = "#id")
    fun sign(
        @PathParam("id") id: UUID,
        body: JsonNode?,
        @HeaderParam(ACTING_FOR_HEADER) actingFor: String?,
    ): Response {
        val customer = customer(actingFor)
        val challenge = requireNotNull(body?.get("scaChallengeId")?.textValue()) { "scaChallengeId is required" }
        return post(customer, "/$id/sign", body, UUID.fromString(challenge).toString())
    }

    @POST
    @Path("/{id}/close")
    @Authorize(action = "customer.delegation.statutory.close", resource = "#id")
    fun close(
        @PathParam("id") id: UUID,
        body: JsonNode?,
        @HeaderParam(ACTING_FOR_HEADER) actingFor: String?,
    ): Response = post(customer(actingFor), "/$id/close", body, id.toString())

    private fun get(customer: CustomerIdentity, path: String): Response = noStore(
        upstream.getWithHeaders(
            "$delegationServiceUrl$UPSTREAM$path",
            customer.partyId.toString(),
            mapOf(ACTOR_PARTY_HEADER to customer.human.toString()),
        ),
    )

    private fun post(customer: CustomerIdentity, path: String, body: JsonNode?, requestKey: String): Response = noStore(
        upstream.post(
            "$delegationServiceUrl$UPSTREAM$path",
            customer.partyId.toString(),
            body?.let(mapper::writeValueAsString) ?: "{}",
            requestKey,
            mapOf(ACTOR_PARTY_HEADER to customer.human.toString()),
        ),
    )

    private fun customer(actingFor: String?): CustomerIdentity {
        val claim = CustomerEdgeResource.resolvePartyIdClaim(jwt.getClaim<String>("party_id"), jwt.subject)
        val human = runCatching { claim?.let(UUID::fromString) }.getOrNull()
            ?: throw ForbiddenException("a valid customer party claim is required")
        val company = actingForResolver.resolve(human, actingFor)
        if (company == human) throw ForbiddenException("a verified company profile is required")
        return CustomerIdentity(company, human)
    }

    private fun challengeBody(customer: CustomerIdentity, operation: JsonNode, id: UUID): Map<String, Any> {
        val action = operation.path("action").asText().lowercase()
        val approvalId = operation.path("approvalRequestId").asText()
        val hash = operation.path("payloadSha256").asText()
        val matchingOperation = operation.path("id").asText() == id.toString() &&
            operation.path("companyPartyId").asText() == customer.partyId.toString()
        val matchingLink = action in setOf("issue", "accept") &&
            approvalId == "delegation-statutory-$action:$id" &&
            HASH.matches(hash)
        check(matchingOperation && matchingLink) { "invalid statutory operation response" }
        return mapOf(
            "partyId" to customer.human,
            "onBehalfOfPartyId" to customer.partyId,
            "purpose" to "APPROVAL",
            "preferredMethod" to "BIOMETRIC",
            "dynamicLinkingData" to mapOf("approvalRequestId" to approvalId, "payloadSha256" to hash),
        )
    }

    private fun requestKey(value: String?): String {
        require(!value.isNullOrBlank() && value.length <= MAX_REQUEST_KEY) { "Idempotency-Key is required" }
        return value
    }

    private fun noStore(response: Response): Response =
        Response.fromResponse(response).header(HttpHeaders.CACHE_CONTROL, "no-store").build()

    private companion object {
        const val UPSTREAM = "/api/v1/delegations/statutory-operations"
        const val ACTING_FOR_HEADER = CustomerEdgeResource.ACTING_FOR_HEADER
        const val ACTOR_PARTY_HEADER = "X-Customer-Actor-Party-Id"
        const val MAX_REQUEST_KEY = 128
        val HASH = Regex("[0-9a-f]{64}")
    }
}
