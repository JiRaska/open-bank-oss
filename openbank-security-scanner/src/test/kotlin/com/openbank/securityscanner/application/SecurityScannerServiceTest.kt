// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.securityscanner.application

import com.openbank.securityscanner.domain.OwaspCategory
import com.openbank.securityscanner.domain.PlatformSecurityReport
import com.openbank.securityscanner.domain.SecurityFinding
import com.openbank.securityscanner.domain.ServiceScanResult
import com.openbank.securityscanner.domain.Severity
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.time.Clock
import java.time.Instant

class SecurityScannerServiceTest {

    private val service = SecurityScannerService(Clock.systemUTC())

    @Test
    fun `service starts with empty caches`() {
        assertThat(service.getLastReport()).isNull()
        assertThat(service.getAllResults()).isEmpty()
        assertThat(service.getServiceResult("missing")).isNull()
    }

    @Test
    fun `service scan result exposes finding counts`() {
        val result = ServiceScanResult(
            serviceName = "account-service",
            serviceUrl = "http://localhost:8100",
            scannedAt = Instant.parse("2026-01-01T00:00:00Z"),
            durationMs = 25,
            reachable = true,
            findings = listOf(
                finding("critical", Severity.CRITICAL, OwaspCategory.A01_BROKEN_ACCESS_CONTROL),
                finding("high", Severity.HIGH, OwaspCategory.A05_SECURITY_MISCONFIGURATION),
                finding("medium", Severity.MEDIUM, OwaspCategory.A05_SECURITY_MISCONFIGURATION),
                finding("info", Severity.INFO, OwaspCategory.A05_SECURITY_MISCONFIGURATION)
            ),
            score = 55,
            grade = "D",
            tlsVersion = "TLS 1.3",
            headersPresent = emptyMap(),
            openApiAvailable = false,
            healthEndpointSecured = false
        )

        assertThat(result.criticalCount).isEqualTo(1)
        assertThat(result.highCount).isEqualTo(1)
        assertThat(result.mediumCount).isEqualTo(1)
    }

    @Test
    fun `build report aggregates score grade and compliance`() {
        val results = listOf(
            serviceResult(
                serviceName = "svc-a",
                score = 92,
                reachable = true,
                findings = listOf(
                    finding("f1", Severity.HIGH, OwaspCategory.A05_SECURITY_MISCONFIGURATION),
                    finding("f2", Severity.MEDIUM, OwaspCategory.A01_BROKEN_ACCESS_CONTROL)
                )
            ),
            serviceResult(
                serviceName = "svc-b",
                score = 68,
                reachable = true,
                findings = listOf(
                    finding("f3", Severity.INFO, OwaspCategory.A02_CRYPTOGRAPHIC_FAILURES)
                )
            )
        )

        val report = invokeBuildReport(results)

        assertThat(report.totalServices).isEqualTo(2)
        assertThat(report.reachableServices).isEqualTo(2)
        assertThat(report.platformScore).isEqualTo(80)
        assertThat(report.platformGrade).isEqualTo("B")
        assertThat(report.criticalFindings).isZero()
        assertThat(report.highFindings).isEqualTo(1)
        assertThat(report.owaspCoverage)
            .containsEntry(OwaspCategory.A01_BROKEN_ACCESS_CONTROL, 1)
            .containsEntry(OwaspCategory.A02_CRYPTOGRAPHIC_FAILURES, 1)
            .containsEntry(OwaspCategory.A05_SECURITY_MISCONFIGURATION, 1)
        assertThat(report.complianceStatus["PSD2_SCA"]).isTrue()
        assertThat(report.complianceStatus["OWASP_TOP10"]).isTrue()
        assertThat(report.complianceStatus["CNB_SECURITY"]).isTrue()
    }

    @Test
    fun `build report handles empty input`() {
        val report = invokeBuildReport(emptyList())

        assertThat(report.totalServices).isZero()
        assertThat(report.reachableServices).isZero()
        assertThat(report.platformScore).isZero()
        assertThat(report.platformGrade).isEqualTo("F")
        assertThat(report.serviceResults).isEmpty()
        assertThat(report.owaspCoverage.values).allMatch { it == 0 }
    }

    @Test
    fun `service result aliases data class behavior`() {
        val finding = mockk<SecurityFinding>(relaxed = true)
        val result = ServiceScanResult(
            serviceName = "svc",
            serviceUrl = "http://localhost",
            scannedAt = Instant.parse("2026-01-01T00:00:00Z"),
            durationMs = 1,
            reachable = true,
            findings = listOf(finding),
            score = 100,
            grade = "A+",
            tlsVersion = null,
            headersPresent = mapOf("x-frame-options" to true),
            openApiAvailable = false,
            healthEndpointSecured = false
        )

        assertThat(result.findings).hasSize(1)
        assertThat(result.findings[0]).isSameAs(finding)
        assertThat(result.score).isEqualTo(100)
        assertThat(result.grade).isEqualTo("A+")
    }

    private fun finding(id: String, severity: Severity, category: OwaspCategory) =
        SecurityFinding(
            id = id,
            category = category,
            severity = severity,
            title = id,
            description = id,
            remediation = id,
            cweId = null,
            cvssScore = null,
            endpoint = null,
            evidence = null
        )

    private fun serviceResult(
        serviceName: String,
        score: Int,
        reachable: Boolean,
        findings: List<SecurityFinding>
    ) = ServiceScanResult(
        serviceName = serviceName,
        serviceUrl = "http://localhost:8100",
        scannedAt = Instant.parse("2026-01-01T00:00:00Z"),
        durationMs = 10,
        reachable = reachable,
        findings = findings,
        score = score,
        grade = if (score >= 95) "A+" else if (score >= 90) "A" else if (score >= 80) "B" else if (score >= 70) "C" else if (score >= 60) "D" else "F",
        tlsVersion = "TLS 1.3",
        headersPresent = emptyMap(),
        openApiAvailable = false,
        healthEndpointSecured = false
    )

    private fun invokeBuildReport(results: List<ServiceScanResult>): PlatformSecurityReport {
        val method: Method = SecurityScannerService::class.java.getDeclaredMethod("buildReport", List::class.java)
        method.isAccessible = true
        return method.invoke(service, results) as PlatformSecurityReport
    }
}
