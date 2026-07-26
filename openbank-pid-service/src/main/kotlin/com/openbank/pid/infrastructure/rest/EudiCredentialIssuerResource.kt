// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.pid.infrastructure.openid4vci.CredentialIssuerService
import com.openbank.pid.infrastructure.openid4vci.CredentialOfferStore
import com.openbank.pid.infrastructure.openid4vci.OfferedClaims
import com.openbank.pid.infrastructure.openid4vci.StatusListService
import io.quarkus.logging.Log
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

/**
 * OpenID4VCI credential issuer — the bank issues a PID (Q)EAA into an EUDI wallet (eIDAS 2.0,
 * ADR-0094, pre-authorized-code flow). Inverse of the relying-party flow.
 *
 * Public endpoints (token, credential) are anonymous by necessity — the wallet holds no client
 * credentials; they live in this class WITHOUT a class-level `@RolesAllowed` so the method-level
 * `@PermitAll` is honoured (the M2M offer endpoint carries its own method `@RolesAllowed`). Issuance is
 * fail-closed: with no issuer signing key the offer/credential endpoints return 503.
 *
 * The class @Path is the shared `/api/v1/parties/eudi` prefix (Quarkus merges the methods of all
 * resource classes at that prefix) — a more specific sibling class would otherwise win JAX-RS
 * root-resource matching and 404 these paths. The issuer metadata lives at a root `/.well-known/...`
 * path in [EudiIssuerMetadataResource].
 */
