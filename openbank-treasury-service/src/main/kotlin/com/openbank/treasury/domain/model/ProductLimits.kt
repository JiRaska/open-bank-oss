// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import java.math.BigDecimal

/**
 * The product limit of ADR-0315 D4 — the second of the three limit families "checked at
 * PENDING_APPROVAL" next to the counterparty limit ([LimitCheck]). It is the bank's mandate for a
 * PRODUCT, independent of who the counterparty is: which currencies the product may be dealt in,
 * the largest principal per deal in each, and the longest tenor.
 *
 * Declared as code (D4: "Limits are declared as code"), in `application.yaml` under
 * `openbank.treasury.product-limits`, so a change to a mandate is a reviewed change. For the same
 * reason a product-limit breach is NOT overridable: the counterparty override (V4) exists because
 * credit exposure moves with other deals; a product mandate does not, and the way to deal outside
 * it is to change the declared mandate, under review — not a per-deal exception.
 *
 * @property maxPrincipal the largest principal per deal, by currency. The currency SET is the
 *   allowed-currency list: a currency with no entry may not be dealt in this product at all.
 * @property maxTenorDays the longest value-to-maturity tenor in calendar days; null = no tenor cap.
 *   Not applied to [ProductType.FX_SPOT], which has no tenor (it is final once settled).
 */
data class ProductLimit(val product: ProductType, val maxPrincipal: Map<String, BigDecimal>, val maxTenorDays: Long?) {
    init {
        require(maxPrincipal.values.all { it.signum() > 0 }) { "$product: every max principal must be positive" }
        require(maxTenorDays == null || maxTenorDays > 0) { "$product: max tenor must be positive" }
    }
}

/** One rule of a [ProductLimit] a deal breaks. [limit] and [actual] are display strings. */
data class ProductLimitBreach(val rule: Rule, val limit: String, val actual: String) {
    enum class Rule { PRODUCT_NOT_PERMITTED, CURRENCY_NOT_PERMITTED, MAX_PRINCIPAL, MAX_TENOR }
}

/** The outcome of evaluating one deal against the [ProductLimitPolicy]. Empty breaches = within. */
data class ProductLimitCheck(
    val product: ProductType,
    val limit: ProductLimit?,
    val breaches: List<ProductLimitBreach>,
) {
    val breached: Boolean get() = breaches.isNotEmpty()

    /** The human sentence a refusal carries. */
    fun describe(): String = breaches.joinToString("; ") { b ->
        when (b.rule) {
            ProductLimitBreach.Rule.PRODUCT_NOT_PERMITTED -> "product $product has no declared product limit"
            ProductLimitBreach.Rule.CURRENCY_NOT_PERMITTED ->
                "$product may not be dealt in ${b.actual} (permitted: ${b.limit})"
            ProductLimitBreach.Rule.MAX_PRINCIPAL ->
                "$product principal ${b.actual} exceeds the product maximum ${b.limit}"
            ProductLimitBreach.Rule.MAX_TENOR ->
                "$product tenor ${b.actual} days exceeds the product maximum ${b.limit} days"
        }
    }
}

/** Raised when a deal is outside its product limit (ADR-0315 D4). Mapped to 422 PRODUCT_LIMIT_BREACHED. */
class ProductLimitBreachedException(val check: ProductLimitCheck) :
    RuntimeException("product limit breached: ${check.describe()}")

/**
 * Pure evaluation of a deal against the declared product limits. FAIL-CLOSED: a product with no
 * declared limit is not permitted at all — a product added to [ProductType] without a mandate must
 * not book unbounded because nobody wrote one.
 */
class ProductLimitPolicy(limits: Collection<ProductLimit>) {
    private val byProduct: Map<ProductType, ProductLimit> = limits.associateBy { it.product }

    init {
        require(byProduct.size == limits.size) { "a product may declare only one product limit" }
    }

    fun limitFor(product: ProductType): ProductLimit? = byProduct[product]

    fun evaluate(deal: Deal): ProductLimitCheck {
        val limit = byProduct[deal.product]
            ?: return ProductLimitCheck(
                deal.product,
                null,
                listOf(ProductLimitBreach(ProductLimitBreach.Rule.PRODUCT_NOT_PERMITTED, "none", deal.product.name)),
            )
        val breaches = buildList {
            val max = limit.maxPrincipal[deal.currency]
            if (max == null) {
                add(
                    ProductLimitBreach(
                        ProductLimitBreach.Rule.CURRENCY_NOT_PERMITTED,
                        limit.maxPrincipal.keys.sorted().joinToString(","),
                        deal.currency,
                    ),
                )
            } else if (deal.principal > max) {
                add(
                    ProductLimitBreach(
                        ProductLimitBreach.Rule.MAX_PRINCIPAL,
                        "${max.toPlainString()} ${deal.currency}",
                        "${deal.principal.toPlainString()} ${deal.currency}",
                    ),
                )
            }
            val tenorCap = limit.maxTenorDays
            if (tenorCap != null && deal.product != ProductType.FX_SPOT && deal.days > tenorCap) {
                add(ProductLimitBreach(ProductLimitBreach.Rule.MAX_TENOR, tenorCap.toString(), deal.days.toString()))
            }
        }
        return ProductLimitCheck(deal.product, limit, breaches)
    }
}
