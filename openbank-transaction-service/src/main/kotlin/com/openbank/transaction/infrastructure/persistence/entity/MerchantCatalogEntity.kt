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
 * Read-only in this service: rows arrive by migration or by an out-of-band catalogue load, never
 * from a customer request. Holding only public business data (trading name, shop location) is a
 * deliberate boundary — see the table comment in `V10__create_merchant_catalog.sql`.
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

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
