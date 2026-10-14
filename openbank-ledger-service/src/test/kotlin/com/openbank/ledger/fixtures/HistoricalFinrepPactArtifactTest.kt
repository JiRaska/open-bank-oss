// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class HistoricalFinrepPactArtifactTest {
    @TempDir lateinit var directory: Path

    private val mapper = ObjectMapper()
    private val consumerSha = "1".repeat(40)
    private val providerSha = "2".repeat(40)
    private val origin = URI("https://broker.invalid")

    private fun pactBytes(): ByteArray = Files.readAllBytes(
        Path.of("../pacts/openbank-finrep-service-openbank-ledger-service.json"),
    )

    private fun expectation(bytes: ByteArray) = HistoricalFinrepPactExpectation(
        consumerSha,
        providerSha,
        HistoricalFinrepPactArtifact.sha256(bytes),
        mapper.readTree(bytes).path("interactions").size(),
    )

    private fun manifest(bytes: ByteArray): MutableMap<String, Any> = mutableMapOf(
        "consumer" to HistoricalFinrepPactArtifact.CONSUMER,
        "provider" to HistoricalFinrepPactArtifact.PROVIDER,
        "brokerOrigin" to origin.toString(),
        "consumerSha" to consumerSha,
        "providerSha" to providerSha,
        "contentSha256" to HistoricalFinrepPactArtifact.sha256(bytes),
        "interactionCount" to mapper.readTree(bytes).path("interactions").size(),
    )

    private fun write(bytes: ByteArray, provenance: Map<String, Any>): Pair<Path, Path> {
        val pact = directory.resolve("pact.json")
        val metadata = directory.resolve("manifest.json")
        Files.write(pact, bytes)
        mapper.writeValue(metadata.toFile(), provenance)
        return pact to metadata
    }

    @Test
    fun `validated artifact retains pair version content and interaction provenance`() {
        val bytes = pactBytes()
        val (file, metadata) = write(bytes, manifest(bytes))
        val artifact = HistoricalFinrepPactArtifact.validate(file, metadata, origin, expectation(bytes))
        assertThat(artifact.consumerSha).isEqualTo(consumerSha)
        assertThat(artifact.providerSha).isEqualTo(providerSha)
        assertThat(artifact.interactionCount).isEqualTo(mapper.readTree(bytes).path("interactions").size())
    }

    @Test
    fun `wrong pair hashes count origin and version are refused`() {
        val bytes = pactBytes()
        val bad = listOf(
            "consumer" to "another-consumer", "provider" to "another-provider",
            "consumerSha" to "short", "providerSha" to "not-a-commit",
            "contentSha256" to "0".repeat(64), "interactionCount" to 0,
            "interactionCount" to 999, "interactionCount" to "8",
            "brokerOrigin" to "https://other.invalid",
        )
        for ((field, value) in bad) {
            val (file, metadata) = write(bytes, manifest(bytes).apply { put(field, value) })
            assertThatThrownBy { HistoricalFinrepPactArtifact.validate(file, metadata, origin, expectation(bytes)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `wrong service pair in content is refused even with a matching digest`() {
        val tree = mapper.readTree(pactBytes())
        (tree.path("consumer") as com.fasterxml.jackson.databind.node.ObjectNode).put("name", "another-consumer")
        val bytes = mapper.writeValueAsBytes(tree)
        val (file, metadata) = write(bytes, manifest(bytes))
        assertThatThrownBy { HistoricalFinrepPactArtifact.validate(file, metadata, origin, expectation(bytes)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unsafe origins and commit path injection are refused`() {
        for (value in listOf(
            "https://user:secret@broker.invalid",
            "https://broker.invalid/path",
            "https://broker.invalid?query=secret",
            "https://broker.invalid#fragment",
            "http://broker.invalid",
            "file:///tmp/pact.json",
        )) {
            assertThatThrownBy { HistoricalFinrepPactArtifact.publicationUri(URI(value), consumerSha) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { HistoricalFinrepPactArtifact.publicationUri(origin, "../other") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `controlled HTTP fetch reads only the constructed version path without credentials`() {
        val bytes = pactBytes()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestedPath = ""
        var authorization: String? = "unseen"
        server.createContext("/") { exchange ->
            requestedPath = exchange.requestURI.path
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val fetched = HistoricalFinrepPactArtifact.fetch(
                URI("http://127.0.0.1:${server.address.port}"),
                consumerSha,
            )
            assertThat(fetched).isEqualTo(bytes)
            assertThat(requestedPath).isEqualTo(
                "/pacts/provider/openbank-ledger-service/consumer/openbank-finrep-service/version/$consumerSha",
            )
            assertThat(authorization).isNull()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `redirects are refused without making a redirected request`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var redirected = false
        server.createContext("/pacts/") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/redirected")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/redirected") { exchange ->
            redirected = true
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            assertThatThrownBy {
                HistoricalFinrepPactArtifact.fetch(URI("http://127.0.0.1:${server.address.port}"), consumerSha)
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(redirected).isFalse()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `duplicate manifest and Pact identity fields are refused`() {
        val bytes = pactBytes()
        val (file, metadata) = write(bytes, manifest(bytes))
        Files.writeString(metadata, "{\"consumer\":\"openbank-finrep-service\",\"consumer\":\"other\"}")
        assertThatThrownBy { HistoricalFinrepPactArtifact.validate(file, metadata, origin, expectation(bytes)) }
            .isInstanceOf(com.fasterxml.jackson.core.JsonParseException::class.java)
        val duplicate = "{\"consumer\":{},\"consumer\":{}}".toByteArray()
        write(duplicate, manifest(bytes).apply { put("contentSha256", HistoricalFinrepPactArtifact.sha256(duplicate)) })
        assertThatThrownBy {
            HistoricalFinrepPactArtifact.validate(
                file,
                metadata,
                origin,
                expectation(bytes).copy(
                    contentSha256 = HistoricalFinrepPactArtifact.sha256(duplicate),
                ),
            )
        }.isInstanceOf(com.fasterxml.jackson.core.JsonParseException::class.java)
    }

    @Test
    fun `a self-consistent manifest cannot change independently configured content expectations`() {
        val bytes = pactBytes()
        val changed = bytes + "\n".toByteArray()
        val (file, metadata) = write(changed, manifest(changed))
        assertThatThrownBy { HistoricalFinrepPactArtifact.validate(file, metadata, origin, expectation(bytes)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `official file URL loader verifies every validated interaction and refuses publication`() {
        val bytes = pactBytes()
        val (file, metadata) = write(bytes, manifest(bytes))
        val originFile = directory.resolve("trusted-origin")
        Files.writeString(originFile, origin.toString())
        val properties = mapOf(
            "finrep.historical.pactFile" to file.toString(),
            "finrep.historical.manifestFile" to metadata.toString(),
            "finrep.historical.trustedBrokerOriginFile" to originFile.toString(),
            "finrep.historical.providerSha" to providerSha,
            "finrep.historical.consumerSha" to consumerSha,
            "finrep.historical.contentSha256" to HistoricalFinrepPactArtifact.sha256(bytes),
            "finrep.historical.interactionCount" to mapper.readTree(bytes).path("interactions").size().toString(),
            "pact.verifier.publishResults" to "false",
        )
        val original = properties.keys.associateWith { System.getProperty(it) }
        try {
            properties.forEach { (key, value) -> System.setProperty(key, value) }
            val pacts = HistoricalFinrepPactLoader(javaClass).load(HistoricalFinrepPactArtifact.PROVIDER)
            assertThat(pacts).hasSize(1)
            assertThat(pacts.single().interactions).hasSize(manifest(bytes)["interactionCount"] as Int)
            System.setProperty("pact.verifier.publishResults", "true")
            assertThatThrownBy { HistoricalFinrepPactLoader(javaClass).load(HistoricalFinrepPactArtifact.PROVIDER) }
                .isInstanceOf(IllegalArgumentException::class.java)
        } finally {
            original.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}
