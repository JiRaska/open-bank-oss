// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.identifiers.AccountId
import com.openbank.libs.legal.LegalEffectDisposition
import com.openbank.libs.legal.LegalEffectRevision

/** Regime and scope established by Account from a verified, currently applicable effect. */
enum class LegalProtectionRegime { CZ_LINKED_ACCOUNT, SK_EXEMPTION, DE_P_KONTO }

data class LegalAccountProtection(
    val effect: LegalEffectRevision,
    val regime: LegalProtectionRegime,
    val debitAccount: AccountId,
    val attachedAccount: AccountId,
) {
    init {
        require((regime == LegalProtectionRegime.CZ_LINKED_ACCOUNT) == (debitAccount != attachedAccount))
    }

    fun permits(debit: Account, attached: Account): Boolean {
        if (effect.disposition != LegalEffectDisposition.ACTIVE) return false
        if (debit.id != debitAccount.value || attached.id != attachedAccount.value) return false
        if (debit.partyId != effect.identity.subjectPartyId.value || debit.partyId != attached.partyId) return false
        return debit.currency == attached.currency && debit.currency.code == currency
    }

    private val currency: String
        get() = if (regime == LegalProtectionRegime.CZ_LINKED_ACCOUNT) "CZK" else "EUR"
}
