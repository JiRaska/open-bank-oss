// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.SettlementOutcome
import com.openbank.domestic.application.port.out.SettlementPort
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticTransferScope
import io.quarkus.oidc.client.OidcClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.UUID

/**
 * Adapter over [TransactionServiceClient] — books the debit leg in transaction-service once
 * the Czech CERTIS scheme returns ACSC (ADR-0108). The idempotency key is payment-scoped so
 * Temporal retries never double-book: transaction-service early-returns the existing transaction
 * for a repeated key and answers 201 with it, which is the arm that actually fires. The 409 branch
 * below is unreachable against that service today and kept only as defence if it ever adopts a
 * conflict response — do not cite it as the deduplication mechanism.
 *
 * The OIDC token is acquired explicitly (not via OidcClientRequestReactiveFilter) because the
 * filter loses the Vert.x context on Temporal activity threads — same root cause as ADR-0104
 * BUG #3 for the SEPA scheme gateway.
 */
@ApplicationScoped
class SettlementAdapter(
    @RestClient private val client: TransactionServiceClient,
    // Lazy Instance: absent when oidc-client is disabled under %test so a direct injection
    // would fail Arc validation for every @QuarkusTest. Resolved on demand in prod only.
    private val oidcClient: Instance<OidcClient>,
    private val accountLookup: AccountLookupPort,
    private val clock: Clock,
) : SettlementPort {

    @Inject
    lateinit var self: SettlementAdapter

    private val log = Logger.getLogger(SettlementAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun settle(payment: DomesticPayment): SettlementOutcome = try {
        self.settleWithResilience(payment)
    } catch (ex: SettlementUnavailableException) {
        throw ex
    } catch (ex: Exception) {
        throw SettlementUnavailableException(
            "transaction-service unavailable for payment ${payment.id}",
            ex,
        )
    }

    @Retry(maxRetries = 2, delay = 300, jitter = 150, retryOn = [Exception::class])
    @Timeout(SETTLE_TIMEOUT_MS)
    open suspend fun settleWithResilience(payment: DomesticPayment): SettlementOutcome {
        val token = oidcClient.get().tokens.awaitSuspending().accessToken
        val valueDate = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val description = buildDescription(payment)

        // transaction-service rejects an amount whose scale exceeds the currency's fraction digits
        // ("Amount scale 6 exceeds currency CZK fraction digits 2") — and payment.amount is persisted
        // with a wider scale (e.g. 123.000000), which made every settlement 400 and stick in
        // SENT_TO_CLEARING. Normalise to the currency's minor units before booking.
        val fractionDigits = runCatching { Currency.getInstance(payment.currency).defaultFractionDigits }
            .getOrDefault(2).coerceAtLeast(0)
        val settlementAmount = payment.amount.setScale(fractionDigits, RoundingMode.HALF_UP)

        // Credit leg: for an in-house transfer (payer + payee both at our bank) resolve the payee's
        // internal accountId and send it as targetAccountId, so transaction-service books a two-sided
        // journal (debit payer + CREDIT payee) and the payee actually receives the money. Without it
        // the debit only lands in the bank cash-clearing suspense and the payee is never credited.
        // External transfers keep a null target (the money genuinely leaves the bank).
        val targetAccountId = resolveInternalCreditorAccountId(payment)

        val response = client.initiateTransaction(
            "Bearer $token",
            InitiateSettlementRequest(
                idempotencyKey = "domestic-settlement-${payment.id}",
                type = "DEBIT",
                sourceAccountId = payment.debtorAccountId,
                targetAccountId = targetAccountId,
                amount = settlementAmount,
                currencyCode = payment.currency,
                description = description,
                valueDate = valueDate,
                rail = "DOMESTIC",
                instructionType = "ONE_OFF",
            ),
        ).awaitSuspending()

        return when (response.status) {
            HTTP_CREATED -> {
                val txId = extractTransactionId(response)
                log.infof("Settlement booked for payment %s → transactionId=%s", payment.id, txId)
                SettlementOutcome(settled = true, transactionId = txId)
            }
            // Unreachable against transaction-service today — it replays a duplicate key as 201
            // with the existing transaction (see this class's KDoc). Kept as defence, and logged
            // loudly enough to notice if that ever changes.
            HTTP_CONFLICT -> {
                log.infof(
                    "Settlement already booked (409) for payment %s — idempotent success. " +
                        "NOTE: transaction-service is not expected to answer 409; if this line " +
                        "appears, its duplicate handling changed and the docs need revisiting.",
                    payment.id,
                )
                SettlementOutcome(settled = true, transactionId = null)
            }
            else -> {
                throw SettlementUnavailableException(
                    "transaction-service returned HTTP ${response.status} for payment ${payment.id}",
                )
            }
        }
    }

    /**
     * Resolve the payee's internal accountId for an in-house transfer (OWN_ACCOUNTS / INTERNAL_CLIENT),
     * or null for external transfers / when it cannot be resolved (then the booking stays one-sided
     * and the debit lands in cash-clearing as before — best-effort, never blocks settlement).
     */
    private suspend fun resolveInternalCreditorAccountId(payment: DomesticPayment): UUID? {
        if (!isInHouse(payment)) return null
        val iban = toCzIban(payment.creditorAccountNumber, payment.creditorBankCode)
            ?: run {
                log.warnf(
                    "Cannot derive creditor IBAN for internal payment %s (acct=%s bank=%s)",
                    payment.id,
                    payment.creditorAccountNumber,
                    payment.creditorBankCode,
                )
                return null
            }
        return accountLookup.findAccountIdByIban(iban).also {
            if (it == null) {
                log.warnf(
                    "Internal payment %s: creditor account %s unresolved — booking debit-only",
                    payment.id,
                    iban,
                )
            }
        }
    }

    /**
     * Build a Czech IBAN (CZkk BBBB + 16-digit account part) from an account number + bank code,
     * computing the mod-97 check digits; returns the input unchanged if it is already an IBAN, or
     * null if the inputs cannot form a valid Czech BBAN.
     */
    private fun toCzIban(accountNumber: String, bankCode: String): String? {
        val raw = accountNumber.replace(" ", "").uppercase()
        if (raw.startsWith("CZ")) return raw
        val acct = raw.filter { it.isDigit() }
        val bank = bankCode.filter { it.isDigit() }
        if (acct.isEmpty() || acct.length > IBAN_ACCOUNT_DIGITS || bank.length > IBAN_BANK_DIGITS) return null
        val bban = bank.padStart(IBAN_BANK_DIGITS, '0') + acct.padStart(IBAN_ACCOUNT_DIGITS, '0')
        // ISO 13616 check digits: BBAN + "CZ00" with letters→numbers (C=12, Z=35), mod 97.
        var mod = 0
        for (ch in bban + "123500") mod = (mod * RADIX_10 + (ch - '0')) % MOD_97
        val check = (MOD_97_COMPLEMENT - mod).toString().padStart(2, '0')
        return "CZ$check$bban"
    }

    /** In-house transfers (OWN_ACCOUNTS / INTERNAL_CLIENT) settle on the bank's own ledger. */
    private fun isInHouse(payment: DomesticPayment): Boolean =
        payment.transferScope == DomesticTransferScope.INTERNAL_CLIENT ||
            payment.transferScope == DomesticTransferScope.OWN_ACCOUNTS

    private fun buildDescription(payment: DomesticPayment): String {
        val info = payment.messageForPayee
            ?: listOfNotNull(
                payment.variableSymbol?.let { "VS:$it" },
                payment.specificSymbol?.let { "SS:$it" },
                payment.constantSymbol?.let { "KS:$it" },
            ).joinToString(" ").ifBlank { payment.endToEndId }
            ?: payment.id.toString()
        // CERTIS is the ČNB interbank clearing scheme — an in-house transfer never traverses it,
        // so labelling it "CERTIS" is factually wrong. Only interbank transfers carry that prefix.
        val scheme = if (isInHouse(payment)) "Interní převod" else "CERTIS"
        return "$scheme $info".take(MAX_DESCRIPTION)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun extractTransactionId(response: jakarta.ws.rs.core.Response): UUID? = try {
        val body = response.readEntity(String::class.java) ?: return null
        // Minimal JSON extraction — the response body is {"id":"<uuid>",...}.
        val match = UUID_PATTERN.find(body)
        match?.groupValues?.get(1)?.let { UUID.fromString(it) }
    } catch (ex: Exception) {
        log.warnf(ex, "Could not parse transactionId from transaction-service response")
        null
    }

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
        const val MAX_DESCRIPTION = 140
        const val SETTLE_TIMEOUT_MS = 8_000L
        const val IBAN_BANK_DIGITS = 4
        const val IBAN_ACCOUNT_DIGITS = 16
        const val MOD_97 = 97
        const val MOD_97_COMPLEMENT = 98
        const val RADIX_10 = 10
        val UUID_PATTERN =
            Regex(""""id"\s*:\s*"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"""")
    }
}
