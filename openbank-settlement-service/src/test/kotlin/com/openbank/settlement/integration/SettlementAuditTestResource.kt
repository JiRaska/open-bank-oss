// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.integration

import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.observability.SettlementAuditBacklogGauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import java.util.UUID

/** Test-only HTTP boundary supplies the Vert.x context used by the real persistence code. */
@Path("/test/settlement-audit")
@PermitAll
class SettlementAuditTestResource(
    private val repository: SettlementRepository,
    private val gauge: SettlementAuditBacklogGauge,
    private val registry: MeterRegistry,
) {
    @POST
    @Path("/refresh-audit-age")
    suspend fun refreshAge(): Map<String, Double> {
        gauge.refresh()
        return mapOf("age" to registry.get(SettlementAuditBacklogGauge.METRIC).gauge().value())
    }

    @POST
    @Path("/{id}/status/{status}")
    suspend fun change(@PathParam("id") id: UUID, @PathParam("status") status: SettlementStatus) =
        repository.updateStatus(id, status)

    @POST
    @Path("/{id}/claim")
    suspend fun claim(@PathParam("id") id: UUID) = mapOf("claimed" to repository.claimForProcessing(id))
}
