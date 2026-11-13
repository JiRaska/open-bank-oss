// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.deal

/**
 * What a treasury deal IS in the instrument model (ADR-0344): a booked position, a planned one, or
 * a what-if. Treasury books it, risk-engine values it; both use this type, so the published wire
 * strings (the enum names) have one owner. Only ACTUAL deals post, pay and enter regulatory measures.
 */
enum class DealKind {
    /** A booked deal: a real position. */
    ACTUAL,

    /** Enters the liquidity forecast only. */
    PLANNED,

    /** Priced only (pre-trade), never part of any snapshot measure. */
    HYPOTHETICAL,
    ;

    companion object {
        /** Absent means ACTUAL: every deal published before the field existed was a booked one. */
        fun parse(raw: String?): DealKind = raw?.trim()?.uppercase()?.let { v ->
            entries.firstOrNull { it.name == v } ?: throw IllegalArgumentException("unknown deal kind '$raw'")
        } ?: ACTUAL
    }
}
