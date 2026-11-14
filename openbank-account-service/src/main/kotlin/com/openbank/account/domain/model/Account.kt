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
    /**
     * Terms version this deposit was opened under (#9044) — the record a complaints/conduct
     * review asks for first. Non-null for every TERM_DEPOSIT opened after V24 (enforced by
     * chk_accounts_terms); null means a pre-V24 account, which has no truthful record and was
     * deliberately NOT backfilled from openedAt (that would manufacture evidence).
     */
    val termsVersion: String? = null,
    /** Snapshot of the terms document reference at opening (URL + effective-from date). */
    val termsUrl: String? = null,
    val termsEffectiveFrom: LocalDate? = null,
    /**
     * The published catalog revision this account was opened under (ADR-0341 D1, V33). Non-null for
     * every account opened since V33; null means an account opened before it, which has no truthful
     * pin and is not backfilled silently (see the service docs for the backfill plan).
     */
    val productRevisionPin: ProductRevisionPin? = null,
    /**
     * Who froze this account (V34); non-null only while [status] is FROZEN. Null on a FROZEN row
     * means it was frozen before V34 and is treated as [FreezeOrigin.OPERATOR]: it is never lifted
     * automatically.
     */
    val freezeOrigin: FreezeOrigin? = null,
) {
    fun canDebit(amount: Money): Boolean {
        require(amount.currency == currency) { "Currency mismatch" }
        return standing.mayDebit
    }

    /**
     * Whether money may leave or enter this account right now (ADR-0374 D4). This is the answer the
     * payment rails read with the account; [canDebit] and [canCredit] are the same rule.
     */
    val standing: AccountStanding
        get() = AccountStanding(
            mayDebit = status == AccountStatus.ACTIVE,
            mayCredit = canCredit(),
            reason = when (status) {
                AccountStatus.ACTIVE -> null
                AccountStatus.PENDING_ACTIVATION -> StandingReason.ACCOUNT_PENDING_ACTIVATION
                AccountStatus.DORMANT -> StandingReason.ACCOUNT_DORMANT
                AccountStatus.FROZEN -> StandingReason.ACCOUNT_FROZEN
                AccountStatus.CLOSED -> StandingReason.ACCOUNT_CLOSED
            },
        )

    fun canCredit(): Boolean = status == AccountStatus.ACTIVE || status == AccountStatus.DORMANT

    fun isActive(): Boolean = status == AccountStatus.ACTIVE

    fun close(clock: Clock): Account {
        check(status == AccountStatus.ACTIVE || status == AccountStatus.DORMANT) {
            "Cannot close account in status $status"
        }
        return copy(status = AccountStatus.CLOSED, closedAt = Instant.now(clock))
    }

    /**
     * Freeze an ACTIVE account. An operator may also freeze an account that is already frozen
     * because of its party's standing: the freeze then becomes the operator's, so the party
     * returning to good standing no longer lifts it.
     */
    fun freeze(origin: FreezeOrigin = FreezeOrigin.OPERATOR): Account {
        if (status == AccountStatus.FROZEN &&
            freezeOrigin == FreezeOrigin.PARTY_STANDING &&
            origin == FreezeOrigin.OPERATOR
        ) {
            return copy(freezeOrigin = FreezeOrigin.OPERATOR)
        }
        check(status == AccountStatus.ACTIVE) { "Cannot freeze account in status $status" }
        return copy(status = AccountStatus.FROZEN, freezeOrigin = origin)
    }

    fun unfreeze(): Account {
        check(status == AccountStatus.FROZEN) { "Cannot unfreeze account in status $status" }
        return copy(status = AccountStatus.ACTIVE, freezeOrigin = null)
    }

    /** True for a freeze that the party's return to good standing may lift. */
    fun isFrozenByPartyStanding(): Boolean =
        status == AccountStatus.FROZEN && freezeOrigin == FreezeOrigin.PARTY_STANDING

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

/** Who froze an account, which decides who may lift the freeze. */
enum class FreezeOrigin {
    /** A person or a control acting on this account. Lifted only by an explicit unfreeze. */
    OPERATOR,

    /** The owning party lost its standing (suspended or closed). Lifted when the party is ACTIVE again. */
    PARTY_STANDING,
}

/** Why an account may not be debited. The freeze origin is deliberately not part of it. */
enum class StandingReason {
    ACCOUNT_PENDING_ACTIVATION,
    ACCOUNT_DORMANT,
    ACCOUNT_FROZEN,
    ACCOUNT_CLOSED,
}

data class AccountStanding(val mayDebit: Boolean, val mayCredit: Boolean, val reason: StandingReason?)

enum class AccountStatus {
    PENDING_ACTIVATION,
    ACTIVE,
    DORMANT,
    FROZEN,
    CLOSED,
}
