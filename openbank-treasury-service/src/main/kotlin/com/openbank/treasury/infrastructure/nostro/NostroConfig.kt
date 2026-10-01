// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.nostro

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.math.BigDecimal

/**
 * `openbank.treasury.nostro.accounts`: nostro IBAN -> treasury GL code (1001 CZK, 1002 EUR). The
 * IBAN is the map KEY, which is safe here: an IBAN carries no dot, so SmallRye's quoting of dotted
 * leaf keys cannot bite.
 */
@ConfigMapping(prefix = "openbank.treasury.nostro")
interface NostroConfig {
    fun accounts(): Map<String, String>

    /** ADR-0315 D7: an open break this many BUSINESS days old (or older) alerts. */
    @WithDefault("3")
    fun breakAlertAgeDays(): Int

    /** ... and only when its amount, in its own currency, is at least this. 0 = every aged break. */
    @WithDefault("0")
    fun breakAlertMinAmount(): BigDecimal
}
