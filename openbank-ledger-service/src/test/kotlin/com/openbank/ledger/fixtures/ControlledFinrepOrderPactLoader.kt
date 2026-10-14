// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.model.PactSource
import au.com.dius.pact.provider.junitsupport.loader.PactLoader
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Controlled loopback broker lineage; all interactions reach the real provider HTTP endpoint. */
@Suppress("UnusedPrivateProperty")
class ControlledFinrepOrderPactLoader(testClass: Class<*>) : PactLoader {
    override fun description(): String = "controlled historical/current FINREP broker replay; publication disabled"

    override fun getPactSource(): PactSource = error("Each Pact retains its independently validated broker source")

    override fun load(providerName: String): List<Pact> {
        require(providerName == HistoricalFinrepPactArtifact.PROVIDER)
        require(System.getProperty("pact.verifier.publishResults") == "false")
        val order = requireNotNull(System.getProperty("finrep.order.direction"))
        require(order in setOf("historical-first", "current-first"))
        val historical = Files.readAllBytes(Path.of(requireNotNull(System.getProperty("finrep.order.historicalFile"))))
        val historicalSha = requireNotNull(System.getProperty("finrep.order.historicalDigest"))
        require(HistoricalFinrepPactArtifact.sha256(historical) == historicalSha)
        val current = Files.readAllBytes(Path.of(requireNotNull(System.getProperty("finrep.order.currentFile"))))
        require(HistoricalFinrepPactArtifact.sha256(current) == System.getProperty("finrep.order.currentDigest"))
        val fixtures = listOf(
            Triple(historical, requireNotNull(System.getProperty("finrep.order.historicalConsumer")), 3),
            Triple(current, requireNotNull(System.getProperty("finrep.order.currentConsumer")), 11),
        ).let { if (order == "historical-first") it else it.reversed() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = URI("http://127.0.0.1:${server.address.port}")
        val mapper = ObjectMapper()
        for ((bytes, consumer, _) in fixtures) {
            val publication = HistoricalFinrepPactArtifact.publicationUri(origin, consumer)
            val document = (mapper.readTree(bytes) as ObjectNode).apply {
                val links = putObject("_links")
                links.putObject("self").put("href", publication.toString())
                links.putObject("pb:consumer-version").put("name", consumer)
                    .put("href", "$origin/pacticipants/openbank-finrep-service/versions/$consumer")
                links.putObject("pb:pact-version").put("href", "$origin/content/$consumer")
                links.putObject("pb:publish-verification-results").put("href", "$origin/results/$consumer")
            }.toString().toByteArray()
            server.createContext(publication.path) { exchange ->
                check(exchange.requestMethod == "GET") { "Controlled replay must not publish" }
                exchange.sendResponseHeaders(200, document.size.toLong())
                exchange.responseBody.use { it.write(document) }
            }
        }
        server.start()
        return try {
            HistoricalFinrepBrokerTransport(origin).use { transport ->
                fixtures.map { (bytes, consumer, count) ->
                    HistoricalFinrepBrokerReplay.fetch(
                        origin,
                        HistoricalFinrepPactExpectation(
                            consumer,
                            requireNotNull(System.getProperty("finrep.order.providerSha")),
                            HistoricalFinrepPactArtifact.sha256(bytes),
                            count,
                        ),
                        bytes,
                        transport,
                    ).pact
                }.also { pacts -> ControlledFinrepOrderResults.initialize(pacts, fixtures.map { it.second }) }
            }
        } finally {
            server.stop(0)
        }
    }
}

/** Full consumer/version/index sequence; repeated or reordered invocations fail before acceptance. */
object ControlledFinrepOrderResults {
    private lateinit var pacts: List<Pact>
    private lateinit var consumers: List<String>
    private lateinit var expected: List<Pair<String, Int>>
    private val completed = mutableListOf<Pair<String, Int>>()

    fun initialize(loaded: List<Pact>, versions: List<String>) {
        check(!::pacts.isInitialized) { "Controlled replay loaded more than once" }
        pacts = loaded
        consumers = versions
        expected = loaded.flatMapIndexed { pactIndex, pact ->
            pact.interactions.indices.map { versions[pactIndex] to it }
        }
    }

    fun displayName(index: Int): String {
        val key = expected[index]
        val pact = pacts[consumers.indexOf(key.first)]
        return "${key.first}:${key.second}:${pact.interactions[key.second].description}"
    }

    fun record(pact: Pact, interaction: au.com.dius.pact.core.model.Interaction) {
        val pactIndex = pacts.indexOfFirst { it === pact }
        require(pactIndex >= 0)
        val interactionIndex = pact.interactions.indexOfFirst { it === interaction }
        require(interactionIndex >= 0)
        val key = consumers[pactIndex] to interactionIndex
        require(completed.size < expected.size && expected[completed.size] == key && key !in completed) {
            "Controlled replay duplicated, omitted or reordered an interaction"
        }
        completed.add(key)
    }

    fun finish() {
        require(completed == expected && completed.size == 14) { "Controlled replay sequence is incomplete" }
        val mapper = ObjectMapper()
        val result = mapper.createArrayNode()
        pacts.forEachIndexed { pactIndex, pact ->
            pact.interactions.forEachIndexed { index, interaction ->
                result.addObject().put("consumerSha", consumers[pactIndex]).put("index", index)
                    .put("description", interaction.description).put("passed", true)
            }
        }
        Files.writeString(Path.of("build/controlled-finrep-order-outcomes.json"), result.toString())
    }
}
