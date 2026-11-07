// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.rest

import com.openbank.devops.application.port.incoming.GetDeliveryOverviewUseCase
import com.openbank.devops.domain.model.DeliveryOverview
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/v1/devops/delivery")
@Produces(MediaType.APPLICATION_JSON)
class DeliveryOverviewResource(private val overview: GetDeliveryOverviewUseCase) {
    @GET
    @Path("/overview")
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getOverview(): DeliveryOverview = overview.getOverview()
}