@Path("/api/v1/parties/eudi")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "EUDI identity", description = "eIDAS 2.0 wallet credential issuance (ADR-0094 OpenID4VCI)")
class EudiCredentialIssuerResource(
    private val issuer: CredentialIssuerService,
    private val offerStore: CredentialOfferStore,
    private val statusList: StatusListService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val secureRandom = SecureRandom()
    private val proofAlgConstraints = AlgorithmConstraints(
        ConstraintType.PERMIT,
        AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256,
        AlgorithmIdentifiers.EDDSA,
    )

    @POST
    @Path("credential-offers")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.eudi.issue")
    @Operation(summary = "Create an OpenID4VCI credential offer for a verified identity (ADR-0094)")
    suspend fun createOffer(body: String): Response {
        if (!issuer.enabled) return serviceUnavailable()
        val node = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return badRequest("invalid JSON")
        fun field(name: String) = node[name]?.asText()?.takeIf { it.isNotBlank() }
        val claims = OfferedClaims(
            subjectId = field("subjectId") ?: return badRequest("subjectId required"),
            givenName = field("givenName") ?: return badRequest("givenName required"),
            familyName = field("familyName") ?: return badRequest("familyName required"),
            birthdate = field("birthdate") ?: return badRequest("birthdate required"),
            issuingCountry = field("issuingCountry") ?: "CZ",
        )
        val preAuthCode = randomToken()
        offerStore.create(preAuthCode, claims, Instant.now(clock))
        val offer = objectMapper.createObjectNode().apply {
            put("credential_issuer", issuer.issuerId)
            set<com.fasterxml.jackson.databind.JsonNode>(
                "credential_configuration_ids",
                objectMapper.createArrayNode().add(PID_CONFIG_ID),
            )
            set<com.fasterxml.jackson.databind.JsonNode>(
                "grants",
                objectMapper.createObjectNode().set(
                    "urn:ietf:params:oauth:grant-type:pre-authorized_code",
                    objectMapper.createObjectNode().put("pre-authorized_code", preAuthCode),
                ),
            )
        }
        val offerUri = "openid-credential-offer://?credential_offer=" +
            URLEncoder.encode(objectMapper.writeValueAsString(offer), StandardCharsets.UTF_8)
        val resp = objectMapper.createObjectNode().apply {
            set<com.fasterxml.jackson.databind.JsonNode>("credentialOffer", offer)
            put("credentialOfferUri", offerUri)
        }
        return Response.ok(objectMapper.writeValueAsString(resp)).build()
    }

    @POST
    @Path("token")
    @PermitAll
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "OpenID4VCI token endpoint — redeem a pre-authorized code (ADR-0094)")
    suspend fun token(
        @FormParam("grant_type") grantType: String?,
        @FormParam("pre-authorized_code") preAuthCode: String?,
    ): Response {
        if (grantType != PRE_AUTH_GRANT) return badRequest("unsupported_grant_type")
        if (preAuthCode.isNullOrBlank()) return badRequest("invalid_request")
        val accessToken = randomToken()
        val cNonce = randomToken()
        offerStore.authorize(preAuthCode, accessToken, cNonce, Instant.now(clock))
            ?: return badRequest("invalid_grant") // unknown / already-redeemed / expired
        val resp = objectMapper.createObjectNode().apply {
            put("access_token", accessToken)
            put("token_type", "bearer")
            put("expires_in", TOKEN_EXPIRES_IN)
            put("c_nonce", cNonce)
            put("c_nonce_expires_in", TOKEN_EXPIRES_IN)
        }
        return Response.ok(objectMapper.writeValueAsString(resp)).build()
    }

    @POST
    @Path("credential")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "OpenID4VCI credential endpoint — mint a holder-bound PID SD-JWT VC (ADR-0094)")
    suspend fun credential(@HeaderParam("Authorization") authorization: String?, body: String): Response {
        if (!issuer.enabled) return serviceUnavailable()
        val accessToken = authorization?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }
            ?: return badRequest("invalid_token")
        val now = Instant.now(clock)
        val offer = offerStore.findByAccessToken(accessToken, now)
            ?.takeIf { it.status == CredentialOfferStore.Status.AUTHORIZED }
            ?: return badRequest("invalid_token")
        val node = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return badRequest("invalid_credential_request")
        val proofJwt = node["proof"]?.get("jwt")?.asText()?.takeIf { it.isNotBlank() }
            ?: return badRequest("invalid_proof")
        val holderJwk = verifyProof(proofJwt, offer.cNonce)
            ?: return badRequest("invalid_proof")
        // Single-use: mint exactly once. A concurrent/duplicate request loses the CAS.
        if (!offerStore.markIssued(accessToken, now)) return badRequest("invalid_token")
        val vc = issuer.issuePidCredential(offer.claims, holderJwk)
        Log.infof("EUDI OpenID4VCI: issued PID credential for subject %s", maskSubject(offer.claims.subjectId))
        val resp = objectMapper.createObjectNode().put("credential", vc)
        return Response.ok(objectMapper.writeValueAsString(resp)).build()
    }

    @POST
    @Path("credentials/{index}/revoke")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.eudi.revoke")
    @Operation(summary = "Revoke a previously-issued credential by its status-list index (ADR-0094)")
    suspend fun revoke(@PathParam("index") index: Long): Response {
        if (!statusList.enabled) return serviceUnavailable()
        return if (statusList.revoke(index)) {
            Response.ok("""{"status":"revoked","index":$index}""").build()
        } else {
            badRequest("unknown status index")
        }
    }

    @GET
    @Path("status-lists/{id}")
    @PermitAll
    @Produces("application/statuslist+jwt")
    @Operation(summary = "Token Status List token — a relying party fetches it to check revocation (ADR-0094)")
    suspend fun statusListToken(@PathParam("id") id: String): Response {
        if (!statusList.enabled || id != statusList.id) {
            return Response.status(Response.Status.NOT_FOUND).entity("""{"error":"unknown status list"}""").build()
        }
        return Response.ok(statusList.statusListToken())
            .header("Cache-Control", "public, max-age=${statusList.cacheTtlSeconds}")
            .build()
    }

    /** Verify the wallet's proof-of-possession JWT and return the bound holder key, or null if invalid. */
    private fun verifyProof(proofJwt: String, expectedNonce: String?): PublicJsonWebKey? {
        if (expectedNonce == null) return null
        val jws = JsonWebSignature()
        if (runCatching { jws.compactSerialization = proofJwt }.isFailure) return null
        val jwkNode = jws.headers?.getObjectHeaderValue("jwk") ?: return null
        val holderJwk = runCatching {
            PublicJsonWebKey.Factory.newPublicJwk(objectMapper.writeValueAsString(jwkNode))
        }.getOrNull() ?: return null
        jws.key = holderJwk.publicKey
        jws.setAlgorithmConstraints(proofAlgConstraints)
        if (!runCatching { jws.verifySignature() }.getOrDefault(false)) return null
        val payload = runCatching { objectMapper.readTree(jws.payload) }.getOrNull() ?: return null
        if (payload["nonce"]?.asText() != expectedNonce) return null
        if (payload["aud"]?.asText() != issuer.issuerId) return null
        return holderJwk
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return org.jose4j.base64url.Base64Url.encode(bytes)
    }

    private fun maskSubject(s: String): String = if (s.length <=
        MASK_TAIL
    ) {
        "***"
    } else {
        s.takeLast(MASK_TAIL).let { "***$it" }
    }

    private fun badRequest(msg: String) =
        Response.status(Response.Status.BAD_REQUEST).entity("""{"error":"$msg"}""").build()

    private fun serviceUnavailable() =
        Response.status(Response.Status.SERVICE_UNAVAILABLE).entity("""{"error":"issuance_disabled"}""").build()

    private companion object {
        const val PID_CONFIG_ID = "eu.europa.ec.eudi.pid.1"
        const val PRE_AUTH_GRANT = "urn:ietf:params:oauth:grant-type:pre-authorized_code"
        const val TOKEN_EXPIRES_IN = 600
        const val TOKEN_BYTES = 32
        const val MASK_TAIL = 4
    }
}
