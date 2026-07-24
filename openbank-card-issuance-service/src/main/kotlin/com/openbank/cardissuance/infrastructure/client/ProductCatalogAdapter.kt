// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.client

import com.openbank.cardissuance.application.port.out.CardConfigLookup
import com.openbank.cardissuance.application.port.out.CardProductCatalogPort
import com.openbank.cardissuance.application.port.out.CardProductConfig
import com.openbank.cardissuance.domain.model.CardNetwork
import io.quarkus.logging.Log
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * **Fail-open** adapter over [ProductCatalogClient] — the posture is spelled out on
 * [CardProductCatalogPort]: a card quota is a soft product rule and product-catalog is
 * KEDA scale-to-zero here, so a 404, a 401, a 5xx or a timeout all resolve to
 * [CardConfigLookup.Unavailable] and the issue proceeds.
 *
 * The one thing this must never be is quiet: every degraded path logs a WARN naming the product
 * code and the concrete failure, so a catalog that is permanently unreachable (and therefore an
 * entitlement gate that is permanently off) is visible in the logs instead of invisible.
 */
@ApplicationScoped
class ProductCatalogAdapter : CardProductCatalogPort {

    @Inject
    @RestClient
    lateinit var client: ProductCatalogClient

    // TooGenericExceptionCaught: deliberately fail-open on ANY fault (connection refused during a
    // scale-from-zero, read timeout, unexpected 5xx). Narrowing this would leave a class of faults
    // unhandled and blocking card issuance — exactly what this adapter exists to prevent.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun findCardConfig(productCode: String): CardConfigLookup = try {
        val product = client.getByCode(productCode).awaitSuspending()
        val config = product.cardConfig
        if (config == null) {
            Log.warnf("product-catalog product %s has no cardConfig; card entitlement rules skipped", productCode)
            CardConfigLookup.Unavailable
        } else {
            CardConfigLookup.Found(config.toDomain())
        }
    } catch (e: WebApplicationException) {
        val status = e.response?.status ?: 0
        Log.warnf(
            "product-catalog returned HTTP %d for product %s; card entitlement rules skipped",
            status,
            productCode,
        )
        CardConfigLookup.Unavailable
    } catch (e: Exception) {
        Log.warnf(
            "product-catalog unavailable for product %s; card entitlement rules skipped: %s",
            productCode,
            e.message,
        )
        CardConfigLookup.Unavailable
    }

    /**
     * Unknown network names are dropped rather than failing the lookup: product-catalog owns a
     * wider `CardNetwork` vocabulary than this service issues on, and a network we cannot issue is
     * indistinguishable from one that is not allowed.
     */
    private fun CardConfigResponse.toDomain() = CardProductConfig(
        enabled = enabled,
        maxCards = maxCards,
        networks = networks.mapNotNull { name -> CardNetwork.entries.firstOrNull { it.name == name } },
        tiers = tiers,
        virtualCardAllowed = virtualCardAllowed,
        contactlessEnabled = contactlessEnabled,
        monthlyFeePerCard = monthlyFeePerCard,
        cardCurrency = cardCurrency,
    )
}
