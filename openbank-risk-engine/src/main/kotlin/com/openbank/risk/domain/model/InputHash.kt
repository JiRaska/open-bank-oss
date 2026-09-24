// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * SHA-256 over a canonical serialisation of both ledger reads (ADR-0314 D2).
 *
 * Canonical means: one line per record, fields in a fixed order, amounts as
 * `stripTrailingZeros().toPlainString()` (so a scale change the ledger's JSON may introduce is not
 * a different input), and the lines SORTED — the ledger promises no ordering, so hashing arrival
 * order would make two identical ledgers look like different knowledge and defeat the replay key.
 */
object InputHash {

    fun of(inputs: LedgerInputs): String {
        val tb = inputs.trialBalance.map {
            listOf(
                "TB",
                it.glAccountCode,
                it.glAccountType,
                it.currency,
                it.totalDebit.c(),
                it.totalCredit.c(),
                it.net.c(),
            )
                .joinToString("|")
        }
        val sl = inputs.subLedger.map {
            listOf("SL", it.subAccountId.toString(), it.currency, it.totalDebit.c(), it.totalCredit.c())
                .joinToString("|")
        }
        val canonical = (listOf("asOf|${inputs.asOf}") + (tb + sl).sorted()).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun BigDecimal.c(): String = if (signum() == 0) "0" else stripTrailingZeros().toPlainString()
}
