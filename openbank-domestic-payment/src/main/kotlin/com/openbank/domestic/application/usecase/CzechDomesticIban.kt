// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.libs.domain.account.Iban

/**
 * Pure reverse of the customer edge's CZ-IBAN split: `prefix-base/bankCode` -> canonical IBAN.
 *
 * The single place this service turns Czech domestic coordinates into an IBAN (#16748). It
 * returns null unless the coordinates denote an account a Czech bank could actually issue:
 * shape (≤6-digit prefix, ≤10-digit base, 4-digit bank code) AND the national mod-11 checksum
 * on both prefix and base (ČNB Decree 169/2011, [CzechAccountNumber.isValid]). The BBAN is
 * `bankCode(4) + prefix(6) + base(10)` and the result is constructed as a kernel [Iban], whose
 * constructor re-checks the mod-97 digits, so a wrong check digit cannot leave this object.
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
        return Iban.of("CZ${checkDigits(bban)}$bban").value
    }

    /** ISO 13616 check digits for a CZ BBAN; the result is re-validated by the [Iban] constructor. */
    @Suppress("MagicNumber")
    private fun checkDigits(bban: String): String {
        var remainder = 0
        for (digit in bban + "123500") remainder = (remainder * 10 + digit.digitToInt()) % 97
        return (98 - remainder).toString().padStart(2, '0')
    }
}
