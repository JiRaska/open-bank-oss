// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Business onboarding for the customer app (ADR-0284 D6), proxying `openbank-kyb-service` with
 * the edge M2M token. **The initiator/signer identity is the token's HUMAN on every route** —
 * never the acting-for header (you onboard a company as yourself, not as another company) and
 * never a body field: a body naming a different `initiatorPartyId` is refused here before
 * kyb-service refuses it again on `X-Customer-Party-Id`.
 *
 * `/lookup` and `/schemes` are the entry screen — they create nothing and need no case.
 */
@Path("/customer/v1/business")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions") // one thin proxy per case transition
class CustomerBusinessResource(
    private val upstream: UpstreamClient,
    private val partyMergeResolver: PartyMergeResolver,
) {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.edge.kyb-service-url", defaultValue = "http://kyb-service.kyb.svc:8157")
    lateinit var kybServiceUrl: String

    @GET
    @Path("/schemes")
    @Blocking
    fun schemes(@QueryParam("country") country: String?): Response {
        // ISO 3166-1 alpha-2 or nothing. Same reasoning as the invitation token below.
        val filter = country?.takeIf { it.isNotBlank() }
        require(filter == null || COUNTRY.matches(filter)) {
            "country must be an ISO 3166-1 alpha-2 code"
        }
        val q = filter?.let { "?country=${enc(it)}" }.orEmpty()
        return upstream.get("$kybServiceUrl$UPSTREAM/schemes$q", human().toString())
    }

    @POST
    @Path("/lookup")
    @Blocking
    fun lookup(body: String): Response = upstream.post("$kybServiceUrl$UPSTREAM/lookup", human().toString(), body, null)

    /** Start a case. The initiator is filled in from the token; a conflicting one in the body is a 403. */
    @POST
    @Path("/onboarding")
    @Blocking
    fun start(body: String): Response {
        val me = human()
        val node = parse(body) ?: return badRequest("body must be a JSON object")
        val claimed = node.path("initiatorPartyId").takeIf { it.isTextual }?.asText()
        if (claimed != null &&
            claimed != me.toString()
        ) {
            return forbidden("initiatorPartyId is not the authenticated customer")
        }
        node.put("initiatorPartyId", me.toString())
        return upstream.post(
            "$kybServiceUrl$UPSTREAM/cases",
            me.toString(),
            objectMapper.writeValueAsString(node),
            null,
        )
    }

    /** Cases the caller initiated or is a signer on. */
    @GET
    @Path("/onboarding")
    @Blocking
    fun mine(): Response {
        val me = human()
        return upstream.get("$kybServiceUrl$UPSTREAM/cases?partyId=$me", me.toString())
    }

    @GET
    @Path("/onboarding/{id}")
    @Blocking
    fun get(@PathParam("id") id: UUID): Response = upstream.get("$kybServiceUrl$UPSTREAM/cases/$id", human().toString())

    @POST
    @Path("/onboarding/{id}/initiator")
    @Blocking
    fun matchInitiator(@PathParam("id") id: UUID, body: String): Response =
        upstream.post("$kybServiceUrl$UPSTREAM/cases/$id/initiator", human().toString(), body, null)

    @POST
    @Path("/onboarding/{id}/cosigners")
    @Blocking
    fun inviteCosigners(@PathParam("id") id: UUID, body: String): Response =
        upstream.post("$kybServiceUrl$UPSTREAM/cases/$id/cosigners", human().toString(), body, null)

    @POST
    @Path("/onboarding/{id}/sign")
    @Blocking
    fun sign(@PathParam("id") id: UUID, body: String): Response =
        upstream.post("$kybServiceUrl$UPSTREAM/cases/$id/sign", human().toString(), body, null)

    @POST
    @Path("/onboarding/{id}/abandon")
    @Blocking
    fun abandon(@PathParam("id") id: UUID): Response =
        upstream.post("$kybServiceUrl$UPSTREAM/cases/$id/abandon", human().toString(), "{}", null)

    /** An invited co-signer, now identity-verified as themselves, claims the invitation. The party is the token's. */
    @POST
    @Path("/invitations/{token}/claim")
    @Blocking
    fun claim(@PathParam("token") token: String): Response {
        val me = human()
        // The token is the only caller-supplied value this class ever puts in a URL PATH, so it
        // is checked for shape before it is used rather than merely encoded. URL-encoding alone
        // does make injection impossible here (the host is a fixed prefix and UpstreamClient
        // re-checks the whole URL against the allowed-host regex), but "impossible for two
        // reasons a reader has to go and find" is what an SSRF finding looks like from outside —
        // and it is what CodeQL reported. An invitation token is opaque and URL-safe by
        // construction, so anything else is a malformed request, not a request to forward.
        require(TOKEN.matches(token)) { "invitation token has an unexpected shape" }
        return upstream.post(
            "$kybServiceUrl$UPSTREAM/invitations/${enc(token)}/claim",
            me.toString(),
            """{"partyId":"$me"}""",
            null,
        )
    }

    private fun human(): UUID {
        val claim = CustomerEdgeResource.resolvePartyIdClaim(jwt.getClaim<String>("party_id"), jwt.subject)
            ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val claimed = runCatching {
            UUID.fromString(claim)
        }.getOrElse { throw ForbiddenException("party_id claim is not a UUID") }
        return partyMergeResolver.resolve(claimed)
    }

    private fun parse(body: String): ObjectNode? = runCatching {
        objectMapper.readTree(body) as? ObjectNode
    }.getOrNull()

    private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8)

    private fun forbidden(message: String) =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to message)).build()

    private fun badRequest(message: String) = Response.status(Response.Status.BAD_REQUEST).entity(
        mapOf(
            "error" to message,
        ),
    ).build()

    private companion object {
        const val UPSTREAM = "/api/v1/kyb"

        /** An invitation token is opaque, URL-safe and bounded; anything else is malformed. */
        val TOKEN = Regex("^[A-Za-z0-9_-]{8,128}$")
        val COUNTRY = Regex("^[A-Za-z]{2}$")
    }
}
