// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime

import java.math.BigDecimal
import java.util.UUID

/**
 * The typed risk context a caller hands fraud-service (ADR-0377 D3, first slice).
 *
 * Fields every caller always knows are non-nullable. Where a value is genuinely unknown the type
 * says so with an explicit variant and a reason ([CounterpartyRef.Unknown]) — never with null, so
 * a missing input and an absent one cannot share a representation. Evidence only some callers
 * have is typed and optional. Velocity, payee age and payee history are NOT here: fraud-service
 * derives those itself (ADR-0354 §1).
 */
data class FraudRiskContext(
    val rail: String,
    val amount: BigDecimal,
    val currency: String,
    val debtorAccountId: UUID,
    val initiationMode: InitiationMode,
    val counterparty: CounterpartyRef,
    val evidence: FraudRiskEvidence = FraudRiskEvidence(),
) {
    init {
        require(rail.isNotBlank()) { "rail is required" }
        require(currency.matches(ISO_4217)) { "currency must be a 3-letter ISO 4217 code" }
    }

    private companion object {
        val ISO_4217 = Regex("^[A-Z]{3}$")
    }
}

/** Who or what started the operation. Closed set: a new mode is a contract change. */
enum class InitiationMode {
    /** The account holder, authenticated in this session, instructed it now. */
    CUSTOMER_PRESENT,

    /** Executed from a standing order the holder set up earlier; nobody is present. */
    STANDING_INSTRUCTION,

    /** Collected under a mandate the debtor granted to a creditor (e.g. a direct debit). */
    MANDATE,

    /** Initiated through a licensed third-party provider on the holder's consent (PSD2 PIS). */
    THIRD_PARTY_PROVIDER,

    /** Instructed by a person acting under a delegation granted by the holder. */
    DELEGATE,

    /** A retry the platform issued for an operation already decided once; no new instruction. */
    SYSTEM_RETRY,
}

/** The surface the instruction arrived through. Optional evidence. */
enum class InitiationChannel {
    MOBILE_APP,
    WEB,
    API,
    BRANCH,
    BACK_OFFICE,
    SCHEDULER,
}

/** Verification of Payee outcome (ADR-0171), when the rail ran one. */
enum class VopResult {
    MATCH,
    CLOSE_MATCH,
    NO_MATCH,
    NOT_POSSIBLE,
}

/** Evidence only some callers have. Every field is optional because absence here is normal. */
data class FraudRiskEvidence(
    val channel: InitiationChannel? = null,
    val deviceSessionRef: String? = null,
    val merchantCategoryCode: String? = null,
    val merchantCountry: String? = null,
    val vopResult: VopResult? = null,
)

/**
 * The payee, as the caller knows it. Either the payee's normalised account identifier ([Known]) or
 * an explicit statement of why there is none ([Unknown]). There is deliberately no nullable form.
 *
 * The caller does NOT hash the identifier: an unkeyed hash of an IBAN is brute-forceable (the space
 * per bank code is small and check-digit structured), so it would be pseudonymisation in name only.
 * fraud-service is the one place that keys it, with a secret it holds (ADR-0377 D3).
 */
sealed interface CounterpartyRef {

    /** The payee's account, normalised. Its `toString()` is redacted. */
    data class Known(val account: CounterpartyAccount) : CounterpartyRef

    /** No payee identifier exists, and [reason] says why. */
    data class Unknown(val reason: UnknownCounterpartyReason) : CounterpartyRef

    companion object {
        /** A payee identified by IBAN. Spaces are stripped and letters upper-cased. */
        fun fromIban(iban: String): Known = Known(CounterpartyAccount.of(CounterpartyScheme.IBAN, iban))

        /** A payee identified by a domestic account number and bank code: canonical `<bank>/<account>`. */
        fun fromAccountNumber(accountNumber: String, bankCode: String): Known {
            val account = CounterpartyAccount.normalise(accountNumber)
            val bank = CounterpartyAccount.normalise(bankCode)
            require(account.isNotEmpty() && bank.isNotEmpty()) { "account number and bank code are required" }
            return Known(CounterpartyAccount.of(CounterpartyScheme.DOMESTIC_ACCOUNT, "$bank/$account"))
        }
    }
}

/** How a [CounterpartyAccount.value] is to be read. Closed set: a new scheme is a contract change. */
enum class CounterpartyScheme {
    IBAN,
    DOMESTIC_ACCOUNT,
}

/**
 * A normalised payee account identifier. This is personal data: [toString] never prints [value],
 * so a context logged by accident does not carry it. Only [of] constructs one, so every instance is
 * normalised and two spellings of one account are equal.
 */
class CounterpartyAccount private constructor(val scheme: CounterpartyScheme, val value: String) {

    override fun equals(other: Any?): Boolean =
        other is CounterpartyAccount && other.scheme == scheme && other.value == value

    override fun hashCode(): Int = 31 * scheme.hashCode() + value.hashCode()

    override fun toString(): String = "CounterpartyAccount(scheme=$scheme, value=<redacted>)"

    companion object {
        /** Maximum normalised length accepted (an IBAN is at most 34; domestic forms are shorter). */
        const val MAX_LENGTH = 64

        fun of(scheme: CounterpartyScheme, raw: String): CounterpartyAccount {
            val value = normalise(raw)
            require(value.isNotEmpty()) { "counterparty identifier is required" }
            require(value.length <= MAX_LENGTH) { "counterparty identifier is too long" }
            return CounterpartyAccount(scheme, value)
        }

        internal fun normalise(value: String): String = value.filterNot { it.isWhitespace() }.uppercase()
    }
}

/** Why a [CounterpartyRef] is [CounterpartyRef.Unknown]. Closed set. */
enum class UnknownCounterpartyReason {
    /** Money stays within the holder's own accounts (e.g. an FX conversion); there is no payee. */
    NOT_APPLICABLE_OWN_ACCOUNT,

    /** The scheme message did not carry a payee account identifier. */
    NOT_PROVIDED_BY_SCHEME,

    /** A card payment: the payee is a merchant, described by the evidence, not an account. */
    CARD_MERCHANT,
}
