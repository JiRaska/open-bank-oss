// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.port.out

import com.openbank.cardissuance.domain.model.CardNetwork

/**
 * The subset of `openbank-product-catalog`'s `Product.cardConfig` that card-issuance enforces.
 * `minCards`, `eligibilityMinAge` and `eligibilitySegments` are deliberately NOT modelled here:
 * they are onboarding-time rules that this service has no input to evaluate (it never sees a date
 * of birth or a customer segment).
 */
data class CardProductConfig(
    val enabled: Boolean,
    val maxCards: Int,
    val networks: List<CardNetwork>,
    val tiers: List<String>,
    val virtualCardAllowed: Boolean,
    val contactlessEnabled: Boolean,
    val monthlyFeePerCard: Double,
    val cardCurrency: String?,
)

/**
 * Outcome of resolving a product's card configuration.
 *
 * [Unavailable] deliberately conflates "product-catalog said 404" with "product-catalog did not
 * answer" — unlike account-service's `ProductLookupResult`, which keeps them apart. Both collapse
 * to the same **permissive** behaviour here (see [CardProductCatalogPort]), so a second case would
 * be a distinction the caller cannot act on.
 */
sealed interface CardConfigLookup {
    data class Found(val config: CardProductConfig) : CardConfigLookup
    data object Unavailable : CardConfigLookup
}

/**
 * Outbound port resolving a product's [CardProductConfig] by product **code**.
 *
 * **Fail-open by design.** Implementations MUST resolve to [CardConfigLookup.Unavailable] — never
 * throw — when the product code is unknown, the call fails, or it times out. Two reasons:
 *
 *  1. The card quota is a *soft product rule*, not a security control. Nothing about issuing one
 *     card too many is unsafe; it is a commercial constraint the bank can reconcile after the fact.
 *  2. `openbank-product-catalog` is KEDA scale-to-zero in this environment. Failing closed would
 *     take card issuance down every single time the catalog scales in — trading a rule that does
 *     not protect anyone for a real outage on the customer-facing path.
 *
 * The trade-off is only acceptable because it is loud: implementations MUST log a warning naming
 * the product code and the failure, so a permanently-unreachable catalog shows up in the logs
 * rather than silently disabling every entitlement rule.
 */
interface CardProductCatalogPort {
    suspend fun findCardConfig(productCode: String): CardConfigLookup
}
