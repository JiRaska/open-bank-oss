// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.application

import com.openbank.securityscanner.domain.OwaspCategory
import com.openbank.securityscanner.domain.Severity
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Drives [SecurityScannerService.scanService] against a real, in-JVM [HttpServer] (JDK only — no
 * Quarkus, no Testcontainers). A mocked HTTP client could not exercise these branches at all: the
 * service builds its own [java.net.http.HttpClient] in a field initializer, so the only way to make
 * the probes take a given branch is to answer them.
 *
 * The management-port probe (`mgmtUrl`) targets port 8085 unconditionally; nothing listens there in
 * a unit-test JVM, so it falls back to the API URL and every probe lands on the stub below. That
 * fallback is itself one of the branches under test.
 */
class SecurityScannerScanServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-02-01T12:00:00Z"), ZoneOffset.UTC)
    private val service = SecurityScannerService(clock)

    @BeforeEach
    fun resetStub() {
        stub.reset()
    }

    @Test
    fun `a bare service reports one missing-header finding per required header`() {
        val result = service.scanService("account-service", baseUrl)

        assertThat(result.reachable).isTrue()
        assertThat(result.findings).hasSize(REQUIRED_HEADERS.size)
        val expectedIds = REQUIRED_HEADERS.map { "MISSING_HEADER_" + it.uppercase().replace("-", "_") }
        assertThat(result.findings.map { it.id }).containsExactlyInAnyOrderElementsOf(expectedIds)
        assertThat(result.findings).allSatisfy {
            assertThat(it.severity).isEqualTo(Severity.MEDIUM)
            assertThat(it.category).isEqualTo(OwaspCategory.A05_SECURITY_MISCONFIGURATION)
            assertThat(it.cweId).isEqualTo("CWE-693")
        }
        assertThat(result.headersPresent.values).containsOnly(false)
        // 7 mediums at -5 each.
        assertThat(result.score).isEqualTo(65)
        assertThat(result.grade).isEqualTo("D")
        assertThat(result.tlsVersion).isEqualTo("TLS 1.3")
        assertThat(result.openApiAvailable).isFalse()
        assertThat(result.serviceName).isEqualTo("account-service")
    }

    @Test
    fun `all security headers present yields a clean A plus result`() {
        REQUIRED_HEADERS.forEach { stub.extraHeaders[it] = "on" }

        val result = service.scanService("account-service", baseUrl)

        assertThat(result.findings).isEmpty()
        assertThat(result.headersPresent.values).containsOnly(true)
        assertThat(result.score).isEqualTo(100)
        assertThat(result.grade).isEqualTo("A+")
    }

    @Test
    fun `header detection is case-insensitive on the wire`() {
        stub.extraHeaders["X-Frame-Options"] = "DENY"

        val result = service.scanService("account-service", baseUrl)

        assertThat(result.headersPresent["x-frame-options"]).isTrue()
        assertThat(result.findings.map { it.id }).doesNotContain("MISSING_HEADER_X_FRAME_OPTIONS")
        assertThat(result.findings).hasSize(REQUIRED_HEADERS.size - 1)
    }

    @Test
    fun `a health-ready body mentioning a secret raises a HIGH cryptographic finding`() {
        stub.healthReadyBody = """{"status":"UP","datasource":{"SECRET":"redacted?"}}"""

        val result = service.scanService("account-service", baseUrl)

        val finding = result.findings.single { it.id == "SENSITIVE_DATA_IN_HEALTH" }
        assertThat(finding.severity).isEqualTo(Severity.HIGH)
        assertThat(finding.category).isEqualTo(OwaspCategory.A02_CRYPTOGRAPHIC_FAILURES)
        assertThat(finding.cvssScore).isEqualTo(7.5)
        assertThat(finding.cweId).isEqualTo("CWE-312")
        // 7 mediums + 1 high.
        assertThat(result.score).isEqualTo(50)
        assertThat(result.grade).isEqualTo("F")
    }

    @Test
    fun `a health-ready body with no credential words raises nothing`() {
        stub.healthReadyBody = """{"status":"UP","checks":[{"name":"Database connections health check"}]}"""

        val result = service.scanService("account-service", baseUrl)

        assertThat(result.findings.map { it.id }).doesNotContain("SENSITIVE_DATA_IN_HEALTH")
    }

    @Test
    fun `an exposed OpenAPI document is INFO only and does not move the score`() {
        stub.openApiStatus = 200

        val result = service.scanService("account-service", baseUrl)

        val finding = result.findings.single { it.id == "OPENAPI_EXPOSED" }
        assertThat(finding.severity).isEqualTo(Severity.INFO)
        assertThat(finding.endpoint).isEqualTo("$baseUrl/q/openapi")
        assertThat(result.openApiAvailable).isTrue()
        assertThat(result.score).isEqualTo(65)
    }

    @Test
    fun `a non-200 OpenAPI response is not an exposure`() {
        stub.openApiStatus = 401

        val result = service.scanService("account-service", baseUrl)

        assertThat(result.openApiAvailable).isFalse()
        assertThat(result.findings.map { it.id }).doesNotContain("OPENAPI_EXPOSED")
    }

    @Test
    fun `actuator endpoints answering 200 on the API port are broken access control`() {
        stub.openActuators += setOf("/q/metrics", "/q/info")

        val result = service.scanService("account-service", baseUrl)

        assertThat(result.findings.map { it.id })
            .contains("UNAUTH_ACTUATOR__Q_METRICS", "UNAUTH_ACTUATOR__Q_INFO")
            .doesNotContain("UNAUTH_ACTUATOR__Q_DEV")
        val metrics = result.findings.single { it.id == "UNAUTH_ACTUATOR__Q_METRICS" }
        assertThat(metrics.category).isEqualTo(OwaspCategory.A01_BROKEN_ACCESS_CONTROL)
        assertThat(metrics.severity).isEqualTo(Severity.MEDIUM)
        assertThat(metrics.cvssScore).isEqualTo(5.3)
        // 7 header mediums + 2 actuator mediums.
        assertThat(result.score).isEqualTo(55)
    }

    @Test
    fun `a wildcard CORS origin is a HIGH finding`() {
        stub.accessControlAllowOrigin = "*"

        val result = service.scanService("account-service", baseUrl)

        val finding = result.findings.single { it.id == "CORS_WILDCARD" }
        assertThat(finding.severity).isEqualTo(Severity.HIGH)
        assertThat(finding.evidence).isEqualTo("ACAO: *")
        assertThat(finding.cweId).isEqualTo("CWE-942")
    }

    @Test
    fun `an echoed origin is not treated as a wildcard`() {
        stub.accessControlAllowOrigin = "https://evil.example.com"

        val result = service.scanService("account-service", baseUrl)

        assertThat(result.findings.map { it.id }).doesNotContain("CORS_WILDCARD")
    }

    @Test
    fun `an unreachable service short-circuits to a single critical finding`() {
        val result = service.scanService("dead-service", deadUrl)

        assertThat(result.reachable).isFalse()
        assertThat(result.score).isZero()
        assertThat(result.grade).isEqualTo("F")
        assertThat(result.headersPresent).isEmpty()
        assertThat(result.openApiAvailable).isFalse()
        assertThat(result.tlsVersion).isNull()
        assertThat(result.durationMs).isZero()
        val finding = result.findings.single()
        assertThat(finding.id).isEqualTo("UNREACHABLE")
        assertThat(finding.severity).isEqualTo(Severity.CRITICAL)
        assertThat(finding.endpoint).isEqualTo("$deadUrl/q/health")
    }

    @Test
    fun `scanAll caches per-service results and the platform report`() {
        val report = service.scanAll(listOf("account-service" to baseUrl, "dead-service" to deadUrl))

        assertThat(report.totalServices).isEqualTo(2)
        assertThat(report.reachableServices).isEqualTo(1)
        assertThat(report.criticalFindings).isEqualTo(1)
        // Platform score averages the REACHABLE services only, so the dead one does not drag it to 32.
        assertThat(report.platformScore).isEqualTo(65)
        assertThat(report.platformGrade).isEqualTo("D")
        assertThat(report.complianceStatus["PSD2_SCA"]).isFalse()
        assertThat(report.complianceStatus["OWASP_TOP10"]).isFalse()
        assertThat(report.complianceStatus["CNB_SECURITY"]).isFalse()
        assertThat(report.owaspCoverage[OwaspCategory.A05_SECURITY_MISCONFIGURATION])
            .isEqualTo(REQUIRED_HEADERS.size + 1)
        assertThat(report.generatedAt).isEqualTo(Instant.now(clock))

        assertThat(service.getLastReport()).isSameAs(report)
        assertThat(service.getServiceResult("account-service")?.reachable).isTrue()
        assertThat(service.getServiceResult("dead-service")?.reachable).isFalse()
        assertThat(service.getAllResults()).hasSize(2)
    }

    private class Stub {
        val extraHeaders = mutableMapOf<String, String>()
        var healthReadyBody = """{"status":"UP"}"""
        var openApiStatus = 404
        var openActuators = mutableSetOf<String>()
        var accessControlAllowOrigin: String? = null

        fun reset() {
            extraHeaders.clear()
            healthReadyBody = """{"status":"UP"}"""
            openApiStatus = 404
            openActuators = mutableSetOf()
            accessControlAllowOrigin = null
        }

        fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val (status, body) = when {
                path == "/q/health" -> 200 to """{"status":"UP"}"""
                path == "/q/health/ready" -> 200 to healthReadyBody
                path == "/q/openapi" -> openApiStatus to "openapi: 3.0.3"
                path in openActuators -> 200 to "exposed"
                path.startsWith("/q/") -> 404 to "not found"
                else -> {
                    extraHeaders.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
                    accessControlAllowOrigin?.let { exchange.responseHeaders.add("Access-Control-Allow-Origin", it) }
                    200 to "root"
                }
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        private val REQUIRED_HEADERS = listOf(
            "x-content-type-options",
            "x-frame-options",
            "strict-transport-security",
            "content-security-policy",
            "x-xss-protection",
            "referrer-policy",
            "permissions-policy",
        )

        private val stub = Stub()
        private lateinit var server: HttpServer
        private lateinit var baseUrl: String
        private lateinit var deadUrl: String

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { stub.handle(it) }
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}"
            // A port that was bound and released: nothing answers, so connect() is refused fast.
            val closed = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
            val deadPort = closed.localPort
            closed.close()
            deadUrl = "http://127.0.0.1:$deadPort"
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            server.stop(0)
        }
    }
}
