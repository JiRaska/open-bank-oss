// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.w3c.dom.Element
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

class HostedFinrepEvidenceValidatorTest {
    private val mapper = ObjectMapper()
    private val expected = HistoricalFinrepPactExpectation("a".repeat(40), "b".repeat(40), "c".repeat(64), 2)
    private val descriptions = listOf("same description", "same description")
    private val runId = "123"
    private val buildUrl = URI("https://github.com/JiRaska/open-bank-oss/actions/runs/$runId")

    private data class Evidence(val proof: ObjectNode, val outcomes: ObjectNode, val suite: Element)

    private fun fixture(): Evidence {
        val proof = mapper.createObjectNode().apply {
            put("proofKind", "historical-finrep-main-postbuild-checkout")
            put("providerSha", expected.providerSha)
            put("consumerSha", expected.consumerSha)
            put("contentSha256", expected.contentSha256)
            put("interactionCount", 2)
            put("buildUrl", buildUrl.toString())
        }
        val outcomes = mapper.createObjectNode().apply {
            put("providerSha", expected.providerSha)
            put("consumerSha", expected.consumerSha)
            put("originalArtifactSha256", expected.contentSha256)
            put("runId", runId)
            putArray("interactions").apply {
                descriptions.forEachIndexed { index, description ->
                    addObject().put("index", index).put("description", description).put("passed", true)
                }
            }
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val suite = document.createElement("testsuite").apply {
            setAttribute("name", "com.openbank.ledger.contract.LedgerHostedHistoricalFinrepProviderVerificationTest")
            setAttribute("tests", "2")
            for (attribute in listOf("errors", "failures", "skipped")) setAttribute(attribute, "0")
            descriptions.forEachIndexed { index, description ->
                appendChild(
                    document.createElement("testcase").apply {
                        setAttribute("name", "${expected.consumerSha}:$index:$description")
                    },
                )
            }
        }
        document.appendChild(suite)
        return Evidence(proof, outcomes, suite)
    }

    private fun accept(evidence: Evidence) {
        HostedFinrepEvidenceValidator.identities(expected, evidence.proof, evidence.outcomes, runId, buildUrl)
        HostedFinrepEvidenceValidator.interactions(expected, descriptions, evidence.outcomes, evidence.suite)
    }

    @Test
    fun `valid proof accepts duplicate descriptions only with distinct exact version indices`() = accept(fixture())

    @TestFactory
    fun `invalid evidence cannot reach publication boundary`(): List<DynamicTest> {
        val mutations: List<Pair<String, (Evidence) -> Unit>> = listOf(
            "wrong provider" to { it.proof.put("providerSha", "d".repeat(40)) },
            "wrong consumer" to { it.outcomes.put("consumerSha", "d".repeat(40)) },
            "wrong run" to { it.outcomes.put("runId", "456") },
            "wrong original artifact" to { it.outcomes.put("originalArtifactSha256", "d".repeat(64)) },
            "duplicate index" to { (it.outcomes.path("interactions")[1] as ObjectNode).put("index", 0) },
            "missing outcome" to
                { (it.outcomes.path("interactions") as com.fasterxml.jackson.databind.node.ArrayNode).remove(1) },
            "failed outcome" to { (it.outcomes.path("interactions")[1] as ObjectNode).put("passed", false) },
            "wrong XML index" to
                {
                    (
                        it.suite.getElementsByTagName(
                            "testcase",
                        ).item(1) as Element
                        ).setAttribute("name", "${expected.consumerSha}:0:same description")
                },
            "missing XML case" to { it.suite.removeChild(it.suite.lastChild) },
            "skipped XML case" to { it.suite.lastChild.appendChild(it.suite.ownerDocument.createElement("skipped")) },
            "failed XML case" to { it.suite.lastChild.appendChild(it.suite.ownerDocument.createElement("failure")) },
            "error XML case" to { it.suite.lastChild.appendChild(it.suite.ownerDocument.createElement("error")) },
            "nonzero skipped" to { it.suite.setAttribute("skipped", "1") },
            "invalid numeric XML" to { it.suite.setAttribute("tests", "synthetic-private-secret") },
        )
        return mutations.map { (name, mutate) ->
            DynamicTest.dynamicTest(name) {
                val evidence = fixture().also(mutate)
                var reachedPublication = false
                val failure = assertThrows<IllegalArgumentException> {
                    accept(evidence)
                    reachedPublication = true
                }
                assertThat(reachedPublication).isFalse()
                val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
                assertThat(trace).doesNotContain("synthetic-private-secret")
                assertThat(failure.cause).isNull()
            }
        }
    }
}
