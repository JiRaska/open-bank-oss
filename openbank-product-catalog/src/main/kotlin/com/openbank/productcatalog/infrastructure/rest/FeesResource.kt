// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.productcatalog.application.ProductCatalogService
import com.openbank.productcatalog.infrastructure.security.CatalogRoles
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Bank-wide fee schedule, served by the product catalog (the system of record for
 * pricing). The admin UI's "Fees" screen reads this instead of hardcoding a price
 * list in the web tier. Fees live with their owning product; this endpoint flattens
 * them into one filterable schedule.
 */
@ApplicationScoped
@Path("/api/v1/fees")
@Produces(MediaType.APPLICATION_JSON)
class FeesResource(
    private val service: ProductCatalogService,
    @ConfigProperty(name = "openbank.catalog.bank-v1-compatibility-enabled", defaultValue = "true")
    private val bankCompatibilityEnabled: Boolean,
) {

    @GET
    @RolesAllowed(Roles.API, Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN, CatalogRoles.READ)
    @Authorize(action = "catalog.list")
    suspend fun list(
        @QueryParam("type") type: String?,
        @QueryParam("currency") currency: String?,
        @QueryParam("productCode") productCode: String?,
    ): Response {
        if (!bankCompatibilityEnabled) {
            throw jakarta.ws.rs.NotFoundException("the banking compatibility API is disabled")
        }
        var items = service.listFeeSchedule()
        if (!type.isNullOrBlank()) items = items.filter { it.type == type }
        if (!currency.isNullOrBlank()) items = items.filter { it.currency == currency }
        if (!productCode.isNullOrBlank()) items = items.filter { it.productCode == productCode }
        return Response.ok(items).build()
    }
}
