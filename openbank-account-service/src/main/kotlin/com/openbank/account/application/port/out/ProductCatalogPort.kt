// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import java.util.UUID

/** The minimal projection of a product-catalog `Product` needed to validate account opening (issue #668). */
data class CatalogProduct(
    val id: UUID,
    val code: String,
    val status: String,
    /** ISO-4217 product currency from the v1 compatibility projection. */
    val currency: String,
)

/**
 * The outcome of resolving a product by its canonical id from `openbank-product-catalog`
 * (ADR-0105 unified product identity; issue #668). [Unavailable] is distinct from a confirmed
 * miss: [findByCode] returns `null` for a genuine 404 (the product does not exist), while an
 * implementation resolves to [Unavailable] on a fault/timeout — the caller's fail-open policy
 * only applies to the latter.
 */
sealed interface ProductLookupResult {
    data class Found(val product: CatalogProduct) : ProductLookupResult
    data object NotFound : ProductLookupResult
    data object Unavailable : ProductLookupResult
}

/**
 * Outbound port resolving a product's canonical definition from product-catalog, so account
 * opening can validate against it rather than accepting an arbitrary UUID with no check
 * (issue #668). product-catalog is read-only reference data, not money-path
 * (`rules.yaml: money_path_services` does not list it) — implementations MUST fail safe,
 * resolving to [ProductLookupResult.Unavailable] rather than propagating a failure that would
 * block account opening on a reference-data outage. This is a DIFFERENT posture from the
 * sanctions gate (ADR-0032 §C fails closed): an unreachable compliance screen is a regulatory
 * risk, an unreachable product catalogue is not.
 */
interface ProductCatalogPort {
    suspend fun findById(productId: UUID): ProductLookupResult
}
