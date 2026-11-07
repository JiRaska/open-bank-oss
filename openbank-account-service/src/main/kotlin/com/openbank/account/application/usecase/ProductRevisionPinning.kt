// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.PinOutcome
import com.openbank.account.application.port.out.ProductCatalogPort
import com.openbank.account.application.port.out.ProductLookupResult
import com.openbank.account.application.port.out.ProductRevisionPinMetrics
import com.openbank.account.application.port.out.RevisionResolution
import com.openbank.account.domain.model.ProductRevisionPin
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * Resolves the product revision an account is opened under (ADR-0341 D1, ADR-0352 phase 3).
 *
 * Fail-closed on the PIN: an opening goes ahead only with a PUBLISHED revision to record. It never
 * falls back to "open unpinned" — an outage refuses with [ProductCatalogUnavailableException] (503,
 * retryable), a product with nothing published refuses with [ProductRevisionNotPublishedException].
 * It performs no write, so a refusal leaves nothing behind, and every outcome is counted.
 */
@ApplicationScoped
class ProductRevisionPinning(
    private val productCatalog: ProductCatalogPort,
    private val metrics: ProductRevisionPinMetrics,
) {

    /** The pin for opening an account in [currency] against v1 product [productId] at [at]. */
    suspend fun pinFor(productId: UUID, currency: String, at: Instant): ProductRevisionPin {
        val product = when (val lookup = productCatalog.findById(productId)) {
            is ProductLookupResult.Found -> lookup.product
            ProductLookupResult.NotFound -> refuse(PinOutcome.PRODUCT_NOT_FOUND) {
                ProductNotEligibleException(productId, "product does not exist")
            }
            ProductLookupResult.Unavailable -> refuse(PinOutcome.CATALOG_UNAVAILABLE) {
                ProductCatalogUnavailableException(productId)
            }
        }
        if (product.status != ACTIVE) {
            refuse(PinOutcome.PRODUCT_NOT_ELIGIBLE) {
                ProductNotEligibleException(productId, "product status is ${product.status}, not ACTIVE")
            }
        }
        if (product.currency != currency) {
            refuse(PinOutcome.PRODUCT_NOT_ELIGIBLE) {
                ProductNotEligibleException(productId, "product currency is ${product.currency}, not $currency")
            }
        }
        val revision = when (val resolution = productCatalog.resolvePublishedRevision(productId, at)) {
            is RevisionResolution.Published -> resolution.revision
            RevisionResolution.NoPublishedRevision -> refuse(PinOutcome.NO_PUBLISHED_REVISION) {
                ProductRevisionNotPublishedException(productId)
            }
            RevisionResolution.Unavailable -> refuse(PinOutcome.CATALOG_UNAVAILABLE) {
                ProductCatalogUnavailableException(productId)
            }
        }
        val pin = ProductRevisionPin(
            offeringId = revision.offeringId,
            productCode = product.code,
            revisionId = revision.revisionId,
            revisionNumber = revision.number,
            contentHash = revision.contentHash,
            irRevisionHash = revision.irRevisionHash,
            irVersion = revision.irVersion,
            pinnedAt = at,
        )
        metrics.record(PinOutcome.PINNED)
        return pin
    }

    private inline fun refuse(outcome: PinOutcome, error: () -> RuntimeException): Nothing {
        metrics.record(outcome)
        throw error()
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
    }
}

/** product-catalog could not be asked, so no revision can be pinned: 503, retryable (ADR-0341 D1). */
class ProductCatalogUnavailableException(productId: UUID) :
    RuntimeException("product-catalog unavailable; cannot pin a revision for product $productId")

/** The product exists but no revision of it is PUBLISHED at the opening instant: 422. */
class ProductRevisionNotPublishedException(productId: UUID) :
    RuntimeException("product $productId has no published catalog revision to pin")
