// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.libs.domain.cards.scheme

import java.time.LocalDate

/**
 * The card-network capabilities this platform can bind, one port each (ADR-0283 D1).
 *
 * ## Why these live in libs-domain
 *
 * A port is a domain contract, and more than one service will hold one: card-processing wants
 * merchant data on a clearing, a future disputes service wants the scheme's case API, the Card
 * Center wants the capability matrix. Putting them in the domain library is what stops each service
 * inventing its own shape and makes "the bank chooses the network per product" a binding decision
 * rather than a code fork.
 *
 * ## What a port must never carry
 *
 * No framework types (this module has zero framework imports, ADR-0002, enforced by
 * `check-domain-purity.py`), and **no PAN**. Every identifier below is a token, a BIN range or an
 * opaque reference. A port that took a PAN would drag its every caller into the cardholder-data
 * environment, which is the boundary the whole card platform is built to keep.
 *
 * ## Suspend, deliberately
 *
 * Every method is `suspend`: these are network calls at their only honest layer. A blocking
 * signature would push every adapter into wrapping a reactive client, which is the shape that
 * produced `HR000068` in five schedulers here.
 */

/** What a card's issuer range says about it. No PAN — a BIN is the first 6 to 8 digits. */
data class BinAttributes(
    val bin: String,
    val brand: String,
    val productType: String?,
    val fundingSource: FundingSource,
    val issuerName: String?,
    val issuerCountry: String?,
)

enum class FundingSource { DEBIT, CREDIT, PREPAID, DEFERRED_DEBIT, UNKNOWN }

/**
 * Issuer-range attributes for a card number's BIN.
 *
 * The first capability bound on purpose: read-only, no cardholder data, free in both networks'
 * sandboxes, and the missing writer for the merchant/BIN reference data the transaction
 * categoriser has been starved of (#8573).
 */
interface BinLookupPort {
    suspend fun lookup(bin: String): SchemeResult<BinAttributes>
}

/**
 * A merchant as the network knows it.
 *
 * [descriptor] is what the acquirer put on the wire — the string a customer sees on a statement and
 * frequently cannot recognise. Everything else is what the network can add to it.
 */
data class MerchantDescriptor(
    val descriptor: String,
    val mcc: String?,
    val countryCode: String?,
    val postalCode: String? = null,
)

data class MerchantIdentity(
    val name: String,
    val mcc: String?,
    val countryCode: String?,
    val city: String? = null,
    val website: String? = null,
    /** The network's own merchant identifier, where it exposes one. Opaque; never parsed. */
    val networkMerchantId: String? = null,
)

interface MerchantDataPort {
    suspend fun identify(descriptor: MerchantDescriptor): SchemeResult<MerchantIdentity>
}

/** A network token standing in for a card at one merchant or wallet. Never contains a PAN. */
data class NetworkToken(
    val tokenReference: String,
    val last4: String,
    val status: NetworkTokenStatus,
    val expiry: LocalDate?,
    val requestorId: String?,
)

/**
 * A network token's lifecycle state.
 *
 * Named for the network token specifically, not `TokenStatus`: this is a SHARED domain library, and
 * a generic name there collides with every other kind of token a service might model. The values
 * still overlap several unrelated spec enums by coincidence, which `check-openapi-enum-vs-domain.py`
 * pairs on — see its BASELINE for the two entries that records.
 */
enum class NetworkTokenStatus { ACTIVE, SUSPENDED, DELETED }

/** Who asked for the token — a wallet, a merchant holding a card on file, or the bank's own app. */
data class TokenRequestor(val requestorId: String, val label: String)

interface TokenisationPort {
    suspend fun provision(cardReference: String, requestor: TokenRequestor): SchemeResult<NetworkToken>

    suspend fun listTokens(cardReference: String): SchemeResult<List<NetworkToken>>

    /**
     * Suspend, resume or delete. One method rather than three because the scheme APIs model it as a
     * state change and splitting it would invite a caller to invent a fourth state.
     */
    suspend fun changeStatus(tokenReference: String, status: NetworkTokenStatus): SchemeResult<NetworkToken>
}

/**
 * Funds pushed TO a card.
 *
 * Amounts are minor units, matching the card money path. A `BigDecimal` here would put a rounding
 * decision at the boundary where the scheme message has none.
 */
data class PushPaymentInstruction(
    val recipientTokenReference: String,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val idempotencyKey: String,
    val reference: String?,
)

data class PushPaymentReceipt(val networkReference: String, val accepted: Boolean, val detail: String?)

interface PushPaymentPort {
    suspend fun push(instruction: PushPaymentInstruction): SchemeResult<PushPaymentReceipt>
}

/**
 * A dispute as the NETWORK models it.
 *
 * [reasonCode] is the scheme's own code, carried verbatim and never translated here: the bank-side
 * lifecycle (ADR-0117) has its own vocabulary, and a mapping in the port would make the two
 * disagree in exactly the place a chargeback deadline is calculated.
 */
data class SchemeDispute(
    val networkCaseId: String,
    val reasonCode: String,
    val amountMinorUnits: Long,
    val currencyCode: String,
    val respondByDate: LocalDate?,
    val status: String,
)

data class DisputeEvidence(val networkCaseId: String, val documentReference: String, val note: String?)

interface DisputePort {
    suspend fun open(
        authorizationNetworkReference: String,
        reasonCode: String,
        amountMinorUnits: Long,
        currencyCode: String,
    ): SchemeResult<SchemeDispute>

    suspend fun submitEvidence(evidence: DisputeEvidence): SchemeResult<SchemeDispute>

    suspend fun status(networkCaseId: String): SchemeResult<SchemeDispute>
}

/** What the network says happened to a card a merchant holds on file. */
data class AccountUpdate(
    val cardReference: String,
    val outcome: AccountUpdateOutcome,
    val newLast4: String?,
    val newExpiry: LocalDate?,
)

enum class AccountUpdateOutcome { UNCHANGED, REISSUED, CLOSED, CONTACT_CARDHOLDER }

interface AccountUpdaterPort {
    suspend fun check(cardReferences: List<String>): SchemeResult<List<AccountUpdate>>
}
