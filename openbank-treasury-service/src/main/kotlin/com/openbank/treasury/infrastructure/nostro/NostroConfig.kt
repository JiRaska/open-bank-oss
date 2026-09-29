// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.nostro

import io.smallrye.config.ConfigMapping

/**
 * `openbank.treasury.nostro.accounts`: nostro IBAN -> treasury GL code (1001 CZK, 1002 EUR). The
 * IBAN is the map KEY, which is safe here: an IBAN carries no dot, so SmallRye's quoting of dotted
 * leaf keys cannot bite.
 */
@ConfigMapping(prefix = "openbank.treasury.nostro")
interface NostroConfig {
    fun accounts(): Map<String, String>
}
