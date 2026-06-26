// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.psd2.application.port.`in`.TransactionPage
import com.openbank.psd2.domain.model.ConsentStatusOb
import com.openbank.psd2.domain.model.ObAccount
import com.openbank.psd2.domain.model.ObAmount
import com.openbank.psd2.domain.model.ObBalance
import com.openbank.psd2.domain.model.ObConsentResponse
import com.openbank.psd2.domain.model.ObTransaction
import com.openbank.psd2.domain.model.PaymentInitiationResponse
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.PaymentStatus

/**
 * Wire mappers for the **Berlin Group NextGenPSD2 XS2A 1.3.12** surface (ADR-0090 P1).
 *
 * The domain model is already Berlin-aligned, so these only translate to the exact wire shape
 * the spec mandates and our bespoke `/open-banking/v2` responses got subtly wrong:
 *  - `_links` (underscore) with each entry an `{ "href": ... }` object,
 *  - monetary `amount` rendered as a **string**, not a JSON number,
 *  - `consentStatus`/`transactionStatus` as the spec's lowerCamel enum strings.
 *
 * Czech (ČOBS) specifics ride on the same shapes and land in P3 — these base mappers stay generic.
 */
@Suppress("TooManyFunctions") // one cohesive responsibility: Berlin XS2A wire shaping (AIS + consent + PIS)
object BerlinXs2aMappers {

    private const val BASE = "/v1"

    /** Berlin `consentStatus` enum strings (RTS / NextGenPSD2 §consentStatus). */
    fun consentStatus(s: ConsentStatusOb): String = when (s) {
        ConsentStatusOb.RECEIVED -> "received"
        ConsentStatusOb.REJECTED -> "rejected"
        ConsentStatusOb.VALID -> "valid"
        ConsentStatusOb.REVOKED_BY_PSU -> "revokedByPsu"
        ConsentStatusOb.EXPIRED -> "expired"
        ConsentStatusOb.TERMINATED_BY_ASPSP -> "terminatedByAspsp"
        ConsentStatusOb.PARTIALLY_AUTHORISED -> "partiallyAuthorised"
    }

    /** Berlin/NextGenPSD2 `tppMessages` error body (single ERROR entry). */
    fun tppError(code: String, text: String? = null): Map<String, Any?> {
        val msg = buildMap<String, String> {
            put("category", "ERROR")
            put("code", code)
            text?.let { put("text", it) }
        }
        return mapOf("tppMessages" to listOf(msg))
    }

    private fun href(path: String): Map<String, String> = mapOf("href" to path)

    private fun amount(a: ObAmount): Map<String, String> =
        mapOf("currency" to a.currency, "amount" to a.amount.toPlainString())

    /** 201 response to `POST /v1/consents` — minimal per spec (status, id, links). */
    fun consentCreated(c: ObConsentResponse): Map<String, Any?> {
        val links = buildMap<String, Any> {
            put("self", href("$BASE/consents/${c.consentId}"))
            put("status", href("$BASE/consents/${c.consentId}/status"))
            put("scaStatus", href("$BASE/consents/${c.consentId}/authorisations"))
            c.links.scaRedirect?.let { put("scaRedirect", href(it)) }
        }
        return mapOf(
            "consentStatus" to consentStatus(c.consentStatus),
            "consentId" to c.consentId,
            "_links" to links,
        )
    }

    /** `GET /v1/consents/{id}` — full consent information response. */
    fun consentInformation(c: ObConsentResponse): Map<String, Any?> = mapOf(
        "access" to c.access,
        "recurringIndicator" to c.recurringIndicator,
        "validUntil" to c.validUntil.toString(),
        "frequencyPerDay" to c.frequencyPerDay,
        "combinedServiceIndicator" to false,
        "lastActionDate" to c.lastActionDate?.toString(),
        "consentStatus" to consentStatus(c.consentStatus),
    )

