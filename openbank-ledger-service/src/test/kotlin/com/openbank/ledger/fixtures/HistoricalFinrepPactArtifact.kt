// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

internal data class HistoricalFinrepPactExpectation(
    val consumerSha: String,
    val providerSha: String,
    val contentSha256: String,
    val interactionCount: Int,
)

/** Offline replay declared provenance. It is never a broker verification or publication receipt. */
internal data class HistoricalFinrepPactArtifact(
    val file: Path,
    val consumerSha: String,
    val providerSha: String,
    val contentSha256: String,
    val interactionCount: Int,
    val bytes: ByteArray,
) {
    companion object {
        const val CONSUMER = "openbank-finrep-service"
        const val PROVIDER = "openbank-ledger-service"
        private const val MAX_BYTES = 10 * 1024 * 1024
        private val COMMIT = Regex("[0-9a-f]{40}")
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

        fun validate(
            file: Path,
            manifest: Path,
            trustedOrigin: URI,
            expected: HistoricalFinrepPactExpectation,
        ): HistoricalFinrepPactArtifact {
            require(Files.isRegularFile(file) && !Files.isSymbolicLink(file)) { "Pact artifact must be a regular file" }
            require(Files.isRegularFile(manifest) && !Files.isSymbolicLink(manifest)) {
                "Pact manifest must be a regular file"
            }
            require(Files.size(manifest) <= MAX_BYTES && Files.size(file) <= MAX_BYTES) { "Pact artifact is too large" }
            val provenance = mapper.readTree(Files.readAllBytes(manifest))
            require(
                provenance.path("consumer").asText() == CONSUMER && provenance.path("provider").asText() == PROVIDER,
            ) {
                "Only the FINREP/ledger pair is supported"
            }
            require(provenance.path("brokerOrigin").asText() == trustedOrigin.toString()) {
                "Pact provenance does not match the trusted broker origin"
            }
            val consumer = provenance.path("consumerSha").asText()
            val provider = provenance.path("providerSha").asText()
            val digest = provenance.path("contentSha256").asText()
            require(COMMIT.matches(consumer) && COMMIT.matches(provider) && DIGEST.matches(digest)) {
                "Invalid Pact provenance hashes"
            }
            publicationUri(trustedOrigin, consumer)
            val count = provenance.path("interactionCount")
            require(count.isIntegralNumber && count.canConvertToInt() && count.intValue() > 0) {
                "Invalid interaction count"
            }
            require(
                consumer == expected.consumerSha &&
                    provider == expected.providerSha &&
                    digest == expected.contentSha256 &&
                    count.intValue() == expected.interactionCount,
            ) { "Declared provenance differs from independently configured expectations" }
            val bytes = Files.readAllBytes(file)
            require(sha256(bytes) == digest) { "Pact content does not match its declared provenance" }
            val pact = mapper.readTree(bytes)
            require(
                pact.path("consumer").path("name").asText() == CONSUMER &&
                    pact.path("provider").path("name").asText() == PROVIDER,
            ) {
                "Pact content names another service pair"
            }
            require(pact.path("interactions").isArray && pact.path("interactions").size() == count.intValue()) {
                "Pact interaction count does not match its declared provenance"
            }
            return HistoricalFinrepPactArtifact(file, consumer, provider, digest, count.intValue(), bytes)
        }

        fun publicationUri(trustedOrigin: URI, consumerSha: String): URI {
            require(COMMIT.matches(consumerSha)) { "Invalid consumer commit" }
            require(
                trustedOrigin.scheme == "https" ||
                    (trustedOrigin.scheme == "http" && trustedOrigin.host == "127.0.0.1"),
            ) { "Broker requires HTTPS" }
            require(
                trustedOrigin.host != null &&
                    trustedOrigin.userInfo == null &&
                    trustedOrigin.rawQuery == null &&
                    trustedOrigin.rawFragment == null &&
                    trustedOrigin.path.orEmpty() in setOf("", "/"),
            ) { "Broker configuration must be an origin" }
            return trustedOrigin.resolve("/pacts/provider/$PROVIDER/consumer/$CONSUMER/version/$consumerSha")
        }

        /** Never follows redirects or sends credentials. The origin comes from trusted local configuration. */
        fun fetch(trustedOrigin: URI, consumerSha: String): ByteArray {
            val request = HttpRequest.newBuilder(publicationUri(trustedOrigin, consumerSha))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build()
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            return response.body().use { body ->
                require(response.statusCode() == 200) { "Historical Pact fetch did not return 200" }
                val bytes = body.readNBytes(MAX_BYTES + 1)
                require(bytes.size <= MAX_BYTES) { "Historical Pact is too large" }
                bytes
            }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
