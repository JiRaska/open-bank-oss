// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "fx_rates")
class FxRateEntity {
    @Id
    var id: UUID = UUID.randomUUID()
    var baseCurrency: String = ""
    var quoteCurrency: String = ""

    // Explicit column names: a bare @Column (precision/scale, no name) is treated as an
    // "explicit" mapping and SKIPS the camelCase->snake_case implicit naming strategy, so
    // Hibernate looked for `bidrate`/`askrate` while Flyway created `bid_rate`/`ask_rate`
    // -> "column askrate does not exist" (42703) -> every GET /rates returned 500.
    @Column(name = "bid_rate", precision = 18, scale = 8)
    var bidRate: BigDecimal = BigDecimal.ZERO

    @Column(name = "ask_rate", precision = 18, scale = 8)
    var askRate: BigDecimal = BigDecimal.ZERO

    var rateType: String = ""
    var source: String = ""
    var validFrom: Instant = Instant.now()
    var validTo: Instant = Instant.now()
    var createdAt: Instant = Instant.now()
}

@Entity
@Table(name = "fx_conversions")
class FxConversionEntity {
    @Id
    var id: UUID = UUID.randomUUID()

    @Column(unique = true)
    var idempotencyKey: String = ""

    var partyId: UUID = UUID.randomUUID()
    var accountId: UUID? = null
    var fromCurrency: String = ""
    var toCurrency: String = ""
    var fromAmountMinorUnits: Long = 0
    var toAmountMinorUnits: Long = 0

    @Column(name = "applied_rate", precision = 18, scale = 8)
    var appliedRate: BigDecimal = BigDecimal.ZERO

    var feeMinorUnits: Long = 0
    var rateId: UUID = UUID.randomUUID()
    var status: String = ""
    var createdAt: Instant = Instant.now()
    var settledAt: Instant? = null
}
