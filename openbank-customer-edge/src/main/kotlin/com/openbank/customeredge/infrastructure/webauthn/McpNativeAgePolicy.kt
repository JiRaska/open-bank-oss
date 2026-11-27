// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response.Status
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Consult Party only for an authenticated owner during the final credential-age check. */
@ApplicationScoped
internal class McpNativeAgePolicy(
    private val upstream: UpstreamClient,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.edge.party-service-url") private val partyService: String,
    @ConfigProperty(name = "openbank.edge.sandbox-mcp-native-age-exceptions", defaultValue = "false")
    private val enabled: Boolean,
) {
    fun eligible(party: UUID, firstVerified: Instant, now: Instant): Boolean {
        if (!firstVerified.isAfter(now.minus(DEFAULT_AGE))) return true
        if (!enabled) return false
        return !firstVerified.isAfter(now.minus(exceptionMinimumAge(party, now)))
    }

    private fun exceptionMinimumAge(party: UUID, now: Instant): Duration {
        return runCatching {
            val path = "/api/v1/parties/$party/mcp-native-passkey-age-exception/effective"
            upstream.getToService(partyService, path, emptyMap(), party.toString()).use { response ->
                if (response.status != Status.OK.statusCode) return DEFAULT_AGE
                val body = mapper.readTree(response.entity.toString())
                val minutes = body.path("minimumAgeMinutes")
                    .takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
                    ?: return DEFAULT_AGE
                if (minutes !in MIN_EXCEPTION_AGE_MINUTES..MAX_EXCEPTION_AGE_MINUTES) return DEFAULT_AGE
                val expiry = Instant.parse(body.path("expiresAt").textValue())
                if (!expiry.isAfter(now) || expiry.isAfter(now.plus(MAX_EXCEPTION_LIFETIME))) return DEFAULT_AGE
                Duration.ofMinutes(minutes.toLong())
            }
        }.getOrDefault(DEFAULT_AGE)
    }

    private companion object {
        const val MIN_EXCEPTION_AGE_MINUTES = 1
        const val MAX_EXCEPTION_AGE_MINUTES = 4319
        val MAX_EXCEPTION_LIFETIME: Duration = Duration.ofHours(24)

        val DEFAULT_AGE: Duration = Duration.ofHours(72)
    }
}
