// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.persistence.entity

import com.openbank.libs.domain.identifiers.Ids
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `cnb_policy_rate` (V9). Every column is named explicitly — the implicit name is not snake_case. */
@Entity
@Table(name = "cnb_policy_rate")
class CnbPolicyRateEntity {
    @Id
    @Column(name = "id")
    var id: UUID = Ids.newId()

    @Column(name = "instrument")
    var instrument: String = ""

    @Column(name = "effective_from")
    var effectiveFrom: LocalDate = LocalDate.MIN

    @Column(name = "rate", precision = 12, scale = 8)
    var rate: BigDecimal = BigDecimal.ZERO

    @Column(name = "source_url")
    var sourceUrl: String = ""

    @Column(name = "fetched_at")
    var fetchedAt: Instant? = null

    @Column(name = "content_sha256")
    var contentSha256: String = ""

    @Column(name = "note")
    var note: String? = null

    @Column(name = "previous_rate", precision = 12, scale = 8)
    var previousRate: BigDecimal? = null

    @Column(name = "revised_at")
    var revisedAt: Instant? = null

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null
}
