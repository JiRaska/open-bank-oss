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
import jakarta.ws.rs.DELETE
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
import java.util.UUID

/**
 * Customer-facing delegated-access routes (ADR-0232 D6), proxying `openbank-delegation-service`
 * with the edge M2M token. Without this class the service is merged but unreachable: the app
 * authenticates against the `openbank-customers` realm, which upstream services do not accept.
 *
 * **The party identity is taken from the token on EVERY route and never from the client.** That is
 * the whole security property of this file, and it is why the routes do not simply mirror the
 * upstream paths: upstream exposes `/grantor/{partyId}` and takes `granteePartyId` as a query
 * parameter, both of which a customer client must not be able to choose. Here the caller says
 * *which side of the relationship they are asking about* ("shared by me" / "shared with me") and
 * the edge supplies *who they are*.
 *
 * delegation-service independently re-checks the same thing via `X-Customer-Party-Id`
 * (`requireCallerIs`, added in #3164) — this is the outer half of that pair, not a replacement for
 * it. The upstream check is what makes a direct call safe; this one is what makes the customer
 * realm work at all.
 *
 * A separate resource from [CustomerEdgeResource] to keep that (already `@Suppress("LargeClass")`)
 * class from growing further, matching [CustomerDocumentResource]; party resolution reuses its
 * `resolvePartyIdClaim` companion helper.
 */
