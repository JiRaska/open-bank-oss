// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * TRANSITIONAL bridge for catalog revisions that are lossless projections of a v1 banking product
 * (schema `org.openbank.banking.legacy-product`, ADR-0257 compatibility boundary). Those revisions
 * carry their fee amounts as v2 price components -- which billing reads as the source of truth -- but
 * keep the fee's identity and waiver rule only inside the embedded v1 document, and carry no
 * accounting class at all. Billing reads that metadata here, matched to a price component by its
 * code, so the idempotency key of an already-billed fee stays the v1 fee id and a cutover cannot
 * charge anyone twice.
 *
 * It goes away when those products are re-published under a catalog schema that carries the class
 * and the waiver rule natively; until then it is the only place billing knows a v1 vocabulary.
 */
internal object LegacyFees {
    private val mapper = jacksonObjectMapper()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LegacyFee(
        val id: String,
        val name: String = "",
        val type: String = "",
        val waivable: Boolean = false,
        val waiveCondition: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class LegacyDocument(val fees: List<LegacyFee> = emptyList())

    /** The embedded document's fees keyed by the price-component code the catalog derives from their id. */
    fun byPriceCode(legacyDocument: String?): Map<String, LegacyFee> {
        if (legacyDocument.isNullOrBlank()) return emptyMap()
        return mapper.readValue<LegacyDocument>(legacyDocument).fees.associateBy { priceCode(it.id) }
    }

    /**
     * Mirrors `BankV1CatalogMapping.feeCode` in openbank-product-catalog: `FEE_<id upper-cased, runs of
     * non A-Z0-9 collapsed to _, trimmed, at most 38 chars>_<first 20 hex of SHA-256(id), upper-cased>`.
     * `LegacyFeesTest` pins it to literal vectors taken from the catalog's own mapping.
     */
    fun priceCode(feeId: String): String {
        val prefix = feeId.uppercase().replace(NON_CODE, "_").trim('_').ifBlank { "FEE" }.take(MAX_PREFIX)
        val hash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(feeId.toByteArray(StandardCharsets.UTF_8)),
        ).take(HASH_LENGTH).uppercase()
        return "FEE_${prefix}_$hash"
    }

    private const val MAX_PREFIX = 38
    private const val HASH_LENGTH = 20
    private val NON_CODE = Regex("[^A-Z0-9]+")
}

/**
 * `productType` of a v1 projection -> the deposit accounting class it implies (see [LegacyFees]).
 *
 * EVERY value of the v1 `ProductType` enum (and of the `legacy-product` schema's `productType` enum)
 * is mapped, because before ADR-0341 billing charged a fee against the customer's deposit-control
 * account whatever the product's type. A product that is not itself a deposit (LOAN, MORTGAGE,
 * CREDIT_CARD, OVERDRAFT, INVESTMENT) is billed to the customer's CURRENT account, which is where its
 * fee was always debited; deriving it is COUNTED per type (`billing.accounting_class.derived`). Only a
 * type the v1 enum does not contain yields null, and that is a counted skip, not a guess.
 */
internal object LegacyProductTypes {
    /** All v1 product types: `openbank-product-catalog` `ProductType` and the legacy-product pack schema. */
    val KNOWN = setOf(
        "CURRENT",
        "SAVINGS",
        "TERM_DEPOSIT",
        "OVERDRAFT",
        "LOAN",
        "MORTGAGE",
        "CREDIT_CARD",
        "INVESTMENT",
    )

    fun accountingClassOf(productType: String?): String? = when (productType) {
        "SAVINGS" -> "CUSTOMER_DEPOSIT_SAVINGS"
        "TERM_DEPOSIT" -> "CUSTOMER_DEPOSIT_TERM"
        "CURRENT", "OVERDRAFT", "LOAN", "MORTGAGE", "CREDIT_CARD", "INVESTMENT" -> "CUSTOMER_DEPOSIT_DEMAND"
        else -> null
    }
}
