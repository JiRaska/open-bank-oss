// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.contact.ContactPolicy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty

/** The delivery guardrails Studio can show without pretending to predict a changing person-level decision. */
data class CampaignContactGuardrails(
    val maxSendsPerParty: Int,
    val sendWindowHours: Long,
    val quietHoursStart: Int,
    val quietHoursEnd: Int,
    val timeZone: String,
)

/**
 * The current platform contact rules for a campaign author.
 *
 * This is deliberately a policy explanation, not a reach forecast. Consent, suppression state and
 * the rolling send count are person-specific and change between authoring and delivery; showing a
 * count of people we "expect" to pass would turn that temporal fact into a false promise. The
 * workflow still re-checks those facts immediately before every outbound handoff.
 */
@Path("/api/v1/campaigns/guardrails")
@ApplicationScoped
class CampaignContactGuardrailResource(
    @ConfigProperty(name = "openbank.campaign.max-sends-per-party-per-week", defaultValue = "2")
    private val maxSendsPerParty: Int,
    @ConfigProperty(name = "openbank.campaign.quiet-hours-start", defaultValue = "21")
    private val quietHoursStart: Int,
    @ConfigProperty(name = "openbank.campaign.quiet-hours-end", defaultValue = "8")
    private val quietHoursEnd: Int,
) {

    @GET
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun guardrails(): Response {
        val platformDefaults = ContactPolicy()
        return Response.ok(
            CampaignContactGuardrails(
                maxSendsPerParty = maxSendsPerParty,
                sendWindowHours = platformDefaults.sendWindowSeconds / SECONDS_PER_HOUR,
                quietHoursStart = quietHoursStart,
                quietHoursEnd = quietHoursEnd,
                timeZone = platformDefaults.quietZone,
            ),
        ).build()
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3_600L
    }
}
