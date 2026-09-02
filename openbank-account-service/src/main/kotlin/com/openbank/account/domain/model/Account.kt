// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.domain.model

import com.openbank.libs.domain.account.Iban
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class Account(
    val id: UUID,
    val accountNumber: Iban,
    val accountType: AccountType,
    val partyId: UUID,
    val productId: UUID,
    val currency: CurrencyCode,
    val status: AccountStatus,
    val signingRule: SigningRule = SigningRule.SINGLE,
    val openedAt: Instant,
    val closedAt: Instant?,
    val version: Long,
    /** Timestamp of the sanctions screen performed at account opening (ADR-0032 §C). Null for pre-Sprint-1 accounts. */
    val sanctionsScreenedAt: Instant? = null,
    /** Result of the sanctions screen: CLEAR | HIT | REVIEW. Null for pre-Sprint-1 accounts. */
    val sanctionsStatus: String? = null,
    /** Account holder name stored for display / statements (V12). Null after GDPR Art. 17 erasure. */
    val legalName: String? = null,
    /**
     * Optional savings goal (ADR-0153) — customer-authored label for what this account is
     * being saved toward. A goal is "set" iff [goalTargetMinorUnits] is non-null; the other
     * two fields are meaningless without it. Denominated in this account's own [currency] (no
     * cross-currency goal). Null after GDPR Art. 17 erasure, same as [legalName].
     */
    val goalName: String? = null,
    val goalTargetMinorUnits: Long? = null,
    val goalTargetDate: LocalDate? = null,
    /**
     * Customer-chosen label for this account ("Dovolená", "Firemní"), shown instead of the
     * generic account-type name. Purely cosmetic — never used for routing, matching, or any
     * authorization decision. Null means "use the account-type default name".
     */
    val nickname: String? = null,
) {
    fun canDebit(amount: Money): Boolean {
        require(amount.currency == currency) { "Currency mismatch" }
        return status == AccountStatus.ACTIVE
    }

    fun canCredit(): Boolean = status == AccountStatus.ACTIVE || status == AccountStatus.DORMANT

    fun isActive(): Boolean = status == AccountStatus.ACTIVE

    fun close(clock: Clock): Account {
        check(status == AccountStatus.ACTIVE || status == AccountStatus.DORMANT) {
            "Cannot close account in status $status"
        }
        return copy(status = AccountStatus.CLOSED, closedAt = Instant.now(clock))
    }

    fun freeze(): Account {
        check(status == AccountStatus.ACTIVE) { "Cannot freeze account in status $status" }
        return copy(status = AccountStatus.FROZEN)
    }

    fun unfreeze(): Account {
        check(status == AccountStatus.FROZEN) { "Cannot unfreeze account in status $status" }
        return copy(status = AccountStatus.ACTIVE)
    }

    /** Activate an onboarding account once its party has cleared KYC + AML (ADR-0267). */
    fun activate(): Account {
        check(status == AccountStatus.PENDING_ACTIVATION) { "Cannot activate account in status $status" }
        return copy(status = AccountStatus.ACTIVE)
    }

    /** Set or clear the customer-chosen label. Blank is treated as "clear" (revert to default). */
    fun rename(nickname: String?): Account = copy(nickname = nickname?.trim()?.ifBlank { null })
}

enum class AccountType {
    CURRENT,
    SAVINGS,
    TERM_DEPOSIT,
    NOSTRO,
    GL_ASSET,
    GL_LIABILITY,
    GL_INCOME,
    GL_EXPENSE,
}

enum class AccountStatus {
    PENDING_ACTIVATION,
    ACTIVE,
    DORMANT,
    FROZEN,
    CLOSED,
}
