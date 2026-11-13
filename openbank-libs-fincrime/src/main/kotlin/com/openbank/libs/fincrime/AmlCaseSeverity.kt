// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime

/**
 * How urgent an AML case is — aml-service's published `riskLevel` (ADR-0353 D3). It rates the
 * ALERT, not the customer: a customer's risk rating is kyc-service's and is never derived from,
 * or mapped onto, this scale (ADR-0353 D1).
 *
 * The wire spelling is the enum name, unchanged since aml-service's first migration; the rails
 * send it when they open a case, aml-service persists it in `aml_cases.risk_level`.
 */
enum class AmlCaseSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
    ;

    companion object {
        /**
         * Parses a wire value. Returns `null` for an absent or unrecognised value so the caller has
         * to decide what an unreadable severity means — it is never silently read as [LOW].
         */
        fun fromWire(value: String?): AmlCaseSeverity? = entries.firstOrNull { it.name == value?.trim()?.uppercase() }
    }
}
