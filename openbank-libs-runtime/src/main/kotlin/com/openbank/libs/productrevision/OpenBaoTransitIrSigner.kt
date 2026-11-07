// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.OpenBaoTokenSource
import com.openbank.libs.security.OpenBaoTransport
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * [IrSignerPort] and [IrSignatureVerifierPort] over OpenBao Transit `sign` / `verify` on a
 * dedicated asymmetric key (ADR-0352 §4; the ADR-0262 Transit pattern, lifted from
 * [com.openbank.libs.security.OpenBaoTransitFieldProtector]). Plain `java.net.http`, no Vault SDK.
 *
 * **A plain class, deliberately not a CDI bean** (ADR-0320 classpath-guard rule 1): product-catalog
 * produces one from its own `src/main`; a consumer that only verifies produces one whose token is
 * granted `verify` and nothing else.
 *
 * - The signed input is the [RevisionIdentity.signingInput] envelope, base64-encoded for Transit, with
 *   `hash_algorithm=sha2-256`; Transit's own `vault:v<N>:…` signature string is stored verbatim as
 *   [IrSignature.signature], so key rotation keeps older revisions verifiable by their version.
 * - **Policy.** The PUBLISH identity gets `update` on `transit/sign/<key>`; every consumer gets
 *   `update` on `transit/verify/<key>` only. Neither gets `create` on `transit/keys` — with it, a
 *   mistyped key name upserts a fresh key and signs under it. Key type `ed25519` or `ecdsa-p256`.
 * - A 403 invalidates the token at the [OpenBaoTokenSource] and retries ONCE; a second 403 fails
 *   closed. Every non-200, timeout, I/O error or malformed response throws [IrSigningException];
 *   [verify] never throws for a WRONG signature (it returns false) but does for an UNAVAILABLE
 *   OpenBao, because "could not check" must not read as "checked and failed".
 */
class OpenBaoTransitIrSigner(
    private val transport: OpenBaoTransport,
    private val keyName: String,
    private val tokenSource: OpenBaoTokenSource,
    private val transitMount: String = "transit",
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : IrSignerPort,
    IrSignatureVerifierPort {

    init {
        require(PATH_SEGMENT.matches(keyName)) { "invalid Transit key name" }
        require(PATH_SEGMENT.matches(transitMount)) { "invalid Transit mount" }
    }

    override fun sign(identity: RevisionIdentity): IrSignature {
        val data = exchange("sign", mapOf("input" to input(identity), "hash_algorithm" to HASH_ALGORITHM))
        val sig = data["signature"]?.takeIf { it.isTextual }?.asText()
            ?: throw IrSigningException("Transit sign for key '$keyName' returned no signature")
        if (!TRANSIT_SIGNATURE.matches(sig)) {
            throw IrSigningException("Transit sign for key '$keyName' returned a malformed signature")
        }
        return IrSignature(keyName, ALGORITHM, sig)
    }

    override fun verify(identity: RevisionIdentity, signature: IrSignature): Boolean {
        if (signature.algorithm != ALGORITHM || signature.keyId != keyName) return false
        if (!TRANSIT_SIGNATURE.matches(signature.signature)) return false
        val data = exchange(
            "verify",
            mapOf(
                "input" to input(identity),
                "signature" to signature.signature,
                "hash_algorithm" to HASH_ALGORITHM,
            ),
        )
        val valid = data["valid"]
        if (valid == null || !valid.isBoolean) {
            throw IrSigningException("Transit verify for key '$keyName' returned no verdict")
        }
        return valid.asBoolean()
    }

    private fun input(identity: RevisionIdentity): String = Base64.getEncoder().encodeToString(identity.signingInput())

    private fun exchange(operation: String, fields: Map<String, String>): JsonNode {
        val body = objectMapper.writeValueAsString(fields)
        var token = tokenSource.token()
        var response = send(operation, body, token)
        if (response.statusCode() == HTTP_FORBIDDEN) {
            tokenSource.invalidate(token)
            token = tokenSource.token()
            response = send(operation, body, token)
        }
        if (response.statusCode() != HTTP_OK) fail(operation, "HTTP ${response.statusCode()}")
        val data = try {
            objectMapper.readTree(response.body())?.get("data")
        } catch (e: IOException) {
            fail(operation, "malformed JSON", e)
        }
        return data ?: fail(operation, "no data")
    }

    private fun fail(operation: String, what: String, cause: Throwable? = null): Nothing =
        throw IrSigningException("Transit $operation for key '$keyName' failed: $what", cause)

    @Suppress("TooGenericExceptionCaught")
    private fun send(operation: String, body: String, token: String): HttpResponse<String> = try {
        val request = HttpRequest.newBuilder()
            .uri(transport.uri("v1/$transitMount/$operation/$keyName"))
            .timeout(transport.requestTimeout)
            .header("Content-Type", "application/json")
            .header("X-Vault-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        transport.httpClient.send(request, OpenBaoTransport.boundedBody())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IrSigningException("Transit $operation for key '$keyName' interrupted", e)
    } catch (e: Exception) {
        throw IrSigningException("Transit $operation for key '$keyName' failed: ${e.javaClass.simpleName}", e)
    }

    companion object {
        const val ALGORITHM = "openbao-transit"
        private const val HASH_ALGORITHM = "sha2-256"
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private val PATH_SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")
        private val TRANSIT_SIGNATURE = Regex("^vault:v[0-9]+:[A-Za-z0-9+/=_-]+$")
    }
}
