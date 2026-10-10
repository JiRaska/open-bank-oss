// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.portfolio

import io.smallrye.config.ConfigMapping
import java.util.Optional

/**
 * `openbank.treasury.portfolio` (ADR-0337 amendment). A deployment that keeps no portfolio (the
 * bank's own treasury) sets no [entity]: its uploads are refused and its reads answer 409.
 *
 * [cfiClasses] maps a CFI PREFIX (1-6 letters, longest wins) to the instrument class reported to
 * the ČNB. Prefixes are letters only, so SmallRye's quoting of dotted leaf keys cannot bite.
 */
@ConfigMapping(prefix = "openbank.treasury.portfolio")
interface PortfolioConfig {
    fun entity(): Optional<String>

    fun safekeepingAccounts(): Optional<List<String>>

    fun cfiClasses(): Map<String, String>
}
