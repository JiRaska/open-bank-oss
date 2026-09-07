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
 * The stored bitmaps for one merchant's logo, in the two sizes a client renders.
 *
 * Separate from [MerchantCatalogEntity] on purpose: the catalogue row is read for every statement
 * page, and a `SELECT *` that drags a few kB of image bytes per merchant along with it would put
 * the images on the enrichment critical path for the benefit of nobody — the page renders names
 * and categories, and fetches each logo once, cached, over its own request.
 *
 * Whether a logo exists at all is answered without touching this table, by
 * [MerchantCatalogEntity.logoEtag].
 */
@Entity
@Table(name = "merchant_logo")
class MerchantLogoEntity : PanacheEntityBase {
    @Id
    @Column(name = "descriptor_key")
    var descriptorKey: String = ""

    @Column(name = "bytes_64", nullable = false)
    var bytes64: ByteArray = ByteArray(0)

    @Column(name = "bytes_128", nullable = false)
    var bytes128: ByteArray = ByteArray(0)

    @Column(name = "content_type", nullable = false)
    var contentType: String = ""

    @Column(name = "content_hash", nullable = false)
    var contentHash: String = ""

    /** Where the bytes came from — provenance, never served to a customer client. */
    @Column(name = "source_url")
    var sourceUrl: String? = null

    @Column(name = "licence")
    var licence: String? = null

    @Column(name = "attribution")
    var attribution: String? = null

    @Column(name = "uploaded_by")
    var uploadedBy: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH
}
