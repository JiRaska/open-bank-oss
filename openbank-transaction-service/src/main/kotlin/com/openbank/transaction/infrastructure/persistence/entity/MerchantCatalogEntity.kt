// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One merchant in the enrichment catalogue, keyed by its normalised acquirer descriptor.
 *
 * Maintained by operators through `MerchantCatalogResource`, and never from a customer request
 * (#8573). Until that resource existed the table was write-never: it held the rows one migration
 * seeded and had no writer at all, so the enrichment it feeds was absent for most transactions.
 *
 * Holding only public business data (trading name, shop location) is a deliberate boundary that
 * the write path preserves — see the table comment in `V16__create_merchant_catalog.sql`.
 */
@Entity
@Table(name = "merchant_catalog")
class MerchantCatalogEntity : PanacheEntityBase {
    @Id
    @Column(name = "descriptor_key")
    var descriptorKey: String = ""

    @Column(name = "clean_name", nullable = false)
    var cleanName: String = ""

    @Column(name = "logo_url")
    var logoUrl: String? = null

    @Column(name = "category")
    var category: String? = null

    /** Null for card-not-present merchants; never a stand-in head-office pin. */
    @Column(name = "lat")
    var lat: Double? = null

    @Column(name = "lon")
    var lon: Double? = null

    @Column(name = "city")
    var city: String? = null

    @Column(name = "country")
    var country: String? = null

    /**
     * What [lat]/[lon] on THIS row can answer — [GeoPrecision].
     *
     * `CITY` for every seeded row, and that is a correction rather than a default: V16 pinned each
     * brand at one Prague coordinate, so a Billa purchase in Brno resolved 185 km from where it
     * happened. A chain has no single location, and the fix is to stop claiming one.
     */
    @Column(name = "geo_precision", nullable = false)
    var geoPrecision: String = GeoPrecision.CITY

    /**
     * [MerchantLogoEntity.contentHash] when a logo has been ingested for this merchant, else null.
     *
     * Denormalised so the per-page enrichment read can decide whether to emit a logo URL without a
     * join, and doubles as the cache-busting token in that URL — a corrected logo changes the hash,
     * so clients pick it up immediately instead of after a cache TTL.
     */
    @Column(name = "logo_etag")
    var logoEtag: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
