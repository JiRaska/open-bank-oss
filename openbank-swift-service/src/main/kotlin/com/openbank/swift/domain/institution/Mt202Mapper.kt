// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.domain.institution

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The legacy FIN MT202 (General Financial Institution Transfer) rendering of an
 * [InstitutionTransfer] — kept beside the pacs.009 for audit and for counterparties that still
 * reconcile on MT fields. The wire message is the pacs.009; this text is never submitted.
 *
 * Field mapping: `:20:` transaction reference (16 x, from the payment id), `:21:` related
 * reference (`NONREF`), `:32A:` value date + currency + amount (comma decimal), `:52A:` ordering
 * institution (debtor BIC), `:53B:` sender's correspondent account (debtor account), `:56A:`
 * intermediary (when present), `:57A:` account-with institution (creditor BIC), `:58A:`
 * beneficiary institution (creditor account + BIC), `:72:` sender-to-receiver information
 * (`/BNF/` + remittance, in 35-character lines, at most six).
 */
object Mt202Mapper {
    private val VALUE_DATE = DateTimeFormatter.ofPattern("yyMMdd")
    private const val LINE = 35
    private const val MAX_72_LINES = 6
    private const val REFERENCE_LENGTH = 16

    /** Characters outside the SWIFT `x` character set are replaced, never passed through. */
    private val NOT_X_CHARSET = Regex("[^A-Za-z0-9/?:().,'+ -]")

    fun render(t: InstitutionTransfer): String = buildString {
        append("{1:F01").append(padBic(t.debtor.bic)).append("0000000000}")
        append("{2:I202").append(padBic(t.creditor.bic)).append("N}")
        append("{4:\n")
        append(":20:").append(reference(t)).append('\n')
        append(":21:NONREF\n")
        append(":32A:").append(t.valueDate.format(VALUE_DATE)).append(t.currency)
            .append(t.amount.amount.toPlainString().replace('.', ',')).append('\n')
        append(":52A:").append(t.debtor.bic).append('\n')
        append(":53B:/").append(t.debtor.accountNumber).append('\n')
        t.intermediaryBic?.let { append(":56A:").append(it).append('\n') }
        append(":57A:").append(t.creditor.bic).append('\n')
        append(":58A:/").append(t.creditor.accountNumber).append('\n').append(t.creditor.bic).append('\n')
        t.remittanceInformation?.let { info ->
            val lines = ("/BNF/" + info.replace(NOT_X_CHARSET, " ")).chunked(LINE).take(MAX_72_LINES)
            append(":72:").append(lines.joinToString("\n")).append('\n')
        }
        append("-}")
    }

    /** `:20:` — the payment id's first 16 hex digits, upper case; deterministic per payment. */
    fun reference(t: InstitutionTransfer): String =
        t.paymentId.toString().replace("-", "").take(REFERENCE_LENGTH).uppercase(Locale.ROOT)

    /** A logical-terminal address is the 8-character BIC + `X` + the branch (default `XXX`). */
    @Suppress("MagicNumber")
    private fun padBic(bic: String): String = bic.take(8) + "X" + (if (bic.length == 11) bic.substring(8) else "XXX")
}
