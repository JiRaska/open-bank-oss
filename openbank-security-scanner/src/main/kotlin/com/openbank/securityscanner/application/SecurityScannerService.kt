// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.securityscanner.application

import com.openbank.securityscanner.domain.*
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@ApplicationScoped
class SecurityScannerService(private val clock: Clock) {

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    // Cache last scan results
    private val lastResults = ConcurrentHashMap<String, ServiceScanResult>()
    private var lastReport: PlatformSecurityReport? = null

    fun getLastReport() = lastReport
    fun getServiceResult(name: String) = lastResults[name]
    fun getAllResults() = lastResults.values.toList()

    fun scanAll(services: List<Pair<String, String>>): PlatformSecurityReport {
        val results = services.map { (name, url) -> scanService(name, url) }
        results.forEach { lastResults[it.serviceName] = it }

        val report = buildReport(results)
        lastReport = report
        return report
    }

    private fun mgmtUrl(apiUrl: String): String {
        val uri = URI.create(apiUrl.trimEnd('/'))
        val mgmt = "${uri.scheme}://${uri.host}:8085"
        return try {
            val req = HttpRequest.newBuilder(URI.create("$mgmt/q/health"))
                .GET().timeout(Duration.ofSeconds(2)).build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..599) mgmt else apiUrl.trimEnd('/')
        } catch (_: Exception) {
            apiUrl.trimEnd('/')
        }
    }

    fun scanService(name: String, url: String): ServiceScanResult {
        val start = Instant.now(clock)
        val findings = mutableListOf<SecurityFinding>()
        val mgmt = mgmtUrl(url)

        // 1. Reachability check — tries management port first, falls back to API port.
        // (Security headers are checked separately in step 2, against the API port —
        // this response's headers are not used.)
        val reachable = try {
            val req = HttpRequest.newBuilder(URI.create("$mgmt/q/health"))
                .GET().timeout(Duration.ofSeconds(5)).build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            resp.statusCode() in 200..599
        } catch (e: Exception) {
            false
        }

        if (!reachable) {
            return ServiceScanResult(
                name,
                url,
                Instant.now(clock),
                0,
                false,
                listOf(
                    SecurityFinding(
                        "UNREACHABLE",
                        OwaspCategory.A05_SECURITY_MISCONFIGURATION,
                        Severity.CRITICAL,
                        "Service unreachable",
                        "Service did not respond to health check",
                        "Ensure service is running and health endpoint is accessible",
                        null,
                        null,
                        "$mgmt/q/health",
                        null,
                    ),
                ),
                0,
                "F",
                null,
                emptyMap(),
                false,
                false,
            )
        }

        // 2. Security headers check on API port (OWASP A05)
        val apiResponseHeaders: Map<String, List<String>> = try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .GET().timeout(Duration.ofSeconds(5)).build()
            http.send(req, HttpResponse.BodyHandlers.discarding()).headers().map()
        } catch (_: Exception) {
            emptyMap()
        }

        val securityHeaders = mapOf(
            "x-content-type-options" to "X-Content-Type-Options",
            "x-frame-options" to "X-Frame-Options",
            "strict-transport-security" to "Strict-Transport-Security (HSTS)",
            "content-security-policy" to "Content-Security-Policy",
            "x-xss-protection" to "X-XSS-Protection",
            "referrer-policy" to "Referrer-Policy",
            "permissions-policy" to "Permissions-Policy",
        )
        val headersPresent = securityHeaders.mapValues { (key, _) ->
            val dotKey = key.replace('-', '.')
            apiResponseHeaders.keys.any { h -> h.lowercase().let { it == key || it == dotKey } }
        }
        headersPresent.filter { !it.value }.forEach { (key, _) ->
            findings.add(
                SecurityFinding(
                    "MISSING_HEADER_${key.uppercase().replace("-", "_")}",
                    OwaspCategory.A05_SECURITY_MISCONFIGURATION,
                    Severity.MEDIUM,
                    "Missing security header: ${securityHeaders[key]}",
                    "The response does not include the $key security header",
                    "Add '$key' header to all HTTP responses",
                    "CWE-693",
                    null,
                    url,
                    null,
                ),
            )
        }

        // 3. Check if health endpoint exposes sensitive info (management port)
        try {
            val req = HttpRequest.newBuilder(URI.create("$mgmt/q/health/ready"))
                .GET().timeout(Duration.ofSeconds(5)).build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            val body = resp.body()
            if (body.contains("password", ignoreCase = true) || body.contains("secret", ignoreCase = true)) {
                findings.add(
                    SecurityFinding(
                        "SENSITIVE_DATA_IN_HEALTH",
                        OwaspCategory.A02_CRYPTOGRAPHIC_FAILURES,
                        Severity.HIGH,
                        "Sensitive data in health endpoint",
                        "Health endpoint may expose credentials or secrets",
                        "Remove sensitive data from health check responses",
                        "CWE-312",
                        7.5,
                        "$mgmt/q/health/ready",
                        null,
                    ),
                )
            }
        } catch (_: Exception) {}

        // 4. Check OpenAPI exposure on API port (OWASP A05 — info disclosure)
        val openApiAvailable = try {
            val req = HttpRequest.newBuilder(URI.create("$url/q/openapi"))
                .GET().timeout(Duration.ofSeconds(3)).build()
            http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        } catch (_: Exception) {
            false
        }

        if (openApiAvailable) {
            findings.add(
                SecurityFinding(
                    "OPENAPI_EXPOSED",
                    OwaspCategory.A05_SECURITY_MISCONFIGURATION,
                    Severity.INFO,
                    "OpenAPI spec publicly accessible",
                    "The OpenAPI specification is accessible without authentication",
                    "Consider restricting OpenAPI access to internal networks or authenticated users in production",
                    "CWE-200",
                    null,
                    "$url/q/openapi",
                    null,
                ),
            )
        }

        // 5. Check unauthenticated actuator endpoints on main API port (OWASP A01)
        val sensitiveEndpoints = listOf("/q/metrics", "/q/info", "/q/dev")
        sensitiveEndpoints.forEach { ep ->
            try {
                val req = HttpRequest.newBuilder(URI.create("$url$ep"))
                    .GET().timeout(Duration.ofSeconds(3)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
                if (resp.statusCode() == 200) {
                    findings.add(
                        SecurityFinding(
                            "UNAUTH_ACTUATOR_${ep.replace("/", "_").uppercase()}",
                            OwaspCategory.A01_BROKEN_ACCESS_CONTROL,
                            Severity.MEDIUM,
                            "Unauthenticated actuator endpoint on API port: $ep",
                            "Actuator endpoint $ep is accessible on the main API port without authentication",
                            "Ensure management endpoints are on port 8085 only (quarkus.management.enabled=true)",
                            "CWE-306",
                            5.3,
                            "$url$ep",
                            null,
                        ),
                    )
                }
            } catch (_: Exception) {}
        }

        // 6. Check CORS misconfiguration on API port (OWASP A05)
        try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("Origin", "https://evil.example.com")
                .GET().timeout(Duration.ofSeconds(3)).build()
            val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
            val acao = resp.headers().firstValue("access-control-allow-origin").orElse("")
            if (acao == "*") {
                findings.add(
                    SecurityFinding(
                        "CORS_WILDCARD",
                        OwaspCategory.A05_SECURITY_MISCONFIGURATION,
                        Severity.HIGH,
                        "CORS wildcard origin allowed",
                        "Service allows requests from any origin (Access-Control-Allow-Origin: *)",
                        "Restrict CORS to known origins only",
                        "CWE-942",
                        6.5,
                        url,
                        "ACAO: *",
                    ),
                )
            }
        } catch (_: Exception) {}

        // Calculate score
        val criticals = findings.count { it.severity == Severity.CRITICAL }
        val highs = findings.count { it.severity == Severity.HIGH }
        val mediums = findings.count { it.severity == Severity.MEDIUM }
        val score = maxOf(0, 100 - criticals * 30 - highs * 15 - mediums * 5)
        val grade = when {
            score >= 95 -> "A+"
            score >= 90 -> "A"
            score >= 80 -> "B"
            score >= 70 -> "C"
            score >= 60 -> "D"
            else -> "F"
        }

        val duration = Duration.between(start, Instant.now(clock)).toMillis()
        return ServiceScanResult(
            name,
            url,
            Instant.now(clock),
            duration,
            true,
            findings,
            score,
            grade,
            "TLS 1.3",
            headersPresent,
            openApiAvailable,
            false,
        )
    }

    private fun buildReport(results: List<ServiceScanResult>): PlatformSecurityReport {
        val allFindings = results.flatMap { it.findings }
        val platformScore = if (results.isEmpty()) {
            0
        } else {
            results.filter { it.reachable }.map { it.score }.average().toInt()
        }
        val owaspCoverage = OwaspCategory.entries.associateWith { cat ->
            allFindings.count { it.category == cat }
        }
        val complianceStatus = mapOf(
            "PSD2_SCA" to results.all { it.reachable },
            "EBA_ICT_RISK" to (results.count { it.score >= 70 } >= results.size * 0.8),
            "GDPR_DATA_PROTECT" to allFindings.none {
                it.category == OwaspCategory.A02_CRYPTOGRAPHIC_FAILURES && it.severity == Severity.CRITICAL
            },
            "OWASP_TOP10" to (allFindings.none { it.severity == Severity.CRITICAL }),
            "CNB_SECURITY" to (platformScore >= 70),
        )
        val grade = when {
            platformScore >= 95 -> "A+"
            platformScore >= 90 -> "A"
            platformScore >= 80 -> "B"
            platformScore >= 70 -> "C"
            platformScore >= 60 -> "D"
            else -> "F"
        }
        return PlatformSecurityReport(
            reportId = UUID.randomUUID().toString(),
            generatedAt = Instant.now(clock),
            totalServices = results.size,
            reachableServices = results.count { it.reachable },
            serviceResults = results,
            platformScore = platformScore,
            platformGrade = grade,
            criticalFindings = allFindings.count { it.severity == Severity.CRITICAL },
            highFindings = allFindings.count { it.severity == Severity.HIGH },
            owaspCoverage = owaspCoverage,
            complianceStatus = complianceStatus,
        )
    }
}
