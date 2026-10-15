// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * ADR-0031 D9 phase 4: the service adapter keeps phase-3's behaviour while the two policy
 * layers live in openbank-agent-policy. The kill-switch test is pure unit; the enabled-gate
 * tests run the REAL shared gate (charter verifier against the packaged agents.yaml + the real
 * OPA client) against a loopback OPA stub, so the adapter's wiring — agent id, tool, resource,
 * tier, OPA URL and timeout from FlakyTestHunterConfig — is exercised end to end. The
 * layer-by-layer composition matrix (charter short-circuit, opa_denied prefix, pdp_unreachable)
 * lives in openbank-agent-policy's AgentPolicyGateTest; duplicating it here would test the lib.
 */
class PrPathPolicyGateTest {

    private var server: HttpServer? = null
    private val objectMapper = ObjectMapper()
    private var lastInput: JsonNode? = null

    private val finding = FlakyTestFinding(
        id = "abcdef1234567890",
        checkType = FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.now(),
        title = "SomeTest.kt:12 uses the unsafe '= runBlocking {' form without ': Unit'",
        component = "openbank-flaky-test-hunter/src/test/kotlin/com/openbank/flakytest/SomeTest.kt",
        filePath = "openbank-flaky-test-hunter/src/test/kotlin/com/openbank/flakytest/SomeTest.kt",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
    )

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun startOpaStub(status: Int, body: String): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/data/openbank/agents/decision") { ex: HttpExchange ->
            lastInput = objectMapper.readTree(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.start()
        server = srv
        return "http://127.0.0.1:${srv.address.port}"
    }

    private fun gateFor(opaUrl: String, enabled: Boolean): PrPathPolicyGate {
        val gateConfig = mockk<FlakyTestHunterConfig.PolicyGate> {
            every { enabled() } returns enabled
            every { opaUrl() } returns opaUrl
            every { timeout() } returns Duration.ofSeconds(2)
        }
        val config = mockk<FlakyTestHunterConfig> {
            every { policyGate() } returns gateConfig
        }
        return PrPathPolicyGate(config)
    }

    @Test
    fun `a disabled gate allows without consulting the charter or the sidecar`() {
        val gate = gateFor(opaUrl = "http://127.0.0.1:1", enabled = false)

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(PrPathDecision.Allowed("flaky-test-hunter/unit-return-abcdef12"))
        assertThat(lastInput).isNull()
    }

    @Test
    fun `an enabled gate with a chartered grant and an allowing OPA decision allows`() {
        val baseUrl = startOpaStub(200, """{"result":{"allow":true,"reason":"allowed by charter"}}""")
        val gate = gateFor(opaUrl = baseUrl, enabled = true)

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(PrPathDecision.Allowed("flaky-test-hunter/unit-return-abcdef12"))
        val input = lastInput!!.path("input")
        assertThat(input.path("agent").asText()).isEqualTo("flaky-test-hunter")
        assertThat(input.path("tool").asText()).isEqualTo("gh.pr.open")
        assertThat(input.path("resource").asText()).isEqualTo("github-pr")
        assertThat(input.path("tier").asText()).isEqualTo("write_proposal")
    }

    @Test
    fun `an OPA policy deny carries the OPA reason`() {
        val baseUrl = startOpaStub(200, """{"result":{"allow":false,"reason":"no matching allow rule"}}""")
        val gate = gateFor(opaUrl = baseUrl, enabled = true)

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(PrPathDecision.Denied("opa_denied: no matching allow rule"))
    }

    @Test
    fun `an unreachable sidecar fails closed with pdp_unreachable`() {
        val gate = gateFor(opaUrl = "http://127.0.0.1:1", enabled = true)

        val decision = runBlocking { gate.decide(finding) }

        assertThat(decision).isEqualTo(PrPathDecision.Denied("pdp_unreachable"))
    }
}
