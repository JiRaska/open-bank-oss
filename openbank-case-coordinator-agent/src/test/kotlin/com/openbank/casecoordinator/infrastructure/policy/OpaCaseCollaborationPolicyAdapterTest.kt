// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.policy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.casecoordinator.application.port.out.CaseCollaborationPolicyQuery
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class OpaCaseCollaborationPolicyAdapterTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `posts the dedicated flat case decision input and preserves OPA provenance`() {
        val body = AtomicReference<String>()
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/data/openbank/case_collaboration/decision") { exchange ->
                body.set(exchange.requestBody.bufferedReader().readText())
                val response = """
                    {"decision_id":"decision-42","result":{"allow":true,
                    "reason":"allowed by charter and rules matrix","rollout_id":"shadow-1",
                    "max_signals_per_case":8}}
                """.trimIndent().toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val adapter = OpaCaseCollaborationPolicyAdapter(
            mapper = jacksonObjectMapper(),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            queryPath = "/v1/data/openbank/case_collaboration/decision",
            timeoutMs = 1_000,
        )

        val decision = adapter.decide(
            CaseCollaborationPolicyQuery(
                "rca-investigator",
                "case.contribute",
                "incident-response",
                "SHADOW",
            ),
        )

        assertThat(decision.allow).isTrue()
        assertThat(decision.decisionId).isEqualTo("decision-42")
        assertThat(decision.rolloutId).isEqualTo("shadow-1")
        assertThat(decision.maxSignalsPerCase).isEqualTo(8)
        val input = jacksonObjectMapper().readTree(body.get()).path("input")
        assertThat(input.path("agent").asText()).isEqualTo("rca-investigator")
        assertThat(input.path("case_class").asText()).isEqualTo("incident-response")
        assertThat(input.path("delivery_mode").asText()).isEqualTo("SHADOW")
    }
}
