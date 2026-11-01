// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import com.fasterxml.jackson.databind.JsonNode
import org.w3c.dom.Element
import java.net.URI

/** Acceptance boundary shared by the actual publisher and controlled negative evidence tests. */
internal object HostedFinrepEvidenceValidator {
    fun identities(
        expected: HistoricalFinrepPactExpectation,
        proof: JsonNode,
        outcomes: JsonNode,
        runId: String,
        buildUrl: URI,
    ) {
        require(proof.path("proofKind").asText() == "historical-finrep-main-postbuild-checkout")
        require(proof.path("providerSha").asText() == expected.providerSha)
        require(proof.path("consumerSha").asText() == expected.consumerSha)
        require(proof.path("contentSha256").asText() == expected.contentSha256)
        require(
            proof.path("interactionCount").isIntegralNumber &&
                proof.path("interactionCount").canConvertToInt() &&
                proof.path("interactionCount").asInt() == expected.interactionCount,
        )
        require(proof.path("buildUrl").asText() == buildUrl.toString())
        require(outcomes.path("providerSha").asText() == expected.providerSha)
        require(outcomes.path("consumerSha").asText() == expected.consumerSha)
        require(outcomes.path("originalArtifactSha256").asText() == expected.contentSha256)
        require(outcomes.path("runId").asText() == runId)
    }

    fun interactions(
        expected: HistoricalFinrepPactExpectation,
        descriptions: List<String>,
        outcomes: JsonNode,
        suite: Element,
    ) {
        require(
            suite.getAttribute("name") ==
                "com.openbank.ledger.contract.LedgerHostedHistoricalFinrepProviderVerificationTest",
        )
        require(suite.getAttribute("tests").toIntOrNull() == expected.interactionCount) {
            "Historical XML test count is invalid"
        }
        for (attribute in listOf("failures", "errors", "skipped")) {
            require(suite.getAttribute(attribute).toIntOrNull() == 0) {
                "Historical XML has invalid or incomplete outcomes"
            }
        }
        val entries = outcomes.path("interactions")
        require(entries.isArray && entries.size() == expected.interactionCount)
        val cases = suite.getElementsByTagName("testcase")
        require(cases.length == expected.interactionCount && descriptions.size == expected.interactionCount)
        descriptions.forEachIndexed { index, description ->
            val entry = entries[index]
            require(
                entry.path("index").isIntegralNumber &&
                    entry.path("index").canConvertToInt() &&
                    entry.path("index").asInt(-1) == index,
            )
            require(entry.path("passed").isBoolean && entry.path("passed").booleanValue())
            require(entry.path("description").asText() == description)
            val test = cases.item(index) as Element
            require(test.getAttribute("name") == "${expected.consumerSha}:$index:$description")
            for (tag in listOf("skipped", "failure", "error")) require(test.getElementsByTagName(tag).length == 0)
        }
    }
}
