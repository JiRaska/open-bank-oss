// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.domain.model.TriggerCatalog
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/**
 * Product events that may start a campaign journey.
 *
 * As with [CampaignCadenceResource], the authoring surface reads the catalogue instead of keeping
 * a second list in TypeScript. A trigger is an executable integration contract: showing an event
 * that no consumer watches would let an author publish a campaign that waits forever. The human
 * form is intentionally served with the key — a marketer approves the event's meaning, never a
 * Kafka topic or CloudEvents type.
 */
@Path("/api/v1/campaigns/triggers")
@ApplicationScoped
class CampaignTriggerResource {

    @GET
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun triggers(): Response = Response.ok(
        TriggerCatalog.ALL.map { (key, trigger) ->
            mapOf("trigger" to key, "humanForm" to trigger.humanForm)
        },
    ).build()
}
