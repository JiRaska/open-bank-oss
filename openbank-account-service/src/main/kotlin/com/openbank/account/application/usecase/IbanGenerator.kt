// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.account.application.usecase

import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Generates Czech IBANs with a **nationally valid BBAN** (ČNB 169/2011 Sb.).
 *
 * The BBAN is `bankCode(4) + prefix(6) + base(10)`, where the base satisfies the Czech mod-11
 * weighted checksum (and the empty prefix trivially does). Only then do we add the ISO 13616
 * mod-97-10 IBAN check digits. The previous implementation emitted a padded `System.nanoTime()`
 * tail with a hard-coded `0000` bank code: it passed the generic IBAN mod-97 test but produced a
 * BBAN that no Czech bank could issue (no mod-11) — i.e. an account that does not exist in the
 * national scheme. Both checks now hold; see [CzechAccountNumber] for the weighting.
 *
 * [bankCode] is the 4-digit ČNB-assigned bank identifier (config `openbank.account.bank-code`).
 * The sandbox default is the reserved placeholder `0000`, which is **not** assigned to any real
 * Czech bank, so generated IBANs never impersonate a live institution (the earlier `2010` default
 * is Fio banka's real code). The bank code does not participate in the mod-11 account checksum —
 * that is the prefix+base — so any 4-digit value still yields a nationally valid BBAN. A production
 * deployment MUST set the real assigned code.
 */
@ApplicationScoped
class IbanGenerator(
    @ConfigProperty(name = "openbank.account.bank-code", defaultValue = "0000")
    private val bankCode: String,
) {
    private val countryCode = "CZ"

    fun generate(currency: CurrencyCode): Iban {
        val base = CzechAccountNumber.generateBase()
        val bban = CzechAccountNumber.composeBban(bankCode = bankCode, base = base)
        val checkDigits = calculateCheckDigits(countryCode, bban)
        return Iban.of("$countryCode$checkDigits$bban")
    }

    /** ISO 13616 / ISO 7064 mod-97-10: rearrange `bban + country + "00"`, then 98 − (n mod 97). */
    private fun calculateCheckDigits(countryCode: String, bban: String): String {
        val rearranged = bban + countryCode + "00"
        val numeric = rearranged.map { c ->
            if (c.isDigit()) c.toString() else (c.code - 'A'.code + 10).toString()
        }.joinToString("")
        val remainder = numeric.toBigInteger().mod(97.toBigInteger()).toInt()
        val checkDigits = 98 - remainder
        return checkDigits.toString().padStart(2, '0')
    }
}
