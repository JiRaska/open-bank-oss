// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import au.com.dius.pact.core.model.BrokerUrlSource
import au.com.dius.pact.core.model.DefaultPactReader
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.support.UnwrapException
import au.com.dius.pact.core.support.json.JsonException
import au.com.dius.pact.core.support.json.JsonParser
import au.com.dius.pact.core.support.json.JsonValue
import com.fasterxml.jackson.core.JsonParser.Feature
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI

/** Broker response lineage, independently compared with the pinned consumer artifact before replay. */
internal class HistoricalFinrepBrokerReplay private constructor(
    val pact: Pact,
    private val expectation: HistoricalFinrepPactExpectation,
    val brokerResponseSha256: String,
    val publicationLink: URI,
    private val transport: HistoricalFinrepBrokerTransport,
    private val originalContent: ObjectNode,
    private val origin: URI,
) {
    val originalArtifactSha256: String get() = expectation.contentSha256
    val consumerSha: String get() = expectation.consumerSha
    val providerSha: String get() = expectation.providerSha

    private val successful = mutableSetOf<Int>()
    private var failed = false
    private var publicationAttempted = false

    /** Each full-Pact interaction must finish exactly once; failures and skips permanently block publication. */
    fun record(index: Int, passed: Boolean, skipped: Boolean = false) {
        require(index in pact.interactions.indices && index !in successful) { "Invalid or repeated interaction result" }
        if (passed && !skipped) successful.add(index) else failed = true
    }

    /** Default off. A separately reviewed main-only caller must supply its attested checkout identity. */
    fun publish(
        publisher: HistoricalFinrepPublisher,
        mainCheckoutSha: String,
        enabled: Boolean = false,
    ): HistoricalFinrepVerificationReceipt {
        require(enabled) { "Historical broker publication is disabled" }
        require(mainCheckoutSha == providerSha && COMMIT.matches(mainCheckoutSha)) {
            "Provider is not the attested main checkout"
        }
        require(!failed && successful.size == pact.interactions.size) { "Full Pact did not pass without skips" }
        check(!publicationAttempted) { "Publication was already attempted; ambiguous requests must not be retried" }
        publicationAttempted = true
        val receipt = transport.validateLink(publisher.publish(this).toString())
        val result = readJson(transport.get(receipt))
        require(result.path("success").isBoolean && result.path("success").booleanValue()) {
            "Broker receipt is not successful"
        }
        require(result.path("providerName").asText() == HistoricalFinrepPactArtifact.PROVIDER) {
            "Broker receipt names another provider"
        }
        require(result.path("providerApplicationVersion").asText() == providerSha) {
            "Broker receipt names another provider version"
        }
        require(result.path("buildUrl").asText() == publisher.buildUrl.toString()) {
            "Broker receipt names another hosted run"
        }
        val links = result.path("_links")
        val verifiedPact = transport.validateLink(links.path("pb:pact-version").path("href").asText())
        val readback = readJson(transport.get(verifiedPact))
        require(readback is ObjectNode && contractContent(readback) == originalContent) {
            "Broker receipt refers to another Pact content"
        }
        validateConsumerVersion(readback.path("_links"), origin, consumerSha, transport)
        return HistoricalFinrepVerificationReceipt(
            consumerSha,
            providerSha,
            originalArtifactSha256,
            brokerResponseSha256,
            expectation.interactionCount,
            publisher.buildUrl.toString(),
        )
    }

    companion object {
        private val COMMIT = Regex("[0-9a-f]{40}")
        private val mapper = ObjectMapper().enable(Feature.STRICT_DUPLICATE_DETECTION)

        fun fetch(
            origin: URI,
            expected: HistoricalFinrepPactExpectation,
            originalBytes: ByteArray,
            transport: HistoricalFinrepBrokerTransport,
        ): HistoricalFinrepBrokerReplay {
            require(COMMIT.matches(expected.providerSha) && expected.interactionCount > 0)
            require(HistoricalFinrepPactArtifact.sha256(originalBytes) == expected.contentSha256) {
                "Original consumer artifact differs from pinned digest"
            }
            val url = HistoricalFinrepPactArtifact.publicationUri(origin, expected.consumerSha)
            val bytes = transport.get(url)
            val broker = readJson(bytes)
            val original = readJson(originalBytes)
            require(broker is ObjectNode && original is ObjectNode) { "Pact must be an object" }
            val links = broker.path("_links")
            require(links.isObject) { "Broker publication lacks HAL lineage" }
            val publication = transport.validateLink(
                links.path("pb:publish-verification-results").path("href").asText(),
            )
            val self = transport.validateLink(links.path("self").path("href").asText())
            require(self == url) { "Broker returned another consumer version" }
            validateConsumerVersion(links, origin, expected.consumerSha, transport)
            transport.validateLink(links.path("pb:pact-version").path("href").asText())
            val stripped = contractContent(broker)
            require(stripped == original) { "Broker content differs from independently pinned consumer artifact" }
            require(
                broker.path("consumer").path("name").asText() == HistoricalFinrepPactArtifact.CONSUMER &&
                    broker.path("provider").path("name").asText() == HistoricalFinrepPactArtifact.PROVIDER &&
                    broker.path("interactions").isArray &&
                    broker.path("interactions").size() == expected.interactionCount,
            ) { "Unexpected broker Pact pair or interaction count" }
            val attributes = listOf("self", "pb:consumer-version", "pb:pact-version", "pb:publish-verification-results")
                .associateWith { mapper.convertValue(links.path(it), Map::class.java) }
            val source = BrokerUrlSource(url.toString(), origin.toString(), attributes)
            val pact = parsePact(broker, source)
            require(pact.interactions.size == expected.interactionCount && pact.source === source) {
                "Pact loader lost interactions or broker lineage"
            }
            return HistoricalFinrepBrokerReplay(
                pact,
                expected,
                HistoricalFinrepPactArtifact.sha256(bytes),
                publication,
                transport,
                original,
                origin,
            )
        }

        private fun readJson(bytes: ByteArray): JsonNode = try {
            requireNotNull(mapper.readTree(bytes)) { "Broker JSON document is empty" }
        } catch (_: JsonProcessingException) {
            throw IllegalArgumentException("Broker JSON document is invalid")
        }

        private fun parsePact(broker: ObjectNode, source: BrokerUrlSource): Pact = try {
            DefaultPactReader.pactFromJson(JsonParser.parseString(broker.toString()) as JsonValue.Object, source)
        } catch (_: UnwrapException) {
            throw IllegalArgumentException("Broker Pact document is invalid")
        } catch (_: JsonException) {
            throw IllegalArgumentException("Broker Pact document is invalid")
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Broker Pact document is invalid")
        } catch (_: IllegalStateException) {
            throw IllegalArgumentException("Broker Pact document is invalid")
        } catch (_: ClassCastException) {
            throw IllegalArgumentException("Broker Pact document is invalid")
        }

        // Only broker document metadata is removed; contract payloads and matchers remain untouched.
        private fun contractContent(document: ObjectNode): ObjectNode = document.deepCopy().apply {
            remove(listOf("_links", "createdAt", "updatedAt"))
        }

        private fun validateConsumerVersion(
            links: JsonNode,
            origin: URI,
            consumerSha: String,
            transport: HistoricalFinrepBrokerTransport,
        ) {
            val version = links.path("pb:consumer-version")
            val expected = origin.resolve(
                "/pacticipants/${HistoricalFinrepPactArtifact.CONSUMER}/versions/$consumerSha",
            )
            require(
                version.path("name").asText() == consumerSha &&
                    transport.validateLink(version.path("href").asText()) == expected,
            ) {
                "Broker lineage names another consumer version"
            }
        }
    }
}

/** Only controlled test stubs are wired initially; no workflow or automatic publisher uses this interface. */
internal interface HistoricalFinrepPublisher {
    val buildUrl: URI
    fun publish(replay: HistoricalFinrepBrokerReplay): URI
}

/** Safe exported receipt: private broker origins, source URLs and HAL links stay only in memory. */
internal data class HistoricalFinrepVerificationReceipt(
    val consumerSha: String,
    val providerSha: String,
    val originalArtifactSha256: String,
    val brokerResponseSha256: String,
    val interactionCount: Int,
    val buildUrl: String,
)