    /** `GET /v1/accounts` — account list with per-account balances/transactions links. */
    fun accountList(accounts: List<ObAccount>): Map<String, Any?> = mapOf(
        "accounts" to accounts.map { a ->
            mapOf(
                "resourceId" to a.resourceId,
                "iban" to a.iban,
                "currency" to a.currency,
                "name" to a.name,
                "ownerName" to a.ownerName,
                "product" to a.product,
                "cashAccountType" to a.cashAccountType,
                "_links" to mapOf(
                    "balances" to href("$BASE/accounts/${a.resourceId}/balances"),
                    "transactions" to href("$BASE/accounts/${a.resourceId}/transactions"),
                ),
            )
        },
    )

    /** `GET /v1/accounts/{id}/balances`. */
    fun balances(accountId: String, balances: List<ObBalance>): Map<String, Any?> = mapOf(
        "account" to mapOf("iban" to accountId),
        "balances" to balances.map { b ->
            mapOf(
                "balanceType" to b.balanceType,
                "balanceAmount" to amount(b.balanceAmount),
                "referenceDate" to b.referenceDate?.toString(),
                "lastChangeDateTime" to b.lastChangeDateTime?.toString(),
            )
        },
    )

    /** `GET /v1/accounts/{id}/transactions` — booked/pending lists + paging link. */
    fun transactions(accountId: String, page: TransactionPage): Map<String, Any?> {
        val tx = buildMap<String, Any> {
            put("booked", page.booked.map(::transaction))
            put("pending", page.pending.map(::transaction))
            page.nextCursor?.let {
                put("_links", mapOf("next" to href("$BASE/accounts/$accountId/transactions?afterCursor=$it")))
            }
        }
        return mapOf(
            "account" to mapOf("iban" to accountId),
            "transactions" to tx,
            "_links" to mapOf("account" to href("$BASE/accounts/$accountId")),
        )
    }

    /** Berlin payment-product path segment (`SEPA_CREDIT_TRANSFERS` -> `sepa-credit-transfers`). */
    fun productSegment(product: PaymentProduct): String = product.name.lowercase().replace('_', '-')

    /** Resolve a Berlin path segment back to the enum, or null if unknown. */
    fun productOf(segment: String): PaymentProduct? =
        runCatching { PaymentProduct.valueOf(segment.uppercase().replace('-', '_')) }.getOrNull()

    /**
     * 201 response to `POST /v1/payments/{payment-product}`. `transactionStatus` is the ISO 20022
     * code (Berlin uses RCVD/ACTC/ACSC/RJCT… verbatim); `_links` carry self/status and the
     * `scaRedirect` the PSU is sent to (redirect SCA, ADR-0021).
     */
    fun paymentInitiated(product: PaymentProduct, resp: PaymentInitiationResponse): Map<String, Any?> {
        val seg = productSegment(product)
        val links = buildMap<String, Any> {
            put("self", href("$BASE/payments/$seg/${resp.paymentId}"))
            put("status", href("$BASE/payments/$seg/${resp.paymentId}/status"))
            val sca = resp.links.scaRedirect ?: "$BASE/payments/$seg/${resp.paymentId}/authorisations"
            put("scaRedirect", href(sca))
        }
        return mapOf(
            "transactionStatus" to resp.transactionStatus.name,
            "paymentId" to resp.paymentId,
            "_links" to links,
        )
    }

    /** `GET /v1/payments/{payment-product}/{paymentId}/status` — ISO 20022 transactionStatus. */
    fun paymentStatus(status: PaymentStatus): Map<String, Any?> = mapOf("transactionStatus" to status.name)

    private fun transaction(t: ObTransaction): Map<String, Any?> = mapOf(
        "transactionId" to t.transactionId,
        "entryReference" to t.entryReference,
        "bookingDate" to t.bookingDate?.toString(),
        "valueDate" to t.valueDate?.toString(),
        "transactionAmount" to amount(t.transactionAmount),
        "creditorName" to t.creditorName,
        "creditorAccount" to t.creditorAccount,
        "debtorName" to t.debtorName,
        "debtorAccount" to t.debtorAccount,
        "remittanceInformationUnstructured" to t.remittanceInformationUnstructured,
        "bankTransactionCode" to t.bankTransactionCode,
    )
}
