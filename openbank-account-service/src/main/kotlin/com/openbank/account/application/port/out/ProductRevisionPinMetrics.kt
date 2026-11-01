// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

/**
 * Every way an attempt to pin a product revision at opening can end (ADR-0341 D1). Each is its own
 * value: a refused opening never shares a tag with a pinned one (the ADR-0252 phase 0 lesson).
 */
enum class PinOutcome {
    PINNED,
    PRODUCT_NOT_FOUND,
    PRODUCT_NOT_ELIGIBLE,
    NO_PUBLISHED_REVISION,
    CATALOG_UNAVAILABLE,
    ;

    val tag: String get() = name.lowercase()
}

/** Counts pin outcomes as `openbank.account.product_revision.pin{outcome}`. */
fun interface ProductRevisionPinMetrics {
    fun record(outcome: PinOutcome)
}
