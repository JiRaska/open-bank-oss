// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * [FieldProtector] over OpenBao Transit (ADR-0320 P3), lifted from card-issuance's
 * `OpenBaoTransitDekUnwrapper` (ADR-0262): plain `java.net.http`, no Vault SDK.
 *
 * **A plain class, deliberately not a CDI bean** (ADR-0320 classpath-guard rule 1): a service opts
 * in by producing one from its own `src/main`, so libs-runtime registers nothing in any consumer's
 * bean archive.
 *
 * - Ciphertext is Transit's own `vault:v<N>:` string, passed through untouched.
 * - AAD maps to Transit's `associated_data`. Keys must be NON-DERIVED AEAD types
 *   (`aes256-gcm96`, `aes128-gcm96`, `chacha20-poly1305`); [verifyKey] checks this at startup.
 *   Transit's `context` is key-DERIVATION input, not AAD, and is not used.
 * - [rewrap] with AAD is decrypt + encrypt in this process: OpenBao's `rewrap` endpoint takes no
 *   `associated_data` (verified against openbao 2.5.4 in `OpenBaoTransitFieldProtectorIT`).
 * - **Policy.** Grant the service `update` on `transit/encrypt/<key>`, `transit/decrypt/<key>`
 *   (and `transit/rewrap/<key>` if used) and NOT `create`: with `create`, an encrypt against a
 *   mistyped key name silently upserts a fresh key. [verifyKey] additionally needs `read` on
 *   `transit/keys/<key>`.
 * - Transport: https only, bounded response body, path prefix preserved — see [OpenBaoTransport].
 * - A 403 invalidates the token at the [OpenBaoTokenSource] and retries ONCE with a fresh token;
 *   a second 403 fails closed. No other retry.
 * - Every non-200, timeout, I/O error or malformed response throws [FieldProtectionException].
 *   Nothing is logged here, and exception messages carry only the key name and HTTP status —
 *   never plaintext, ciphertext, token or response body.
 */
class OpenBaoTransitFieldProtector(
    private val transport: OpenBaoTransport,
    private val keyName: String,
    private val tokenSource: OpenBaoTokenSource,
    tokenizationPepper: ByteArray,
    private val transitMount: String = "transit",
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : FieldProtector {

    private val tokenizer = BlindIndexTokenizer(tokenizationPepper)

    init {
        require(PATH_SEGMENT.matches(keyName)) { "invalid Transit key name" }
        require(PATH_SEGMENT.matches(transitMount)) { "invalid Transit mount" }
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray?): String {
        val data = call("encrypt", mapOf("plaintext" to b64(plaintext)), aad)
        return validCiphertext(data, keyName)
    }

    override fun decrypt(ciphertext: String, aad: ByteArray?): ByteArray {
        TransitCiphertext.parse(ciphertext)
        val data = call("decrypt", mapOf("ciphertext" to ciphertext), aad)
        val encoded = data["plaintext"]?.takeIf { it.isTextual }?.asText()
            ?: throw FieldProtectionException("Transit decrypt for key '$keyName' returned no plaintext")
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw FieldProtectionException("Transit decrypt for key '$keyName' returned malformed plaintext", e)
        }
    }

    override fun rewrap(ciphertext: String, aad: ByteArray?): String {
        TransitCiphertext.parse(ciphertext)
        if (aad != null) {
            val plaintext = decrypt(ciphertext, aad)
            try {
                return encrypt(plaintext, aad)
            } finally {
                plaintext.fill(0)
            }
        }
        return validCiphertext(call("rewrap", mapOf("ciphertext" to ciphertext), null), keyName)
    }

    override fun tokenize(value: String): String = tokenizer.tokenize(value)

    override fun tokenize(value: String, domain: String): String = tokenizer.tokenize(value, domain)

    /**
     * Optional startup check: the key exists, is not derived, and is an AEAD type that honours
     * `associated_data`. Throws [FieldProtectionException] otherwise.
     */
    fun verifyKey() {
        val data = exchange("keys", "GET", null)
        val type = data["type"]?.asText()
        if (type !in AEAD_KEY_TYPES) {
            throw FieldProtectionException("Transit key '$keyName' has unsupported type '$type'")
        }
        if (data["derived"]?.asBoolean() == true) {
            throw FieldProtectionException("Transit key '$keyName' is derived; only non-derived keys are supported")
        }
    }

    private fun call(operation: String, fields: Map<String, String>, aad: ByteArray?): JsonNode {
        val body = if (aad == null) fields else fields + ("associated_data" to b64(aad))
        return exchange(operation, "POST", objectMapper.writeValueAsString(body))
    }

    private fun exchange(operation: String, method: String, body: String?): JsonNode {
        var token = tokenSource.token()
        var response = send(operation, method, body, token)
        if (response.statusCode() == HTTP_FORBIDDEN) {
            tokenSource.invalidate(token)
            token = tokenSource.token()
            response = send(operation, method, body, token)
        }
        if (response.statusCode() != HTTP_OK) {
            throw FieldProtectionException(
                "Transit $operation for key '$keyName' failed: HTTP ${response.statusCode()}",
            )
        }
        return parseData(operation, response.body())
    }

    private fun parseData(operation: String, body: String): JsonNode {
        val data = try {
            objectMapper.readTree(body)?.get("data")
        } catch (e: IOException) {
            throw FieldProtectionException("Transit $operation for key '$keyName' returned malformed JSON", e)
        }
        return data ?: throw FieldProtectionException("Transit $operation for key '$keyName' returned no data")
    }

    /** Every transport failure (timeout is an IOException) becomes a [FieldProtectionException]. */
    @Suppress("TooGenericExceptionCaught")
    private fun send(operation: String, method: String, body: String?, token: String): HttpResponse<String> = try {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder()
            .uri(transport.uri("v1/$transitMount/$operation/$keyName"))
            .timeout(transport.requestTimeout)
            .header("Content-Type", "application/json")
            .header("X-Vault-Token", token)
            .method(method, publisher)
            .build()
        transport.httpClient.send(request, OpenBaoTransport.boundedBody())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw FieldProtectionException("Transit $operation for key '$keyName' interrupted", e)
    } catch (e: Exception) {
        throw FieldProtectionException("Transit $operation for key '$keyName' failed: ${e.javaClass.simpleName}", e)
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_FORBIDDEN = 403
        private val PATH_SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

        /** Transit key types that authenticate `associated_data`. */
        val AEAD_KEY_TYPES = setOf("aes256-gcm96", "aes128-gcm96", "chacha20-poly1305")
    }
}

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun validCiphertext(data: JsonNode, keyName: String): String {
    val ct = data["ciphertext"]?.takeIf { it.isTextual }?.asText()
        ?: throw FieldProtectionException("Transit response for key '$keyName' carried no ciphertext")
    TransitCiphertext.parse(ct)
    return ct
}
