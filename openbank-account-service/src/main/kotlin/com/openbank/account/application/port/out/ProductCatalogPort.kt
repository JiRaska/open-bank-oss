// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import java.time.Instant
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
 * miss ([NotFound], a genuine 404): both now refuse account opening, but with different statuses —
 * a missing product is the caller's error, an outage is retryable.
 */
sealed interface ProductLookupResult {
    data class Found(val product: CatalogProduct) : ProductLookupResult
    data object NotFound : ProductLookupResult
    data object Unavailable : ProductLookupResult
}

/** The subset of a PUBLISHED catalog revision an account pins (ADR-0341 D1). */
data class PublishedRevision(
    val revisionId: UUID,
    val offeringId: UUID,
    val number: Long,
    val contentHash: String?,
    /** Canonical IR `revisionHash` from the revision's publish-path signature; null without IR. */
    val irRevisionHash: String?,
    val irVersion: Int?,
)

/**
 * The three outcomes of resolving the revision to pin, each its own value (the ADR-0252 phase 0
 * lesson: a skip must never share a signal with success). Only [Published] lets an account open.
 */
sealed interface RevisionResolution {
    data class Published(val revision: PublishedRevision) : RevisionResolution

    /** The catalog answered: no revision of this product is published at that instant (404). */
    data object NoPublishedRevision : RevisionResolution

    /** The catalog could not answer (outage, timeout, open circuit, 5xx, auth failure). */
    data object Unavailable : RevisionResolution
}

/**
 * Outbound port resolving a product and the published revision an account pins from
 * product-catalog.
 *
 * **Fails closed on the pin, not on availability as such (ADR-0341 D1, ADR-0352 §6).** Since V33
 * an account cannot be opened without a pinned published revision, so an unreachable catalog
 * refuses opening — not because the catalog is in the posting path (it is not: postings read the
 * pin), but because there is no pin to record. This replaces the fail-open existence check of
 * ADR-0158 for account opening. Implementations never throw for an outage; they answer
 * [ProductLookupResult.Unavailable] / [RevisionResolution.Unavailable] and the caller refuses.
 */
interface ProductCatalogPort {
    suspend fun findById(productId: UUID): ProductLookupResult

    /** The revision of v1 product [productId] that is PUBLISHED and effective at [effectiveAt]. */
    suspend fun resolvePublishedRevision(productId: UUID, effectiveAt: Instant): RevisionResolution
}
