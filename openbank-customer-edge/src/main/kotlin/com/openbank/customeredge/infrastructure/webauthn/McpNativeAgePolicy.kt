// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.customeredge.infrastructure.webauthn

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import jakarta.enterprise.context.ApplicationScoped
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
                if (response.status != 200) return DEFAULT_AGE
                val body = mapper.readTree(response.entity.toString())
                val minutes = body.path("minimumAgeMinutes")
                val expiry = Instant.parse(body.path("expiresAt").textValue())
                if (!minutes.isIntegralNumber || !minutes.canConvertToInt() ||
                    minutes.intValue() !in 1..4319 || !expiry.isAfter(now) ||
                    expiry.isAfter(now.plus(Duration.ofHours(24)))
                ) {
                    return DEFAULT_AGE
                }
                Duration.ofMinutes(minutes.longValue())
            }
        }.getOrDefault(DEFAULT_AGE)
    }

    private companion object {
        val DEFAULT_AGE: Duration = Duration.ofHours(72)
    }
}
