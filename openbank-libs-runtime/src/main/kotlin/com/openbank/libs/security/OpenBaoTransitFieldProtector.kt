// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * [FieldProtector] over OpenBao Transit (ADR-0320 P3), lifted from card-issuance's
 * `OpenBaoTransitDekUnwrapper` (ADR-0262): plain `java.net.http`, no Vault SDK.
 *
 * **A plain class, deliberately not a CDI bean** (ADR-0320 classpath-guard rule 1): a service opts
 * in by producing one from its own `src/main`, so libs-runtime registers nothing in any consumer's
 * bean archive.
 *
 * - Ciphertext is Transit's own `vault:v<N>:` string, passed through untouched, so values written
 *   by `vault write transit/encrypt/...` or card-issuance's KEK wrap stay readable.
 * - AAD maps to Transit's `associated_data` (AES-GCM keys). Transit's `context` is key-DERIVATION
 *   input, not AAD, and is not used.
 * - Every request has a timeout; every non-200, timeout, I/O error or malformed response throws
 *   [FieldProtectionException]. Nothing is logged here, and exception messages carry only the key
 *   name and HTTP status — never plaintext, ciphertext, token or response body.
 * - No retry: a caller that wants one wraps the call. Silently retrying a decrypt on a hot path
 *   would multiply a Transit outage into latency everywhere.
 *
 * @param tokenSupplier returns a Transit-capable OpenBao token; see [OpenBaoKubernetesLogin].
 */
@Suppress("LongParameterList")
class OpenBaoTransitFieldProtector(
    private val baoAddr: URI,
    private val keyName: String,
    private val tokenSupplier: () -> String,
    tokenizationPepper: ByteArray,
    private val transitMount: String = "transit",
    private val requestTimeout: Duration = Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS),
    private val httpClient: HttpClient = defaultHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : FieldProtector {

    private val tokenizer = BlindIndexTokenizer(tokenizationPepper)

    init {
        require(PATH_SEGMENT.matches(keyName)) { "invalid Transit key name" }
        require(PATH_SEGMENT.matches(transitMount)) { "invalid Transit mount" }
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray?): String {
        val data = call("encrypt", mapOf("plaintext" to b64(plaintext)), aad)
        return validCiphertext(data)
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
        return validCiphertext(call("rewrap", mapOf("ciphertext" to ciphertext), aad))
    }

    override fun tokenize(value: String): String = tokenizer.tokenize(value)

    private fun validCiphertext(data: JsonNode): String {
        val ct = data["ciphertext"]?.takeIf { it.isTextual }?.asText()
            ?: throw FieldProtectionException("Transit response for key '$keyName' carried no ciphertext")
        TransitCiphertext.parse(ct)
        return ct
    }

    private fun call(operation: String, fields: Map<String, String>, aad: ByteArray?): JsonNode {
        val body = if (aad == null) fields else fields + ("associated_data" to b64(aad))
        val response = send(operation, body)
        if (response.statusCode() != HTTP_OK) {
            throw FieldProtectionException(
                "Transit $operation for key '$keyName' failed: HTTP ${response.statusCode()}",
            )
        }
        val data = try {
            objectMapper.readTree(response.body())?.get("data")
        } catch (e: IOException) {
            throw FieldProtectionException("Transit $operation for key '$keyName' returned malformed JSON", e)
        }
        return data ?: fail("Transit $operation for key '$keyName' returned no data")
    }

    /** Every transport failure (timeout is an IOException) becomes a [FieldProtectionException]. */
    @Suppress("TooGenericExceptionCaught")
    private fun send(operation: String, body: Map<String, String>): HttpResponse<String> = try {
        val request = HttpRequest.newBuilder()
            .uri(baoAddr.resolve("/v1/$transitMount/$operation/$keyName"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("X-Vault-Token", tokenSupplier())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: FieldProtectionException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw FieldProtectionException("Transit $operation for key '$keyName' interrupted", e)
    } catch (e: Exception) {
        throw FieldProtectionException("Transit $operation for key '$keyName' failed: ${e.javaClass.simpleName}", e)
    }

    private fun fail(message: String): Nothing = throw FieldProtectionException(message)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 10L
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 5L
        private const val HTTP_OK = 200
        private val PATH_SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}
