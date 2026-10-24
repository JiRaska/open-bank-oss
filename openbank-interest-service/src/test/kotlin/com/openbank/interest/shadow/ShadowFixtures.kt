// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.shadow

import com.openbank.interest.domain.shadow.ShadowLegacyAccrual
import com.openbank.interest.domain.shadow.ShadowLegacyCapitalization
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.libs.product.ir.ProductRevisionIr
import com.openbank.libs.productrevision.HmacIrSigner
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * The interest shadow's test IR is the committed reference product `openbank-products/ir/savings-czk.json`
 * (ADR-0352 phase 3), read from the file rather than restated, so the parity the tests assert is parity
 * with the artifact the catalog may publish — not with a hand-kept copy of it.
 */
object ShadowFixtures {
    val SAVINGS_CZK: String = Files.readString(Path.of("..", "openbank-products", "ir", "savings-czk.json")).trim()

    const val SECRET = "test-only-ir-signing-secret-not-for-production"
    val signer = HmacIrSigner(SECRET.toByteArray(), "product-ir-publish")

    /** The reference IR with its annual rate replaced; [rate] is a canonical scale-10 decimal. */
    fun withRate(rate: String): String = SAVINGS_CZK.replace("\"rate\":\"0.0300000000\"", "\"rate\":\"$rate\"")
        .also { require(it != SAVINGS_CZK || rate == "0.0300000000") { "reference rate literal moved" } }

    fun withDayCount(dayCount: String): String =
        SAVINGS_CZK.replace("\"dayCount\":\"ACT_365_FIXED\"", "\"dayCount\":\"$dayCount\"")

    fun parse(ir: String = SAVINGS_CZK): ProductRevisionIr = ProductIrParser.parse(ir)

    fun accrual(
        balance: String,
        accrued: String,
        date: LocalDate = LocalDate.of(2028, 2, 29),
        decidedByPin: Boolean = true,
        revisionId: String? = UUID.randomUUID().toString(),
        currency: String = "CZK",
    ) = ShadowLegacyAccrual(
        sourceId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        productId = "savings",
        currency = currency,
        productRevisionId = revisionId,
        accrualDate = date,
        balance = BigDecimal(balance),
        accruedAmount = BigDecimal(accrued),
        decidedByPin = decidedByPin,
    )

    fun capitalization(totalAccrued: String, gross: String, tax: String = "0") = ShadowLegacyCapitalization(
        sourceId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        productId = "savings",
        currency = "CZK",
        productRevisionId = UUID.randomUUID().toString(),
        periodTo = LocalDate.of(2028, 2, 29),
        totalAccrued = BigDecimal(totalAccrued),
        grossAmount = BigDecimal(gross),
        taxAmount = BigDecimal(tax),
    )
}
