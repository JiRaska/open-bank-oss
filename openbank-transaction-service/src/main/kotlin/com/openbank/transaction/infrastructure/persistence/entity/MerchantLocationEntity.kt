// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * Where one merchant trades in one town.
 *
 * Keyed by (descriptor, town-as-the-acquirer-spells-it) because that is the only per-site
 * information today's data actually carries: `BILLA PRAHA 4` and `BILLA BRNO` normalise to the same
 * merchant, and the token that distinguishes them is the one the lookup key deliberately discards.
 *
 * [terminalId] is null on every row today — no feed in this fleet supplies a terminal or ATM
 * identifier — and is the only thing that may justify [geoPrecision] `EXACT` for a chain. The
 * database enforces that, so the field is a place for a fact to land rather than a promise that one
 * already has.
 */
@Entity
@Table(name = "merchant_location")
@IdClass(MerchantLocationEntity.Key::class)
class MerchantLocationEntity : PanacheEntityBase {
    /** Composite primary key: one row per merchant per town. */
    data class Key(val descriptorKey: String = "", val cityToken: String = "") : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    @Id
    @Column(name = "descriptor_key")
    var descriptorKey: String = ""

    @Id
    @Column(name = "city_token")
    var cityToken: String = ""

    @Column(name = "lat", nullable = false)
    var lat: Double = 0.0

    @Column(name = "lon", nullable = false)
    var lon: Double = 0.0

    @Column(name = "city")
    var city: String? = null

    @Column(name = "country")
    var country: String? = null

    @Column(name = "geo_precision", nullable = false)
    var geoPrecision: String = GeoPrecision.CITY

    @Column(name = "terminal_id")
    var terminalId: String? = null

    @Column(name = "source")
    var source: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

/**
 * What a coordinate can answer.
 *
 * The distinction exists because the catalogue's seeded rows could not answer the question they
 * were being asked: one pin per BRAND, rendered on a map captioned "where you spent", put every
 * Billa purchase in the country at one Prague address. Nothing was broken — the data said exactly
 * what it was asked to, and it was fiction.
 */
object GeoPrecision {
    /** Where the money was actually spent: a single-site merchant, or a device-resolved location. */
    const val EXACT = "EXACT"

    /** The merchant trades in this town; the pin is representative. Caption the town, do not claim a street. */
    const val CITY = "CITY"
}
