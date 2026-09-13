// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

/** Pure reverse of the customer edge's CZ-IBAN split: `prefix-base/bankCode` -> canonical IBAN. */
internal object CzechDomesticIban {
    private const val BANK_CODE_LENGTH = 4
    private const val PREFIX_LENGTH = 6
    private const val BASE_LENGTH = 10
    private const val RADIX = 10
    private const val MODULUS = 97
    private const val CHECK_DIGIT_COMPLEMENT = 98

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

        val bban = bank + prefix.padStart(PREFIX_LENGTH, '0') + base.padStart(BASE_LENGTH, '0')
        var remainder = 0
        for (digit in bban + "123500") {
            remainder = (remainder * RADIX + digit.digitToInt()) % MODULUS
        }
        val checkDigits = (CHECK_DIGIT_COMPLEMENT - remainder).toString().padStart(2, '0')
        return "CZ$checkDigits$bban"
    }
}
