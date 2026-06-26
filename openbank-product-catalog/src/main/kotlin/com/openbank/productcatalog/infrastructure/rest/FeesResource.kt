// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.productcatalog.infrastructure.rest

import com.openbank.productcatalog.application.ProductCatalogService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Bank-wide fee schedule, served by the product catalog (the system of record for
 * pricing). The admin UI's "Fees" screen reads this instead of hardcoding a price
 * list in the web tier. Fees live with their owning product; this endpoint flattens
 * them into one filterable schedule.
 */
@ApplicationScoped
@Path("/api/v1/fees")
@Produces(MediaType.APPLICATION_JSON)
class FeesResource(private val service: ProductCatalogService) {

    @GET
    fun list(
        @QueryParam("type") type: String?,
        @QueryParam("currency") currency: String?,
        @QueryParam("productCode") productCode: String?
    ): Response {
        var items = service.listFeeSchedule()
        if (!type.isNullOrBlank()) items = items.filter { it.type == type }
        if (!currency.isNullOrBlank()) items = items.filter { it.currency == currency }
        if (!productCode.isNullOrBlank()) items = items.filter { it.productCode == productCode }
        return Response.ok(items).build()
    }
}
