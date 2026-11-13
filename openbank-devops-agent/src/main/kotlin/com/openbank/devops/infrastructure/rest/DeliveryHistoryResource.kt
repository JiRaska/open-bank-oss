// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.rest

import com.openbank.devops.application.port.incoming.GetDeliveryHistoryUseCase
import com.openbank.devops.domain.model.DeliveryHistory
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/api/v1/devops/delivery/history")
@Produces(MediaType.APPLICATION_JSON)
class DeliveryHistoryResource(private val history: GetDeliveryHistoryUseCase) {
    @GET
    @RolesAllowed("ROLE_ADMIN", "ROLE_VIEWER")
    suspend fun getHistory(@QueryParam("limit") @DefaultValue("288") limit: Int): DeliveryHistory =
        history.getHistory(limit)
}
