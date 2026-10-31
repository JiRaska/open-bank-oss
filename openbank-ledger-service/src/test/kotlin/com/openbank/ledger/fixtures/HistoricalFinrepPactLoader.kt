// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.model.PactSource
import au.com.dius.pact.provider.junitsupport.loader.PactLoader
import au.com.dius.pact.provider.junitsupport.loader.PactUrlLoader
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Official file-URL loader behind explicit offline artifact validation. Never publishes to a broker. */
// Pact requires a reflective Class constructor; artifact properties supply its inputs.
@Suppress("UnusedPrivateProperty")
class HistoricalFinrepPactLoader(testClass: Class<*>) : PactLoader {
    private lateinit var delegate: PactUrlLoader

    override fun description(): String = "validated offline historical FINREP Pact (not broker verification)"

    override fun load(providerName: String): List<Pact> {
        require(providerName == HistoricalFinrepPactArtifact.PROVIDER)
        require(System.getProperty("pact.verifier.publishResults") == "false") {
            "Offline replay cannot publish verification"
        }
        val file = Path.of(requireNotNull(System.getProperty("finrep.historical.pactFile")))
        val manifest = Path.of(requireNotNull(System.getProperty("finrep.historical.manifestFile")))
        val originFile = Path.of(requireNotNull(System.getProperty("finrep.historical.trustedBrokerOriginFile")))
        require(Files.isRegularFile(originFile) && !Files.isSymbolicLink(originFile) && Files.size(originFile) <= 8192)
        val origin = URI(Files.readString(originFile).trim())
        val expected = HistoricalFinrepPactExpectation(
            requireNotNull(System.getProperty("finrep.historical.consumerSha")),
            requireNotNull(System.getProperty("finrep.historical.providerSha")),
            requireNotNull(System.getProperty("finrep.historical.contentSha256")),
            requireNotNull(System.getProperty("finrep.historical.interactionCount")).toInt(),
        )
        val artifact = HistoricalFinrepPactArtifact.validate(file, manifest, origin, expected)
        require(artifact.providerSha == System.getProperty("finrep.historical.providerSha")) {
            "Provider commit differs from ancestry proof"
        }
        val frozenFile = Files.createTempFile("validated-historical-finrep-", ".json")
        frozenFile.toFile().deleteOnExit()
        Files.write(frozenFile, artifact.bytes)
        delegate = PactUrlLoader(arrayOf(frozenFile.toUri().toString()))
        val pacts = delegate.load(providerName)
        require(pacts.size == 1 && pacts.single().interactions.size == artifact.interactionCount) {
            "Loader omitted Pact interactions"
        }
        return pacts
    }

    override fun getPactSource(): PactSource = delegate.pactSource
}
