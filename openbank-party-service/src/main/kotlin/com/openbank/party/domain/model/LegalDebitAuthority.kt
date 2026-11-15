// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import com.openbank.libs.domain.identifiers.AccountId
import com.openbank.libs.domain.identifiers.PartyId
import com.openbank.libs.legal.LegalEffectDisposition
import com.openbank.libs.legal.LegalEffectRevision

/**
 * Debit scope resolved from a current, verified court instrument. The caller obtains the
 * applicable snapshot at execution time. Appointment and restrictions on the holder are
 * independent facts. Court approvals and transaction-specific consent are separate workflows.
 */
data class LegalDebitConstraint(val effect: LegalEffectRevision, val accountId: AccountId)

data class LegalDebitMandate(
    val effect: LegalEffectRevision,
    val accountId: AccountId,
    val representative: PartyId,
    val mayActAlone: Boolean,
)

class LegalDebitAuthority(
    private val subject: PartyId,
    private val constraints: List<LegalDebitConstraint>,
    private val mandates: List<LegalDebitMandate>,
) {
    init {
        val effects = constraints.map { it.effect } + mandates.map { it.effect }
        require(effects.all { it.identity.subjectPartyId == subject })
        require(effects.distinctBy { it.identity.effectId }.size == effects.size)
    }

    fun permits(account: AccountId, actor: PartyId): Boolean = if (actor == subject) {
        constraints.none { it.accountId == account && it.effect.disposition == LegalEffectDisposition.ACTIVE }
    } else {
        mandates.any {
            it.accountId == account &&
                it.representative == actor &&
                it.mayActAlone &&
                it.effect.disposition == LegalEffectDisposition.ACTIVE
        }
    }

    fun appliesTo(party: PartyId): Boolean = subject == party
}
