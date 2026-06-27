// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import com.openbank.libs.util.BuildInfo
import jakarta.annotation.security.PermitAll
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.OffsetDateTime

@Path("/api/v1/info")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
class ServiceInfoResource(
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "openbank-service")
    private val serviceName: String,

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "0.0.0")
    private val serviceVersion: String,

    @ConfigProperty(name = "openbank.api.version", defaultValue = "1")
    private val apiVersion: String,

    private val clock: Clock,
) {
    @GET
    @PermitAll
    fun info(): Response = Response.ok(
        ServiceInfo(
            service = serviceName,
            version = serviceVersion,
            apiVersion = "v$apiVersion",
            // BuildInfo values come from the libs JAR's openbank-build-info.properties,
            // stamped at build time from libs.versions.toml. Loaded once per JVM.
            buildTime = BuildInfo.buildTime,
            gitCommit = BuildInfo.gitCommit,
            timestamp = OffsetDateTime.now(clock).toString(),
            status = "UP",
            stack = BuildInfo.toStack(),
        ),
    ).header("X-API-Version", "v$apiVersion") // contract major (ADR-0048)
        .header("X-Service-Version", serviceVersion) // release SemVer (ADR-0048)
        .header("X-Service-Name", serviceName)
        .build()
}

data class ServiceInfo(
    val service: String,
    val version: String,
    val apiVersion: String,
    val buildTime: String,
    val gitCommit: String,
    val timestamp: String,
    val status: String,
    /** Tech-stack snapshot: kotlin / quarkus / java / gradle / libs metadata. */
    val stack: Map<String, Any>,
)
