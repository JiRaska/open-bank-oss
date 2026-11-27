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
    @ConfigProperty(name = "openbank.edge.sandbox-mcp-native-age-policy", defaultValue = "false")
    private val sharedPolicyEnabled: Boolean,
) {
    fun eligible(party: UUID, firstVerified: Instant, now: Instant): Boolean {
        if (!firstVerified.isAfter(now.minus(DEFAULT_AGE))) return true
        val leaseAge = runCatching { minimumConfiguredAge(party, now) }.getOrNull() ?: return false
        return !firstVerified.isAfter(now.minus(leaseAge))
    }

    private fun minimumConfiguredAge(party: UUID, now: Instant): Duration? {
        if (enabled) {
            val exception = fetchAge(party, now, "mcp-native-passkey-age-exception")
            if (exception != null) return exception
        }
        return if (sharedPolicyEnabled) fetchAge(party, now, "mcp-native-passkey-age-policy") else null
    }

    private fun fetchAge(party: UUID, now: Instant, resource: String): Duration? {
        val path = "/api/v1/parties/$party/$resource/effective"
        return upstream.getToService(partyService, path, emptyMap(), party.toString()).use { response ->
            if (response.status == Status.NOT_FOUND.statusCode) return null
            check(response.status == Status.OK.statusCode)
            val body = mapper.readTree(response.entity.toString())
            val minutes = body.path("minimumAgeMinutes")
                .takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
            check(minutes != null && minutes in MIN_EXCEPTION_AGE_MINUTES..MAX_EXCEPTION_AGE_MINUTES)
            val expiry = Instant.parse(body.path("expiresAt").textValue())
            check(expiry.isAfter(now) && !expiry.isAfter(now.plus(MAX_EXCEPTION_LIFETIME)))
            Duration.ofMinutes(minutes.toLong())
        }
    }

    private companion object {
        const val MIN_EXCEPTION_AGE_MINUTES = 1
        const val MAX_EXCEPTION_AGE_MINUTES = 4319
        val MAX_EXCEPTION_LIFETIME: Duration = Duration.ofHours(24)

        val DEFAULT_AGE: Duration = Duration.ofHours(72)
    }
}
