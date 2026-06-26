// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.crypto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.pid.application.port.out.PidPresentationVerifierPort
import com.openbank.pid.application.port.out.PidVerificationException
import com.openbank.pid.domain.model.PidClaims
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.base64url.Base64Url
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Verifies an EUDI wallet presentation (SD-JWT VC) and returns the verified PID attributes (ADR-0094).
 *
 * SD-JWT VC envelope: `<issuer-JWS>~<disclosure1>~...~<disclosureN>~<optional KB-JWT>`. Verification,
 * fail-closed at every step BEFORE any claim is trusted:
 *  1. issuer JWS signature against a configured trusted key (algorithm allow-list pinned: ES256/EdDSA);
 *  2. issuer (`iss`) is in the trust store;
 *  3. temporal validity (`exp`, `nbf`) with bounded skew;
 *  4. disclosure-hash binding: `_sd_alg` (when declared) must be `sha-256`, and each used disclosure's
 *     base64url-string SHA-256 must appear in `_sd`;
 *  5. holder key-binding (KB-JWT against `cnf.jwk`, `nonce`/`aud`) when the caller supplies a nonce/aud.
 *
 * Trust store is config-driven JSON: `openbank.pid.eudi.trusted-issuers-json` =
 * `[{"iss":"<issuer>","jwks":{"keys":[...]}}]`. Empty (default `[]`) ⇒ every verification fails closed.
 */
