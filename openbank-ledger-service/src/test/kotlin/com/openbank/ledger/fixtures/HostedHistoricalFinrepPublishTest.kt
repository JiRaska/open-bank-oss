// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Separate opt-in post-run publication; never depends on or reruns provider tests. */
@EnabledIfEnvironmentVariable(named = "FINREP_PUBLICATION_ENABLED", matches = "true")
class HostedHistoricalFinrepPublishTest {
    private val mapper = ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    private fun json(path: String): JsonNode = FinrepEvidenceDocuments.json(Path.of(HostedFinrepRun.input(path)))

    @Test
    fun `publish exactly once after complete final XML and checkout revalidation`() {
        require(System.getProperty("pact.verifier.publishResults") == "false")
        require(HostedFinrepRun.input("GITHUB_RUN_ATTEMPT") == "1") { "Ambiguous publication runs must not be rerun" }
        val expected = HostedFinrepRun.expectation()
        val proof = json("FINREP_POSTBUILD_PROOF")
        val buildUrl =
            URI("https://github.com/JiRaska/open-bank-oss/actions/runs/${HostedFinrepRun.input("GITHUB_RUN_ID")}")
        val outcomes = json("FINREP_OUTCOMES")
        HostedFinrepEvidenceValidator.identities(
            expected,
            proof,
            outcomes,
            HostedFinrepRun.input("GITHUB_RUN_ID"),
            buildUrl,
        )
        val xml = Path.of(HostedFinrepRun.input("FINREP_FINAL_XML"))
        val suite = FinrepEvidenceDocuments.xml(xml).documentElement
        HostedFinrepRun.open()
        try {
            val replay = HostedFinrepRun.replay
            require(outcomes.path("brokerResponseSha256").asText() == replay.brokerResponseSha256)
            HostedFinrepEvidenceValidator.interactions(
                expected,
                replay.pact.interactions.map {
                    it.description
                },
                outcomes,
                suite,
            )
            replay.pact.interactions.indices.forEach { replay.record(it, true) }
            FinrepFreshCheckoutProof.verify(proof)
            // Claim is written BEFORE POST; an ambiguous network/readback outcome cannot be retried.
            Files.writeString(
                Path.of(HostedFinrepRun.input("FINREP_PUBLICATION_ATTEMPT")),
                "attempted",
                StandardOpenOption.CREATE_NEW,
            )
            val receipt = replay.publish(HostedFinrepRun.publisher(buildUrl), expected.providerSha, enabled = true)
            Files.writeString(
                Path.of(HostedFinrepRun.input("FINREP_PUBLIC_EVIDENCE")),
                mapper.writeValueAsString(receipt),
                StandardOpenOption.CREATE_NEW,
            )
        } finally {
            HostedFinrepRun.close()
        }
    }
}
