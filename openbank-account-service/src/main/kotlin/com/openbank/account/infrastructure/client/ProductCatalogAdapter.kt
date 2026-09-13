// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import com.openbank.account.application.port.out.CatalogProduct
import com.openbank.account.application.port.out.ProductCatalogPort
import com.openbank.account.application.port.out.ProductLookupResult
import io.quarkus.logging.Log
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/**
 * **Fail-open** adapter over [ProductCatalogClient] (issue #668). Unlike [SanctionsScreeningAdapter]
 * (fails closed — a missing compliance screen is a regulatory risk), an unreachable product
 * catalogue must never block account opening: product-catalog is reference data, not money-path.
 * A confirmed 404 is NOT treated the same way as an outage: it maps to [ProductLookupResult.NotFound],
 * which the caller's validation DOES act on (an operator cannot open an account against a product
 * that does not exist).
 */
@ApplicationScoped
class ProductCatalogAdapter : ProductCatalogPort {

    @Inject
    @RestClient
    lateinit var client: ProductCatalogClient

    // TooGenericExceptionCaught: deliberately fail-open on ANY fault (network error, timeout,
    // unexpected 5xx) — see the class doc. Narrowing this would leave a class of faults
    // unhandled and blocking account opening, exactly what this adapter exists to prevent.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun findById(productId: UUID): ProductLookupResult = try {
        val response = client.getById(productId.toString()).awaitSuspending()
        ProductLookupResult.Found(
            CatalogProduct(UUID.fromString(response.id), response.code, response.status, response.currency),
        )
    } catch (e: WebApplicationException) {
        val status = e.response?.status ?: 0
        if (status == Response.Status.NOT_FOUND.statusCode) {
            ProductLookupResult.NotFound
        } else {
            Log.warnf("product-catalog returned HTTP %d for product %s; validation skipped", status, productId)
            ProductLookupResult.Unavailable
        }
    } catch (e: Exception) {
        Log.warnf("product-catalog unavailable for %s; validation skipped: %s", productId, e.message)
        ProductLookupResult.Unavailable
    }
}
