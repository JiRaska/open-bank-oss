// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.account.IbanCheckDigits

/**
 * Pure reverse of the customer edge's CZ-IBAN split: `prefix-base/bankCode` -> canonical IBAN.
 *
 * The single place this service turns Czech domestic coordinates into an IBAN (#16748). It
 * returns null unless the account number is one a Czech bank could issue: ≤6-digit prefix and
 * ≤10-digit base, each passing the national mod-11 checksum (ČNB Decree 169/2011,
 * [CzechAccountNumber.isValid]). The bank code is checked for SHAPE only (four ASCII digits):
 * no registry of assigned ČNB bank codes exists in this codebase, so an unassigned code passes.
 * The BBAN is `bankCode(4) + prefix(6) + base(10)` and the IBAN is built by the kernel
 * [IbanCheckDigits.build], the one ISO 7064 implementation, which also validates the result.
 */
internal object CzechDomesticIban {
    private const val BANK_CODE_LENGTH = 4
    private const val PREFIX_LENGTH = 6
    private const val BASE_LENGTH = 10

    fun fromAccountNumber(accountNumber: String, bankCode: String): String? {
        val bank = bankCode.trim()
        if (bank.length != BANK_CODE_LENGTH || !bank.all { it in '0'..'9' }) return null

        val parts = accountNumber.trim().replace(" ", "").split('-')
        val (prefix, base) = when (parts.size) {
            1 -> "" to parts.single()
            2 -> parts[0].takeIf { it.isNotEmpty() }?.let { it to parts[1] } ?: return null
            else -> return null
        }
        if (prefix.length > PREFIX_LENGTH || !prefix.all { it in '0'..'9' }) return null
        if (base.isEmpty() || base.length > BASE_LENGTH || !base.all { it in '0'..'9' }) return null
        if (!CzechAccountNumber.isValid(prefix, base)) return null

        val bban = CzechAccountNumber.composeBban(bankCode = bank, base = base, prefix = prefix)
        return IbanCheckDigits.build(COUNTRY, bban).value
    }

    /**
     * [fromAccountNumber], but also accepting a value that is already a valid CZ [Iban] — the
     * form some stored rows and confirmations carry. Null when it is neither.
     */
    fun canonical(accountNumber: String, bankCode: String): String? {
        val raw = accountNumber.replace(" ", "").uppercase()
        if (raw.startsWith(COUNTRY)) {
            // mod-97 proves the IBAN is well-formed, not that its BBAN is issuable: slice it and
            // hold it to the same national checksum as the split form.
            if (raw.length != CZ_IBAN_LENGTH || !Iban.isValid(raw)) return null
            val bban = raw.substring(BBAN_START)
            val prefix = bban.substring(BANK_CODE_LENGTH, BANK_CODE_LENGTH + PREFIX_LENGTH)
            val base = bban.substring(BANK_CODE_LENGTH + PREFIX_LENGTH)
            return raw.takeIf { CzechAccountNumber.isValid(prefix, base) }
        }
        return fromAccountNumber(accountNumber, bankCode)
    }

    private const val COUNTRY = "CZ"
    private const val CZ_IBAN_LENGTH = 24
    private const val BBAN_START = 4
}
