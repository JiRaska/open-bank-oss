// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.model.PactSource
import au.com.dius.pact.provider.junitsupport.loader.PactLoader
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Explicit hosted loader. Pact's automatic publication remains disabled. */
@Suppress("UnusedPrivateProperty")
class HostedHistoricalFinrepPactLoader(testClass: Class<*>) : PactLoader {
    override fun description(): String = "independently pinned historical FINREP broker Pact"
    override fun getPactSource(): PactSource = HostedFinrepRun.replay.pact.source
    override fun load(providerName: String): List<Pact> {
        require(providerName == HistoricalFinrepPactArtifact.PROVIDER)
        require(System.getProperty("pact.verifier.publishResults") == "false")
        HostedFinrepRun.open()
        return listOf(HostedFinrepRun.replay.pact)
    }
}

object HostedFinrepRun {
    private val mapper = ObjectMapper()
    internal lateinit var replay: HistoricalFinrepBrokerReplay
        private set
    private lateinit var transport: HistoricalFinrepBrokerTransport
    private val verified = mutableSetOf<Int>()
    private var failed = false

    fun input(name: String): String = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
        "Required hosted replay input is missing"
    }

    internal fun expectation(): HistoricalFinrepPactExpectation = HistoricalFinrepPactExpectation(
        input("FINREP_CONSUMER_SHA"),
        input("FINREP_PROVIDER_SHA"),
        input("FINREP_ORIGINAL_SHA256"),
        input("FINREP_INTERACTION_COUNT").toInt(),
    )

    fun open() {
        check(!::replay.isInitialized) { "Historical replay was already loaded" }
        val origin = try {
            URI(input("PACT_BROKER_URL"))
        } catch (_: Exception) {
            throw IllegalArgumentException("Hosted broker origin is invalid")
        }
        require(origin.scheme == "https") { "Hosted broker requires HTTPS" }
        transport = HistoricalFinrepBrokerTransport(
            origin,
            username = input("PACT_BROKER_USERNAME"),
            password = input("PACT_BROKER_PASSWORD"),
        )
        val original = Files.readAllBytes(Path.of(input("FINREP_ORIGINAL_PACT")))
        try {
            replay = HistoricalFinrepBrokerReplay.fetch(origin, expectation(), original, transport)
        } catch (failure: Throwable) {
            transport.close()
            throw failure
        }
    }

    fun record(index: Int, passed: Boolean) {
        replay.record(index, passed)
        if (passed) verified.add(index) else failed = true
    }

    internal fun publisher(buildUrl: URI): HistoricalFinrepPublisher =
        HistoricalFinrepBrokerPublisher(URI(input("PACT_BROKER_URL")), transport, buildUrl)

    fun close() = transport.close()

    fun finish() {
        try {
            require(!failed && verified.size == replay.pact.interactions.size) { "Historical replay is incomplete" }
            val output = Path.of(input("FINREP_OUTCOMES"))
            require(!Files.exists(output)) { "Historical outcome file already exists" }
            val document = mapper.createObjectNode().apply {
                put("consumerSha", replay.consumerSha)
                put("providerSha", replay.providerSha)
                put("originalArtifactSha256", replay.originalArtifactSha256)
                put("brokerResponseSha256", replay.brokerResponseSha256)
                put("runId", input("GITHUB_RUN_ID"))
                putArray("interactions").apply {
                    replay.pact.interactions.forEachIndexed { index, interaction ->
                        addObject().put("index", index).put("description", interaction.description).put("passed", true)
                    }
                }
            }
            Files.writeString(output, document.toString(), java.nio.file.StandardOpenOption.CREATE_NEW)
        } finally {
            transport.close()
        }
    }
}
