// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.OpenBaoTokenSource
import com.openbank.libs.security.OpenBaoTransport
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.AlgorithmParameters
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Runtime inputs contain public keys and version pins. The four private keys stay in Transit. */
internal class HostedBankTransitProof(
    private val transport: OpenBaoTransport,
    private val tokens: OpenBaoTokenSource,
    private val mapper: ObjectMapper,
    keys: Map<Vendor, VendorKeys>,
    private val clock: Clock = Clock.systemUTC(),
) : HostedBankParProof {
    class Key(val version: Int, val publicKey: ECPublicKey) {
        init {
            require(version > 0)
            val params = publicKey.params
            require(
                params.curve == P256.curve &&
                    params.generator == P256.generator &&
                    params.order == P256.order &&
                    params.cofactor == P256.cofactor,
            )
        }

        val jwk: Map<String, String> = sortedMapOf(
            "crv" to "P-256",
            "kty" to "EC",
            "x" to coordinate(publicKey.w.affineX),
            "y" to coordinate(publicKey.w.affineY),
        )
    }

    class VendorKeys(val assertion: Key, val dpop: Key)

    private val keys = keys.toMap()

    init {
        require(transport.httpClient.followRedirects() == HttpClient.Redirect.NEVER)
        require(keys.keys == Vendor.entries.toSet())
        require(keys.values.flatMap { listOf(it.assertion.jwk, it.dpop.jwk) }.toSet().size == keys.size * 2) {
            "Bank signing keys must be distinct by vendor and purpose"
        }
    }

    override suspend fun sign(vendor: Vendor, issuer: URI, parEndpoint: URI): HostedBankParProof.Signed {
        require(issuer == ISSUER && parEndpoint == PAR)
        return signRequest(vendor, PAR, null)
    }

    suspend fun signToken(vendor: Vendor, nonce: String? = null): HostedBankParProof.Signed {
        require(nonce == null || NONCE.matches(nonce))
        return signRequest(vendor, TOKEN, nonce)
    }

    private suspend fun signRequest(vendor: Vendor, endpoint: URI, nonce: String?): HostedBankParProof.Signed {
        val key = keys.getValue(vendor)
        val sampledAt = clock.instant().epochSecond
        val assertion = jwt(
            "${vendor.bankClientId}-assertion",
            key.assertion,
            mapOf("alg" to "ES256", "typ" to "JWT", "kid" to "${vendor.bankClientId}-v${key.assertion.version}"),
            mapOf(
                "iss" to vendor.bankClientId,
                "sub" to vendor.bankClientId,
                "aud" to ISSUER.toString(),
                "iat" to sampledAt,
                "exp" to sampledAt + ASSERTION_LIFETIME,
                "jti" to UUID.randomUUID().toString(),
            ),
        )
        val proof = signDpop(vendor, endpoint, nonce, null)
        return HostedBankParProof.Signed(assertion, proof, thumbprint(vendor))
    }

    suspend fun signResource(vendor: Vendor, accessToken: String, nonce: String?): String {
        require(nonce == null || NONCE.matches(nonce))
        val hash = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(US_ASCII))
        return signDpop(vendor, RESOURCE, nonce, encode(hash))
    }

    private suspend fun signDpop(vendor: Vendor, endpoint: URI, nonce: String?, accessHash: String?): String {
        val key = keys.getValue(vendor).dpop
        return jwt(
            "${vendor.bankClientId}-dpop",
            key,
            mapOf("alg" to "ES256", "typ" to "dpop+jwt", "jwk" to key.jwk),
            buildMap {
                put("htm", "POST")
                put("htu", endpoint.toString())
                put("iat", clock.instant().epochSecond)
                put("jti", UUID.randomUUID().toString())
                if (nonce != null) put("nonce", nonce)
                if (accessHash != null) put("ath", accessHash)
            },
        )
    }

    fun thumbprint(vendor: Vendor): String {
        val key = keys.getValue(vendor)
        val canonicalJwk = key.dpop.jwk.entries.joinToString(",", "{", "}") { (field, value) ->
            "\"$field\":\"$value\""
        }
        val thumbprint = MessageDigest.getInstance("SHA-256").digest(canonicalJwk.toByteArray(US_ASCII))
        return encode(thumbprint)
    }

    private suspend fun jwt(name: String, key: Key, header: Map<String, Any>, claims: Map<String, Any>): String {
        val input = "${encode(mapper.writeValueAsBytes(header))}.${encode(mapper.writeValueAsBytes(claims))}"
        val bytes = input.toByteArray(US_ASCII)
        val body = mapper.writeValueAsString(
            mapOf(
                "input" to Base64.getEncoder().encodeToString(bytes),
                "key_version" to key.version,
                "hash_algorithm" to "sha2-256",
                "marshaling_algorithm" to "jws",
            ),
        )
        var token = withContext(Dispatchers.IO) { tokens.token() }
        var response = transit(name, body, token)
        if (response.statusCode() == HTTP_FORBIDDEN) {
            token = withContext(Dispatchers.IO) {
                tokens.invalidate(token)
                tokens.token()
            }
            response = transit(name, body, token)
        }
        check(response.statusCode() == HTTP_OK) { "Bank Transit signature unavailable" }
        val wire = try {
            mapper.readTree(response.body())?.path("data")?.path("signature")?.textValue()
        } catch (_: JacksonException) {
            error("Malformed bank Transit signature")
        }
        val prefix = "vault:v${key.version}:"
        check(wire != null && wire.startsWith(prefix)) { "Unexpected bank Transit signature version" }
        val signature = try {
            Base64.getUrlDecoder().decode(wire.removePrefix(prefix))
        } catch (_: IllegalArgumentException) {
            error("Malformed bank Transit signature")
        }
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(key.publicKey)
        verifier.update(bytes)
        check(signature.size == ES256_BYTES && verifier.verify(signature)) {
            "Bank Transit signature did not match its public pin"
        }
        return "$input.${encode(signature)}"
    }

    private suspend fun transit(name: String, body: String, token: String): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(transport.uri("v1/transit/sign/$name"))
            .timeout(transport.requestTimeout)
            .header("Content-Type", "application/json")
            .header("X-Vault-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val handler = HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BODY_BYTES)
        return withTimeout(transport.requestTimeout.toMillis()) {
            transport.httpClient.sendAsync(request, handler).await()
        }
    }

    private companion object {
        val ISSUER = URI.create("https://kc.open-bank.tech/realms/openbank-customers")
        val PAR = URI.create("$ISSUER/protocol/openid-connect/ext/par/request")
        val RESOURCE = URI.create(HostedOAuthAdmission.RESOURCE)
        val TOKEN = URI.create("$ISSUER/protocol/openid-connect/token")
        val NONCE = Regex("[!-~]{1,1024}")
        val P256 = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        const val ASSERTION_LIFETIME = 60L
        const val MAX_BODY_BYTES = 4_096L
        const val ES256_BYTES = 64
        const val COORDINATE_BYTES = 32
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403

        fun coordinate(value: BigInteger): String {
            require(value.signum() >= 0 && value.bitLength() <= COORDINATE_BYTES * Byte.SIZE_BITS)
            val canonical = value.toByteArray().takeLast(COORDINATE_BYTES).toByteArray()
            return encode(ByteArray(COORDINATE_BYTES - canonical.size) + canonical)
        }

        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
