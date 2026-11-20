// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.libs.domain.account.CzechAccountNumber
import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.account.IbanCheckDigits
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
 * national scheme. Both checks now hold; see [CzechAccountNumber] for the weighting. The mod-97
 * check digits come from the shared [IbanCheckDigits], and [IbanCheckDigits.build] re-validates the
 * result through [Iban], so a spec fix in libs-domain reaches every generated IBAN.
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
        return IbanCheckDigits.build(countryCode, bban)
    }
}
