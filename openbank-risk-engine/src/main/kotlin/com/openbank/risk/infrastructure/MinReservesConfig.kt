// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.domain.reserves.MinReserveParameters
import com.openbank.risk.domain.reserves.ReserveClass
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.math.BigDecimal

/**
 * `openbank.risk.min-reserves.*` — the versioned ČNB minimum reserve parameter set (ADR-0313
 * treasury gap, ADR-0315). A config MAPPING: every member is required, so a deployment missing the
 * rate fails at startup instead of answering with a defaulted requirement. Values and citations
 * live in application.yaml.
 */
@ConfigMapping(prefix = "openbank.risk.min-reserves")
interface MinReservesConfig {
    @WithName("parameter-set-id")
    fun parameterSetId(): String

    @WithName("parameter-set-version")
    fun parameterSetVersion(): String

    fun source(): String

    fun rate(): BigDecimal

    @WithName("remuneration-rate")
    fun remunerationRate(): BigDecimal

    @WithName("holding-currency")
    fun holdingCurrency(): String

    fun classification(): Classification

    interface Classification {
        /** GL code → class wire name. */
        @WithName("gl-accounts")
        fun glAccounts(): Map<String, String>

        /** GL account TYPE (ASSET, EQUITY, …) → class, for accounts [glAccounts] does not name. */
        @WithName("gl-account-types")
        fun glAccountTypes(): Map<String, String>
    }
}

fun MinReservesConfig.toParameters(): MinReserveParameters = MinReserveParameters(
    id = parameterSetId(),
    version = parameterSetVersion(),
    source = source(),
    rate = rate(),
    remunerationRate = remunerationRate(),
    holdingCurrency = holdingCurrency().trim().uppercase(),
    glAccounts = classification().glAccounts().mapKeys { it.key.trim() }.mapValues { ReserveClass.parse(it.value) },
    glAccountTypes = classification().glAccountTypes()
        .mapKeys { it.key.trim().uppercase() }
        .mapValues { ReserveClass.parse(it.value) },
)
