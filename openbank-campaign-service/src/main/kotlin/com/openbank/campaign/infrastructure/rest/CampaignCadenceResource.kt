// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.domain.model.ScheduleCatalog
import com.openbank.libs.authz.Authorize
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response

/**
 * The cadences a recurring campaign may be scheduled on.
 *
 * Served rather than documented, so the console cannot offer a cadence the service would reject:
 * the authoring screen reads this list, and adding an entry to [ScheduleCatalog] is all it takes for
 * the option to appear in it.
 *
 * **A separate resource from `CampaignResource` on purpose.** That class sits exactly at detekt's
 * `TooManyFunctions` threshold of 11, which fires AT the limit rather than above it, so an
 * eleventh-and-first method costs the gate — its own KDoc records that a private helper already had
 * to be moved out for the same reason. A read-only catalogue is also not campaign lifecycle, so the
 * split the linter forces is the one that belongs here anyway.
 *
 * The literal `/cadences` segment must keep matching ahead of `CampaignResource`'s `/{id}`. JAX-RS
 * sorts candidates by literal-character count before template count, and that ordering holds across
 * classes, so this stays reachable — `CampaignRestContractIT` pins it, as it does for `/summary`.
 */
@Path("/api/v1/campaigns/cadences")
@ApplicationScoped
class CampaignCadenceResource {

    @GET
    @Authorize(action = "campaign.read", resource = "#id")
    suspend fun cadences(): Response = Response.ok(
        ScheduleCatalog.ALL.map { (key, cadence) ->
            mapOf("cadence" to key, "humanForm" to cadence.humanForm, "zone" to ScheduleCatalog.ZONE)
        },
    ).build()
}
