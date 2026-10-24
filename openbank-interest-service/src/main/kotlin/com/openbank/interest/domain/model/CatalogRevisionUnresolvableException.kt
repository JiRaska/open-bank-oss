// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.domain.model

import java.util.UUID

/**
 * A catalog-sourced rate applies to the account but the revision it is pinned to cannot be resolved
 * to a cached rate config (ADR-0341). Accruing at "the currently effective" rate instead would
 * reprice a live contract, so the accrual fails visibly and is retried once the cache is repaired.
 */
class CatalogRevisionUnresolvableException(
    val accountId: UUID,
    val productId: String,
    val currency: String,
    /** True when the cause was a catalog-sourced rate with no pin (counted as `rate.unpinned`, not as unresolved). */
    val unpinnedCatalogRate: Boolean = false,
) : RuntimeException(
    "Account $accountId is catalog-pinned for $productId/$currency but its revision's rate config is unresolvable",
)
