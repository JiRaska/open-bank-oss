// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.securityscanner.infrastructure.rest

import com.openbank.securityscanner.application.SecurityScannerService
import io.quarkus.scheduler.Scheduled
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType; import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

data class ServiceConfig(val name: String, val url: String, val port: Int)

@ConfigMapping(prefix = "openbank.security-scanner")
interface SecurityScannerConfig {
    @WithName("scan-interval-minutes") fun scanIntervalMinutes(): Int
    fun services(): List<ServiceEntry>
    interface ServiceEntry {
        fun name(): String
        fun url(): String
        fun port(): Int
    }
}

@ApplicationScoped
@Path("/api/v1/security")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Security Scanner", description = "OWASP Top 10 security scanning for all microservices")
class SecurityScannerResource(
    private val scanner: SecurityScannerService,
    private val config: SecurityScannerConfig
) {
    private fun serviceList() = config.services().map { it.name() to it.url() }

    @GET @Path("/report")
    @Operation(summary = "Get latest security scan report")
    fun getReport(): Response =
        scanner.getLastReport()?.let { Response.ok(it).build() }
            ?: Response.status(404).entity(mapOf("message" to "No scan completed yet. POST /api/v1/security/scan to trigger.")).build()

    @POST @Path("/scan")
    @Operation(summary = "Trigger a full security scan of all services")
    fun triggerScan(): Response = Response.ok(scanner.scanAll(serviceList())).build()

    @GET @Path("/services/{name}")
    @Operation(summary = "Get security scan result for a specific service")
    fun getServiceResult(@PathParam("name") name: String): Response =
        scanner.getServiceResult(name)?.let { Response.ok(it).build() }
            ?: Response.status(404).entity(mapOf("error" to "No scan result for service: $name")).build()

    @GET @Path("/services")
    @Operation(summary = "Get all service scan results")
    fun getAllResults(): Response = Response.ok(scanner.getAllResults()).build()

    @GET @Path("/info")
    @Operation(summary = "Security scanner info")
    fun info() = mapOf(
        "service" to "openbank-security-scanner",
        "version" to "0.1.0",
        "capabilities" to listOf("owasp-top10", "security-headers", "cors-check", "actuator-exposure", "tls-check"),
        "standards" to listOf("OWASP Top 10 2021", "EBA ICT Risk Guidelines", "PSD2 RTS", "NIST SP 800-53")
    )

    @Scheduled(every = "30m", delayed = "2m")
    fun scheduledScan() { scanner.scanAll(serviceList()) }
}
