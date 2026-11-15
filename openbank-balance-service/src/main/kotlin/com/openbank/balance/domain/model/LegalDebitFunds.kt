// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import com.openbank.libs.domain.identifiers.AccountId
import com.openbank.libs.domain.identifiers.PartyId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.legal.LegalEffectDisposition
import com.openbank.libs.legal.LegalEffectRevision
import java.math.BigDecimal

data class LegalAttachment(val effect: LegalEffectRevision, val account: AccountId, val remainingClaim: Money) {
    init {
        require(remainingClaim.amount.signum() > 0)
    }
}

/**
 * One calculation over already resolved and allocated effects. The protected amount is the
 * unconsumed verified amount, net of any prior allocations against it. Claim amounts refer to
 * this account; an order spanning accounts needs an upstream serialized allocation budget.
 */
object LegalDebitFunds {
    /** The caller must already have verified authority and serialize the resulting reservation. */
    fun reserve(
        balance: Balance,
        subject: PartyId,
        attachments: List<LegalAttachment>,
        verifiedProtection: Money,
        amount: Money,
    ): Balance? {
        require(amount.amount.signum() > 0)
        if (amount > available(balance, subject, attachments, verifiedProtection)) return null
        return balance.withReservation(amount.amount)
    }

    fun available(
        balance: Balance,
        subject: PartyId,
        attachments: List<LegalAttachment>,
        verifiedProtection: Money,
    ): Money {
        require(verifiedProtection.currency.code == balance.currency)
        require(verifiedProtection.amount.signum() >= 0)
        require(attachments.distinctBy { it.effect.identity.effectId }.size == attachments.size)
        require(attachments.all { it.remainingClaim.currency == verifiedProtection.currency })
        val account = AccountId(balance.accountId)
        require(attachments.all { it.effect.identity.subjectPartyId == subject && it.account == account })
        val funded = balance.effectiveAvailable().max(BigDecimal.ZERO)
        val protected = funded.min(verifiedProtection.amount)
        val claims = attachments.filter {
            it.effect.disposition == LegalEffectDisposition.ACTIVE
        }.fold(BigDecimal.ZERO) { total, attachment -> total + attachment.remainingClaim.amount }
        val held = claims.min(funded - protected)
        return Money.of(funded - held, balance.currency)
    }
}
