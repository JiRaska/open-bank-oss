// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.time.Clock
import java.util.Base64

/**
 * Mastercard's OAuth 1.0a request signing (RSA-SHA256), which is how every Mastercard Developers
 * call authenticates.
 *
 * ## Why this is code and not configuration
 *
 * There is no bearer token to attach. The `Authorization` header is an RSA signature over a **base
 * string** built from the request's own method, URL and parameters, so it changes per request and
 * cannot come from a filter that does not know them. Getting any part of the construction wrong
 * produces a header that looks valid and is always rejected — a 401 that reads like a credential
 * problem when it is a construction problem, which is the failure this class exists to make
 * impossible to reach by accident.
 *
 * ## The parts that are easy to get wrong, and are pinned by tests
 *
 * - **Percent-encoding is RFC 3986, not form encoding.** `URLEncoder` turns a space into `+` and
 *   leaves `*` and `~` alone; OAuth needs `%20`, `%2A` and a literal `~`. [percentEncode] fixes all
 *   three, and `MastercardOAuthSignerTest` asserts each one.
 * - **Parameters are sorted after encoding, not before.** Sorting the raw values orders by a
 *   different alphabet than the one the server re-derives, and the mismatch only shows for inputs
 *   containing characters that encode.
 * - **The base string is encoded twice**: once per parameter, then again for the whole parameter
 *   block as it joins method and URL. Encoding once is the classic mistake and passes every test
 *   that uses parameter values with nothing to encode.
 * - **The URL in the base string drops the query and the default port**, and is lower-cased in its
 *   scheme and host. The server rebuilds it that way; anything else changes the signature.
 *
 * ## What it deliberately does not do
 *
 * No body hash. Mastercard requires `oauth_body_hash` for requests that carry a body, and this
 * adapter issues GETs only. Adding it later means adding one parameter, and a signer that computed
 * a hash of an absent body would produce a subtly wrong signature for every call — so the absence
 * is deliberate, not an omission.
 */
class MastercardOAuthSigner(
    private val consumerKey: String,
    private val signingKey: PrivateKey,
    private val clock: Clock,
    private val nonces: SecureRandom = SecureRandom(),
) {

    /**
     * Builds the `Authorization` header value for one request.
     *
     * [queryParameters] must be the parameters actually on the wire. They are signed, so a caller
     * that adds one afterwards invalidates the signature — which is why the adapter builds the URL
     * and the header from the same map.
     */
    fun authorizationHeader(
        method: String,
        url: String,
        queryParameters: Map<String, String> = emptyMap(),
    ): String {
        val oauthParameters = linkedMapOf(
            "oauth_consumer_key" to consumerKey,
            "oauth_nonce" to nonce(),
            "oauth_signature_method" to SIGNATURE_METHOD,
            "oauth_timestamp" to (clock.millis() / MILLIS_PER_SECOND).toString(),
            "oauth_version" to OAUTH_VERSION,
        )
        val signature = sign(baseString(method, url, queryParameters + oauthParameters))
        val header = (oauthParameters + mapOf("oauth_signature" to signature))
            .entries
            .joinToString(",") { (k, v) -> """${percentEncode(k)}="${percentEncode(v)}"""" }
        return "OAuth $header"
    }

    /**
     * `METHOD&encoded-url&encoded-sorted-parameters`.
     *
     * Internal rather than private so the test can assert the base string itself. Asserting only
     * the final signature would make a wrong base string indistinguishable from a wrong key, and
     * the base string is where the mistakes live.
     */
    internal fun baseString(method: String, url: String, parameters: Map<String, String>): String {
        val encoded = parameters
            .map { (k, v) -> percentEncode(k) to percentEncode(v) }
            // Sorted AFTER encoding: the server re-derives the order from the encoded forms, and
            // sorting the raw values orders by a different alphabet whenever a value contains a
            // character that encodes.
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (k, v) -> "$k=$v" }
        return listOf(
            method.uppercase(),
            percentEncode(normaliseUrl(url)),
            // The second encoding. Encoding once is the classic mistake and passes any test whose
            // parameter values contain nothing that encodes.
            percentEncode(encoded),
        ).joinToString("&")
    }

    /**
     * Scheme and host lower-cased, query dropped, default port dropped.
     *
     * The server rebuilds the URL this way before verifying, so any difference — an upper-case
     * host, an explicit `:443` — changes the signature while leaving the request itself valid.
     */
    internal fun normaliseUrl(url: String): String {
        val uri = URI(url)
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val port = uri.port
        val explicitPort = if (port == -1 || isDefaultPort(scheme, port)) "" else ":$port"
        val path = uri.rawPath.ifEmpty { "/" }
        return "$scheme://$host$explicitPort$path"
    }

    private fun isDefaultPort(scheme: String, port: Int) =
        (scheme == "https" && port == HTTPS_PORT) || (scheme == "http" && port == HTTP_PORT)

    private fun sign(baseString: String): String {
        val signer = Signature.getInstance(JCA_ALGORITHM)
        signer.initSign(signingKey)
        signer.update(baseString.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun nonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        nonces.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val SIGNATURE_METHOD = "RSA-SHA256"
        const val OAUTH_VERSION = "1.0"

        private const val JCA_ALGORITHM = "SHA256withRSA"
        private const val MILLIS_PER_SECOND = 1000L
        private const val NONCE_BYTES = 16
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80

        /**
         * RFC 3986 percent-encoding, which is NOT what `URLEncoder` produces.
         *
         * Three concrete differences, each pinned by a test: a space becomes `%20` and not `+`,
         * `*` becomes `%2A` rather than being left alone, and `~` stays literal rather than
         * becoming `%7E`. Every one of them changes the signature, and none of them shows up in a
         * test whose inputs are alphanumeric.
         */
        fun percentEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
    }
}
