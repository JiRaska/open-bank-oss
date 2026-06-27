// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.integration

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.jose4j.base64url.Base64Url
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.keys.EllipticCurves
import org.junit.jupiter.api.Test

/**
 * EUDI credential persistence end-to-end (ADR-0094) — the go-live guard for the durable status list.
 *
 * Drives the real OpenID4VCI issue flow (offer → token → credential, with a genuine wallet
 * proof-of-possession JWT) against a Testcontainers Postgres, then revokes the issued credential and
 * re-presents it. The post-revoke rejection is read back through the `PostgresStatusListStore`, which
 * holds ZERO heap state — every `isRevoked` is a fresh DB query against the `eudi_status_list_entry`
 * row written by the revoke. That is exactly the property the in-memory store lacked: a process that
 * lost its heap (a pod restart) still sees the revocation. The literal pod-restart e2e (issue → revoke
 * → restart pod → re-present → still rejected) is run against the live sandbox as the complement; this
 * IT is the automated proxy that proves the Postgres write/read path, the V9 migration and the
 * suspend/Panache wiring all work together.
 *
 * The issuer signing key + trust anchor are generated per-JVM by [EudiIssuerKeyResource] so issuance
 * and verification share one key; `authz.enforce=false` keeps the advisory OPA path from blocking the
 * M2M calls (the role contract is covered by @TestSecurity).
 */
@QuarkusTest
@QuarkusTestResource(EudiCredentialPersistenceIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.pid.it.PostgresTestResource::class)
@QuarkusTestResource(EudiCredentialPersistenceIT.EudiIssuerKeyResource::class)
@TestSecurity(user = "eudi-it", roles = ["ROLE_OPERATOR"])
class EudiCredentialPersistenceIT {

    private val mapper = ObjectMapper()

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("party-events-out", "pid-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    /** Generate the EC P-256 issuer key per JVM; wire it as both the signing key and the trust anchor. */
    class EudiIssuerKeyResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val key = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "issuer-it" }
            val publicJwk = key.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)
            return mapOf(
                "openbank.pid.eudi.issuer.signing-key-jwk" to key.toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE),
                "openbank.pid.eudi.issuer.issuer-id" to ISSUER_ID,
                "openbank.pid.eudi.trusted-issuers-json" to """[{"iss":"$ISSUER_ID","jwks":{"keys":[$publicJwk]}}]""",
                // Advisory OPA: no sidecar in the test JVM — keep it from failing closed on @Authorize.
                "authz.enforce" to "false",
            )
        }

        override fun stop() = Unit
    }

    @Test
    fun `a revoked credential is rejected on re-presentation, read from the durable status list`() {
        val vc = issueCredential()
        val statusIndex = statusIndexOf(vc)

        // BEFORE revoke: the credential verifies and resolves (status bit clear, read from Postgres).
        verifyPresentation(vc).then().statusCode(200)

        // Revoke the issued credential by its status-list index (durable write to eudi_status_list_entry).
        given().pathParam("idx", statusIndex)
            .`when`().post("/api/v1/parties/eudi/credentials/{idx}/revoke")
            .then().statusCode(200)

        // AFTER revoke: the SAME signature is still valid, but the revocation (re-read from the DB, not
        // from any heap state) now makes verification fail closed — 422. This is the restart-durable bit.
        verifyPresentation(vc).then().statusCode(422)
    }

    /** Full OpenID4VCI issue: create offer → redeem pre-auth code → mint a holder-bound credential. */
    private fun issueCredential(): String {
        val offerResp = given().contentType(ContentType.JSON)
            .body("""{"subjectId":"CZ-IT-1","givenName":"Eva","familyName":"Durable","birthdate":"1988-08-08"}""")
            .`when`().post("/api/v1/parties/eudi/credential-offers")
            .then().statusCode(200).extract().body().asString()
        val grants = mapper.readTree(offerResp)["credentialOffer"]["grants"]
        val preAuthCode = grants[PRE_AUTH_GRANT]["pre-authorized_code"].asText()

        val tokenResp = given().contentType(ContentType.URLENC)
            .formParam("grant_type", "urn:ietf:params:oauth:grant-type:pre-authorized_code")
            .formParam("pre-authorized_code", preAuthCode)
            .`when`().post("/api/v1/parties/eudi/token")
            .then().statusCode(200).extract().body().asString()
        val accessToken = mapper.readTree(tokenResp)["access_token"].asText()
        val cNonce = mapper.readTree(tokenResp)["c_nonce"].asText()

        val proof = walletProof(cNonce)
        val credResp = given().contentType(ContentType.JSON)
            .header("Authorization", "Bearer $accessToken")
            .body("""{"proof":{"jwt":"$proof"}}""")
            .`when`().post("/api/v1/parties/eudi/credential")
            .then().statusCode(200).extract().body().asString()
        return mapper.readTree(credResp)["credential"].asText()
    }

    /** A wallet proof-of-possession JWT: an ES256 JWS over {nonce, aud} carrying the holder JWK header. */
    private fun walletProof(cNonce: String): String {
        val holderKey = EcJwkGenerator.generateJwk(EllipticCurves.P256).also { it.keyId = "holder-it" }
        val jwkHeader = mapper.readValue(
            holderKey.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY),
            Map::class.java,
        )
        return JsonWebSignature().apply {
            payload = mapper.writeValueAsString(mapOf("nonce" to cNonce, "aud" to ISSUER_ID))
            key = holderKey.privateKey
            algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
            setHeader("typ", "openid4vci-proof+jwt")
            setHeader("jwk", jwkHeader)
        }.compactSerialization
    }

    private fun verifyPresentation(vc: String) = given().contentType(ContentType.JSON)
        .body(mapper.writeValueAsString(mapOf("vpToken" to vc)))
        .`when`().post("/api/v1/parties/eudi/verify-presentation")

    /** Extract the `status.status_list.idx` the issuer embedded in the SD-JWT VC's issuer JWS. */
    private fun statusIndexOf(vc: String): Long {
        val issuerJws = vc.substringBefore("~")
        val payloadJson = String(Base64Url.decode(issuerJws.split(".")[1]), Charsets.UTF_8)
        return mapper.readTree(payloadJson)["status"]["status_list"]["idx"].asLong()
    }

    private companion object {
        const val ISSUER_ID = "https://pid-it.openbank.local"
        const val PRE_AUTH_GRANT = "urn:ietf:params:oauth:grant-type:pre-authorized_code"
    }
}
