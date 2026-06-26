// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.securityscanner.domain

import java.time.Instant

enum class Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
enum class OwaspCategory {
    A01_BROKEN_ACCESS_CONTROL,
    A02_CRYPTOGRAPHIC_FAILURES,
    A03_INJECTION,
    A04_INSECURE_DESIGN,
    A05_SECURITY_MISCONFIGURATION,
    A06_VULNERABLE_COMPONENTS,
    A07_AUTH_FAILURES,
    A08_SOFTWARE_INTEGRITY_FAILURES,
    A09_LOGGING_MONITORING_FAILURES,
    A10_SSRF
}

data class SecurityFinding(
    val id: String,
    val category: OwaspCategory,
    val severity: Severity,
    val title: String,
    val description: String,
    val remediation: String,
    val cweId: String?,
    val cvssScore: Double?,
    val endpoint: String?,
    val evidence: String?
)

data class ServiceScanResult(
    val serviceName: String,
    val serviceUrl: String,
    val scannedAt: Instant,
    val durationMs: Long,
    val reachable: Boolean,
    val findings: List<SecurityFinding>,
    val score: Int,           // 0-100, higher = more secure
    val grade: String,        // A+ A B C D F
    val tlsVersion: String?,
    val headersPresent: Map<String, Boolean>,
    val openApiAvailable: Boolean,
    val healthEndpointSecured: Boolean
) {
    val criticalCount get() = findings.count { it.severity == Severity.CRITICAL }
    val highCount     get() = findings.count { it.severity == Severity.HIGH }
    val mediumCount   get() = findings.count { it.severity == Severity.MEDIUM }
}

data class PlatformSecurityReport(
    val reportId: String,
    val generatedAt: Instant,
    val totalServices: Int,
    val reachableServices: Int,
    val serviceResults: List<ServiceScanResult>,
    val platformScore: Int,
    val platformGrade: String,
    val criticalFindings: Int,
    val highFindings: Int,
    val owaspCoverage: Map<OwaspCategory, Int>,  // category -> finding count
    val complianceStatus: Map<String, Boolean>   // PSD2, EBA, GDPR checks
)
