// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import com.openbank.risk.domain.irrbb.IrrbbParameters
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * The model and parameter versions in force when a snapshot run was recorded (ADR-0314 D2).
 *
 * RECORDED in the manifest, never hashed into [InputHash]: positions are built from the ledger,
 * lending and treasury reads alone, and no parameter set below changes a single position — they
 * are applied by the analytic reads (capital, liquidity, IRRBB, reserves) over those positions.
 * Folding them into the input hash would make a parameter bump look like new ledger knowledge
 * and mint a second, position-identical run for the same as-of.
 *
 * The IRRBB shocks are configured as raw sizes with no set id or version, so their "version" is
 * [irrbbShockSetVersion]: a SHA-256 fingerprint of the canonical sizes and floor — two runs with
 * the same fingerprint were recorded under the same shocks.
 */
data class ModelVersions(
    /** The engine's build version (`quarkus.application.version`, derived from `version.txt`). */
    val engineVersion: String,
    val capitalSetId: String,
    val capitalSetVersion: String,
    val liquiditySetId: String,
    val liquiditySetVersion: String,
    val irrbbShockSetVersion: String,
    val irrbbShockSource: String,
    val minReservesSetId: String,
    val minReservesSetVersion: String,
    val behaviouralModelId: String,
    val behaviouralModelVersion: String,
) {
    companion object {
        private const val FINGERPRINT_HEX = 16

        /** `sha256:<16 hex>` over the sorted per-currency sizes and the floor (or `none`). */
        fun irrbbFingerprint(params: IrrbbParameters): String {
            val sizes = params.shockSizes.toSortedMap().map { (ccy, s) ->
                "$ccy=${s.parallelBp.c()}/${s.shortBp.c()}/${s.longBp.c()}"
            }
            val floor = params.floor?.let { "floor=${it.atZeroBp.c()}/${it.slopeBpPerYear.c()}" } ?: "floor=none"
            val canonical = (sizes + floor).joinToString("\n")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return "sha256:" + digest.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_HEX)
        }

        private fun BigDecimal.c(): String = stripTrailingZeros().toPlainString()
    }
}
