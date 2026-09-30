// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.quote

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

/**
 * `openbank.treasury.simulated-market.quotes` (ADR-0315 D9). [spreadBp] maps a counterparty id to
 * its half-spread in basis points around the curve mid. The id is the map KEY, which is safe: a
 * counterparty id carries no dot, so SmallRye's quoting of dotted leaf keys cannot bite.
 */
@ConfigMapping(prefix = "openbank.treasury.simulated-market.quotes")
interface SimulatedQuoteConfig {
    @WithDefault("false")
    fun enabled(): Boolean

    fun spreadBp(): Map<String, Int>
}