@Path("/customer/v1/delegations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerDelegationResource(private val upstream: UpstreamClient) {

    @Inject
    lateinit var jwt: JsonWebToken

    @ConfigProperty(
        name = "openbank.edge.delegation-service-url",
        defaultValue = "http://delegation-service.delegation.svc:8126",
    )
    lateinit var delegationServiceUrl: String

    private val json = ObjectMapper()

    /**
     * Grants the caller has ISSUED ("Sdílím"). The upstream path is party-scoped and the party comes
     * from the token, so this can only ever list the caller's own grants.
     */
    @GET
    @Path("/shared-by-me")
    @Blocking
    fun sharedByMe(): Response {
        val partyId = partyId()
        return upstream.get("$delegationServiceUrl$UPSTREAM/grantor/$partyId", partyId)
    }

    /** Grants the caller has RECEIVED ("Sdíleno se mnou"), including offers awaiting a response. */
    @GET
    @Path("/shared-with-me")
    @Blocking
    fun sharedWithMe(): Response {
        val partyId = partyId()
        return upstream.get("$delegationServiceUrl$UPSTREAM/grantee/$partyId", partyId)
    }

    /**
     * One grant the caller is a party to. Upstream answers 404 when the caller is neither grantor
     * nor grantee, so a guessed id yields no existence oracle and the edge adds no check of its own.
     */
    @GET
    @Path("/{id}")
    @Blocking
    fun getById(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        return upstream.get("$delegationServiceUrl$UPSTREAM/$id", partyId)
    }

    /**
     * Offer a grant over one of the caller's own resources (SCA-bound, purpose `DELEGATION_GRANT`).
     *
     * `grantorPartyId` is FORCED to the token's party. A body naming a different grantor is
     * rejected with 403 rather than silently rewritten: a client that sends the wrong id has a bug,
     * and quietly issuing a grant the user did not ask for is the worse of the two outcomes. An
     * absent field is filled in, since the app has no reason to send it at all.
     *
     * `dailyLimit`/`monthlyLimit` are the ONE exception to pass-through, and they are rejected here
     * rather than only upstream. Nothing in this platform counts cumulative spend against a grant
     * (`DelegationOffered` does not even carry the two fields), so a ceiling set through this route
     * would be stored, echoed back, and never applied to a single payment — the grantor would be
     * told they capped their delegate at "5 000 Kč/den" by an API that cannot do it. delegation-
     * service refuses them too and is the binding gate; this copy exists so the customer channel
     * fails on its own terms and the refusal is visible in the edge contract the app reads, not
     * only in an upstream 400 the app would surface as a generic error.
     *
     * Everything else — grantee, resource, capabilities, perTransactionLimit, SCA session — is
     * passed through untouched; delegation-service owns that validation and verifies resource
     * ownership itself.
     */
    @POST
    @Blocking
    fun offer(body: String?): Response {
        val partyId = partyId()
        val node = runCatching { json.readTree(body ?: "{}") as? ObjectNode }.getOrNull()
            ?: return refuse(Response.Status.BAD_REQUEST, "Body must be a JSON object")
        val unenforced = UNENFORCED_CEILING_FIELDS.filter { !node.get(it).let { v -> v == null || v.isNull } }
        if (unenforced.isNotEmpty()) {
            return refuse(
                Response.Status.BAD_REQUEST,
                "${unenforced.joinToString(" and ")} cannot be accepted: this platform enforces only " +
                    "perTransactionLimit. No service counts cumulative spend against a grant, so a ceiling " +
                    "set here would never be applied to any payment. Omit the field (ADR-0232 D1/D6).",
                CODE_CUMULATIVE_LIMIT_UNSUPPORTED,
            )
        }
        val declared = node.get(FIELD_GRANTOR)?.asText()?.takeIf { it.isNotBlank() }
        if (declared != null && declared != partyId) {
            return refuse(Response.Status.FORBIDDEN, "grantorPartyId must be the authenticated party")
        }
        node.put(FIELD_GRANTOR, partyId)
        return upstream.post("$delegationServiceUrl$UPSTREAM", partyId, json.writeValueAsString(node))
    }

    /**
     * Accept an offered grant. `granteePartyId` is the token's party, so the caller can only ever
     * accept an offer addressed to themselves; `scaSessionId` is the grantee's OWN challenge
     * (purpose `DELEGATION_ACCEPT`) and is the one thing the client supplies.
     */
    @POST
    @Path("/{id}/accept")
    @Blocking
    fun accept(@PathParam("id") id: UUID, @QueryParam("scaSessionId") scaSessionId: UUID?): Response {
        val partyId = partyId()
        if (scaSessionId == null) return refuse(Response.Status.BAD_REQUEST, "scaSessionId is required")
        return upstream.post(
            "$delegationServiceUrl$UPSTREAM/$id/accept?granteePartyId=$partyId&scaSessionId=$scaSessionId",
            partyId,
            "",
        )
    }

    /** Decline an offered grant. No SCA: refusing access never needs a step-up. */
    @POST
    @Path("/{id}/decline")
    @Blocking
    fun decline(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        return upstream.post("$delegationServiceUrl$UPSTREAM/$id/decline?granteePartyId=$partyId", partyId, "")
    }

    /** Give back an active grant the caller holds. Same reasoning as decline — no SCA to drop access. */
    @POST
    @Path("/{id}/renounce")
    @Blocking
    fun renounce(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        return upstream.post("$delegationServiceUrl$UPSTREAM/$id/renounce?granteePartyId=$partyId", partyId, "")
    }

    /**
     * Revoke a grant the caller issued — unilateral and instant, which is the property the UI
     * promises. `revokedBy` is NOT sent: upstream derives the actor from `X-Customer-Party-Id` for
     * customer-scoped calls, and the query parameter it still accepts is the bank-initiated path,
     * role-gated and narrowed by `delegation_rest_ext.rego`. Passing it from here would re-open the
     * hole #3164 closed, where the actor was caller-supplied.
     */
    @DELETE
    @Path("/{id}")
    @Blocking
    fun revoke(@PathParam("id") id: UUID, @QueryParam("reason") reason: String?): Response {
        val partyId = partyId()
        val body = json.writeValueAsString(mapOf("reason" to (reason?.takeIf { it.isNotBlank() } ?: DEFAULT_REASON)))
        return upstream.delete("$delegationServiceUrl$UPSTREAM/$id", partyId, body)
    }

    private fun partyId(): String = CustomerEdgeResource.resolvePartyIdClaim(
        partyIdClaim = jwt.getClaim<String>("party_id"),
        sub = jwt.subject,
    ) ?: throw ForbiddenException("Missing party_id/sub claim in customer token")

    // One helper rather than a forbidden()/badRequest() pair: detekt's TooManyFunctions fires AT
    // the threshold (11), not above it, and a second one-line wrapper is the cheapest thing to give up.
    private fun refuse(status: Response.Status, message: String, code: String? = null): Response =
        Response.status(status)
            .entity(mapOf("error" to message) + (code?.let { mapOf("code" to it) } ?: emptyMap()))
            .build()

    private companion object {
        const val UPSTREAM = "/api/v1/delegations"
        const val FIELD_GRANTOR = "grantorPartyId"
        const val DEFAULT_REASON = "Revoked by grantor"
        const val CODE_CUMULATIVE_LIMIT_UNSUPPORTED = "CUMULATIVE_LIMIT_UNSUPPORTED"

        /** Constraints the schema still names but no service enforces. See [offer]. */
        val UNENFORCED_CEILING_FIELDS = listOf("dailyLimit", "monthlyLimit")
    }
}
