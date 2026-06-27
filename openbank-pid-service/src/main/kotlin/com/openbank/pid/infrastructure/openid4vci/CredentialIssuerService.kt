// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vci

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.application.port.out.PidVerificationException
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.base64url.Base64Url
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

/**
 * Issues PID credentials into an EUDI wallet (eIDAS 2.0 OpenID4VCI, ADR-0094) — the bank as a
 * (Q)EAA / PID issuer, the inverse of the relying-party flow.
 *
 * Mints an **SD-JWT VC**: an issuer-signed JWS (`iss`, `vct`, `sub`, `iat`, `exp`, `_sd` digest array)
 * followed by the selectively-disclosable disclosures (given_name, family_name, birthdate). The
 * credential is **holder-bound** ([holderJwk] → `cnf.jwk`, so only that wallet can present it) and
 * carries a `status.status_list` claim so the bank can later **revoke** it ([StatusListService]).
 *
 * Fail-closed: signing is delegated to the shared [EudiIssuerKey]; with no key configured [enabled]
 * is false and minting throws — a bank must never issue an unsigned/forgeable credential.
 */
@ApplicationScoped
class CredentialIssuerService(
    private val issuerKey: EudiIssuerKey,
    private val statusList: StatusListService,
    @ConfigProperty(name = "openbank.pid.eudi.issuer.credential-ttl-seconds", defaultValue = "31536000")
    private val credentialTtlSeconds: Long,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val secureRandom = SecureRandom()

    val issuerId: String get() = issuerKey.issuerId

    val enabled: Boolean get() = issuerKey.enabled

    /** True once a wallet proof JWT has proven possession of [holderJwk]; mint a holder-bound PID VC. */
    suspend fun issuePidCredential(claims: OfferedClaims, holderJwk: PublicJsonWebKey): String {
        if (!issuerKey.enabled) throw PidVerificationException("credential issuance disabled: no issuer signing key")
        val disclosures = buildList {
            add(disclosure("given_name", claims.givenName))
            add(disclosure("family_name", claims.familyName))
            add(disclosure("birthdate", claims.birthdate))
        }
        val sha = MessageDigest.getInstance("SHA-256")
        val sd = disclosures.map { Base64Url.encode(sha.digest(it.toByteArray(Charsets.US_ASCII))) }
        val now = Instant.now(clock).epochSecond
        val statusIndex = statusList.allocate()
        val payload = objectMapper.createObjectNode().apply {
            put("iss", issuerKey.issuerId)
            put("vct", PID_VCT)
            put("sub", claims.subjectId)
            put("iat", now)
            put("exp", now + credentialTtlSeconds)
            put("issuing_country", claims.issuingCountry)
            put("_sd_alg", "sha-256")
            set<com.fasterxml.jackson.databind.JsonNode>(
                "cnf",
                objectMapper.createObjectNode().set(
                    "jwk",
                    objectMapper.readTree(holderJwk.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)),
                ),
            )
            set<com.fasterxml.jackson.databind.JsonNode>(
                "status",
                objectMapper.createObjectNode().set(
                    "status_list",
                    objectMapper.createObjectNode()
                        .put("idx", statusIndex)
                        .put("uri", statusList.statusListUri),
                ),
            )
            set<com.fasterxml.jackson.databind.JsonNode>(
                "_sd",
                objectMapper.createArrayNode().apply { sd.forEach { add(it) } },
            )
        }
        return issuerKey.sign(objectMapper.writeValueAsString(payload)) + "~" + disclosures.joinToString("~") + "~"
    }

    /** Issuer metadata public JWKS (the verifier/wallet trust anchor) — empty when issuance is disabled. */
    fun publicJwksJson(): String = issuerKey.publicJwksJson()

    private fun disclosure(name: String, value: String): String {
        // SD-JWT requires a cryptographically-random salt (≥128 bits) — never a predictable clock value,
        // which would let an attacker correlate a disclosure hash to its claim name.
        val salt = Base64Url.encode(ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) })
        val arr = objectMapper.createArrayNode().apply {
            add(salt)
            add(name)
            add(value)
        }
        return Base64Url.encode(objectMapper.writeValueAsString(arr).toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val PID_VCT = "eu.europa.ec.eudi.pid.1"
        const val SALT_BYTES = 16
    }
}

/** The verified identity attributes a credential offer will attest into the wallet. */
data class OfferedClaims(
    val subjectId: String,
    val givenName: String,
    val familyName: String,
    val birthdate: String,
    val issuingCountry: String = "CZ",
)