@ApplicationScoped
@Suppress(
    // A fail-closed crypto verifier is inherently guard-heavy: each check is a distinct throw with a
    // safe, specific message; JOSE parse failures are intentionally reduced to PidVerificationException
    // (the underlying cause is never surfaced to the caller to avoid leaking crypto internals).
    "ThrowsCount",
    "SwallowedException",
    "LoopWithTooManyJumpStatements",
)
class EudiPresentationVerifierImpl(
    @ConfigProperty(name = "openbank.pid.eudi.trusted-issuers-json", defaultValue = "[]")
    trustedIssuersJson: String,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : PidPresentationVerifierPort,
    RefreshableTrustStore,
    TrustedIssuerKeys {

    // The static (config) trust layer — a permanent base that is never wiped by a Trusted List refresh.
    private val staticTrustStore: Map<String, JsonWebKeySet> = parseTrustStore(trustedIssuersJson)

    // iss -> trusted JWKS. Static base merged with the dynamic eIDAS Trusted List layer, replaced
    // atomically on refresh (@Volatile — read on the verify hot path, written by the refresher thread).
    @Volatile
    private var trustStore: Map<String, JsonWebKeySet> = staticTrustStore

    override fun replaceDynamicTrust(trustedIssuersJson: String) {
        // Dynamic entries override static ones for the same iss; static entries with no dynamic
        // counterpart are preserved (local overrides / sandbox allow-list survive an empty list).
        trustStore = staticTrustStore + parseTrustStore(trustedIssuersJson)
    }

    override fun allTrustedKeys(): List<java.security.PublicKey> = trustStore.values.flatMap { keySet ->
        keySet.jsonWebKeys.mapNotNull { (it as? PublicJsonWebKey)?.publicKey }
    }

    private val sigAlgConstraints = AlgorithmConstraints(
        ConstraintType.PERMIT,
        AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256,
        AlgorithmIdentifiers.EDDSA,
    )

    override fun verify(vpToken: String, nonce: String?, audience: String?): PidClaims {
        val parts = vpToken.split(TILDE)
        if (parts.isEmpty() || parts[0].isBlank()) {
            throw PidVerificationException("malformed SD-JWT VC: empty issuer JWS")
        }
        // A trailing '~' means no key-binding JWT; otherwise the last element is the KB-JWT.
        val hasKb = !vpToken.endsWith(TILDE)
        val issuerJws = parts[0]
        val disclosures: List<String>
        val kbJwt: String?
        if (hasKb && parts.size >= 2) {
            disclosures = parts.subList(1, parts.size - 1).filter { it.isNotEmpty() }
            kbJwt = parts.last().takeIf { it.isNotEmpty() }
        } else {
            disclosures = parts.drop(1).filter { it.isNotEmpty() }
            kbJwt = null
        }

        val payload = verifyIssuerSignature(issuerJws)
        verifyTemporal(payload)
        val disclosed = bindDisclosures(payload, disclosures)
        verifyHolderBinding(payload, kbJwt, nonce, audience)
        return extractPidClaims(payload, disclosed)
    }

    // ── (1)+(2) issuer signature + trust ───────────────────────────────────────────

    private fun verifyIssuerSignature(issuerJws: String): JsonNode {
        val jws = JsonWebSignature()
        try {
            jws.compactSerialization = issuerJws
        } catch (e: org.jose4j.lang.JoseException) {
            throw PidVerificationException("issuer JWS not parseable: ${e.message}")
        }
        // Peek the UNVERIFIED payload only to select the trusted key by issuer; never trusted yet.
        val unverified = runCatching { objectMapper.readTree(jws.unverifiedPayload) }.getOrNull()
            ?: throw PidVerificationException("issuer JWS payload not JSON")
        val iss = unverified["iss"]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw PidVerificationException("issuer JWS missing 'iss'")
        val jwks = trustStore[iss]
            ?: throw PidVerificationException("untrusted issuer: $iss")

        val kid = jws.keyIdHeaderValue
        val jwk = (if (kid != null) jwks.findJsonWebKey(kid, null, null, null) else jwks.jsonWebKeys.firstOrNull())
            ?: throw PidVerificationException("no trusted key for issuer $iss (kid=$kid)")
        jws.key = (jwk as? PublicJsonWebKey)?.publicKey
            ?: throw PidVerificationException("trusted key is not a public JWK for issuer $iss")
        jws.setAlgorithmConstraints(sigAlgConstraints)

        val ok = try {
            jws.verifySignature()
        } catch (e: org.jose4j.lang.JoseException) {
            throw PidVerificationException("issuer signature verification error: ${e.message}")
        }
        if (!ok) throw PidVerificationException("issuer signature invalid")
        // Only NOW read the verified payload.
        return objectMapper.readTree(jws.payload)
    }

    // ── (3) temporal ────────────────────────────────────────────────────────────────

    private fun verifyTemporal(payload: JsonNode) {
        val now = Instant.now(clock).epochSecond
        payload["exp"]?.takeIf { it.isNumber }?.asLong()?.let {
            if (now > it + CLOCK_SKEW_SECONDS) throw PidVerificationException("PID credential expired")
        }
        payload["nbf"]?.takeIf { it.isNumber }?.asLong()?.let {
            if (now + CLOCK_SKEW_SECONDS < it) throw PidVerificationException("PID credential not yet valid")
        }
    }

    // ── (4) disclosure-hash binding ──────────────────────────────────────────────────

    private fun bindDisclosures(payload: JsonNode, disclosures: List<String>): Map<String, JsonNode> {
        // The credential MUST hash its disclosures with the algorithm we recompute below. SD-JWT lets
        // the issuer declare it via `_sd_alg` (defaulting to "sha-256" when absent). We only implement
        // SHA-256: a credential declaring anything else would silently fail disclosure binding (every
        // hash mismatches → all claims dropped → 422), so reject it explicitly for a clear diagnostic
        // and forward-compat with future eIDAS ARF revisions that may mandate a different digest.
        payload["_sd_alg"]?.takeIf { it.isTextual }?.asText()?.let { declared ->
            if (declared != SD_ALG_SHA_256) {
                throw PidVerificationException("unsupported _sd_alg '$declared' (only '$SD_ALG_SHA_256' is supported)")
            }
        }
        val sdHashes = (payload["_sd"]?.takeIf { it.isArray }?.map { it.asText() } ?: emptyList()).toSet()
        val sha = MessageDigest.getInstance("SHA-256")
        val claims = HashMap<String, JsonNode>()
        for (d in disclosures) {
            val hash = Base64Url.encode(sha.digest(d.toByteArray(Charsets.US_ASCII)))
            if (hash !in sdHashes) {
                // Unbound disclosure — possibly forged/added. Never trust it.
                Log.warn("EUDI: dropping disclosure not present in _sd (possible forgery)")
                continue
            }
            val arr = runCatching { objectMapper.readTree(Base64Url.decode(d)) }.getOrNull()
            if (arr == null || !arr.isArray || arr.size() < THREE) continue // [salt, claimName, claimValue]
            val name = arr[1]?.asText() ?: continue
            claims[name] = arr[2]
        }
        return claims
    }

    // ── (5) holder key-binding (anti-replay) ─────────────────────────────────────────

    private fun verifyHolderBinding(payload: JsonNode, kbJwt: String?, nonce: String?, audience: String?) {
        // Only enforce when the caller supplied a nonce/audience to bind to (the redirect flow).
        if (nonce == null && audience == null) return
        val cnfJwkNode = payload["cnf"]?.get("jwk")
            ?: throw PidVerificationException("nonce/audience supplied but PID has no holder cnf key")
        if (kbJwt == null) throw PidVerificationException("nonce/audience supplied but no key-binding JWT")
        val cnfJwk = runCatching { PublicJsonWebKey.Factory.newPublicJwk(objectMapper.writeValueAsString(cnfJwkNode)) }
            .getOrElse { throw PidVerificationException("invalid holder cnf jwk: ${it.message}") }
        val jws = JsonWebSignature()
        runCatching { jws.compactSerialization = kbJwt }
            .onFailure { throw PidVerificationException("key-binding JWT not parseable") }
        jws.key = cnfJwk.publicKey
        jws.setAlgorithmConstraints(sigAlgConstraints)
        if (!runCatching { jws.verifySignature() }.getOrDefault(false)) {
            throw PidVerificationException("key-binding signature invalid")
        }
        val kb = objectMapper.readTree(jws.payload)
        if (nonce != null &&
            kb["nonce"]?.asText() != nonce
        ) {
            throw PidVerificationException("key-binding nonce mismatch")
        }
        if (audience != null &&
            kb["aud"]?.asText() != audience
        ) {
            throw PidVerificationException("key-binding aud mismatch")
        }
    }

    // ── (6) extract verified PID claims ──────────────────────────────────────────────

    private fun extractPidClaims(payload: JsonNode, disclosed: Map<String, JsonNode>): PidClaims {
        fun claim(vararg names: String): JsonNode? =
            names.firstNotNullOfOrNull { disclosed[it] ?: payload[it]?.takeIf { n -> !n.isNull } }

        val subjectRaw = payload["sub"]?.asText()?.takeIf { it.isNotBlank() }?.let { "sub:$it" }
            ?: claim("personal_administrative_number")?.asText()?.takeIf { it.isNotBlank() }?.let { "pan:$it" }
            ?: throw PidVerificationException("PID has no subject identifier (sub / personal_administrative_number)")

        val given = claim("given_name", "givenName")?.asText()
            ?: throw PidVerificationException("PID missing given_name")
        val family = claim("family_name", "familyName")?.asText()
            ?: throw PidVerificationException("PID missing family_name")
        val birth = claim("birthdate", "birth_date", "birthDate")?.asText()
            ?: throw PidVerificationException("PID missing birthdate")
        val birthDate = runCatching { LocalDate.parse(birth) }
            .getOrElse { throw PidVerificationException("PID birthdate not ISO-8601: $birth") }

        return PidClaims(
            subjectId = subjectRaw,
            givenName = given,
            familyName = family,
            birthDate = birthDate,
            birthPlace = claim("birth_place", "place_of_birth", "birthPlace")?.asText(),
            nationalities = claim("nationalities")?.takeIf { it.isArray }?.mapNotNull { it.asText() } ?: emptyList(),
            issuingCountry = claim("issuing_country", "issuingCountry")?.asText() ?: "",
            nationalIdentifier = claim("national_identifier", "birth_number")?.asText(),
            issuer = payload["iss"].asText(),
            levelOfAssurance = "HIGH",
            statusListUri = payload["status"]?.get("status_list")?.get("uri")?.asText()?.takeIf { it.isNotBlank() },
            statusListIndex = payload["status"]?.get("status_list")?.get("idx")?.takeIf { it.isNumber }?.asLong(),
        )
    }

    private fun parseTrustStore(json: String): Map<String, JsonWebKeySet> = runCatching {
        val root = objectMapper.readTree(json)
        if (!root.isArray) return emptyMap()
        root.mapNotNull { node ->
            val iss = node["iss"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val jwks = node["jwks"] ?: return@mapNotNull null
            iss to JsonWebKeySet(objectMapper.writeValueAsString(jwks))
        }.toMap()
    }.getOrElse {
        Log.error(
            "EUDI: failed to parse trusted-issuers-json; trust store EMPTY (all verifications fail): ${it.message}",
        )
        emptyMap()
    }.also {
        Log.infof("EUDI presentation verifier: %d trusted issuer(s) configured", it.size)
    }

    companion object {
        private const val TILDE = "~"
        private const val CLOCK_SKEW_SECONDS = 120L
        private const val THREE = 3
        private const val SD_ALG_SHA_256 = "sha-256"
    }
}
