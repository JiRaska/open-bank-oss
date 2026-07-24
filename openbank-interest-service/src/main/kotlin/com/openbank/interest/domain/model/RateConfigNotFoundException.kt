// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.model

/**
 * No active [InterestRateConfig] resolves for the requested (account/product, currency, date).
 *
 * Distinct from a server error: the caller asked to accrue in a currency the account has no rate for
 * (issue #1265 — rates are currency-specific, so an EUR accrual against a CZK-only product no longer
 * silently books at a wrong-currency rate; it fails closed). The REST layer maps this to 422, not 500.
 */
class RateConfigNotFoundException(val productId: String, val currency: String) :
    RuntimeException("No active rate config for product $productId in currency $currency")
