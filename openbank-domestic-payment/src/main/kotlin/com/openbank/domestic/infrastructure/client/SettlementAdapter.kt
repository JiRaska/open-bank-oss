// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.clients.transaction.TransactionCallOutcome
import com.openbank.clients.transaction.TransactionClient
import com.openbank.clients.transaction.TransactionCompletionFailure
import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.TransactionHttpException
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.SettlementOutcome
import com.openbank.domestic.application.port.out.SettlementPort
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticTransferScope
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Books the debit leg in transaction-service once the Czech CERTIS scheme returns ACSC (ADR-0108), through
 * the shared [TransactionClient] facade (ADR-0319), the only place that interprets the HTTP answer.
 *
 * Settlement is confirmed ONLY by HTTP 201 carrying a COMPLETED transaction (the facade throws on anything
 * else) whose type, accounts, amount, currency, rail and instruction type match the request (#13217).
 * Every other answer throws [SettlementUnavailableException]; the activity rethrows it, Temporal retries,
 * and the payment stays SENT_TO_CLEARING:
 *  - 409 is a concurrent-state conflict on the provider, never proof of an earlier booking (#13225);
 *    transaction-service replays a repeated key as 201 with the existing transaction, so the payment-scoped
 *    `domestic-settlement-<paymentId>` key is what keeps every retry from booking twice;
 *  - an unreadable or non-final 2xx is completion-UNCERTAIN and retried under that same key.
 *
 * The bearer comes from the named `m2m` oidc-client filter on [TransactionServiceClient] (#13224).
 */
@ApplicationScoped
class SettlementAdapter(
    @RestClient private val client: TransactionServiceClient,
    private val accountLookup: AccountLookupPort,
    private val clock: Clock,
    registry: MeterRegistry,
) : SettlementPort {

    @Inject
    lateinit var self: SettlementAdapter

    private val log = Logger.getLogger(SettlementAdapter::class.java)
    private val transactions = TransactionClient(client)
    private val unsettledCounters = UNSETTLED_OUTCOMES.associateWith { outcome ->
        registry.counter(UNSETTLED_METRIC, "rail", UNSETTLED_RAIL, "outcome", outcome)
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun settle(payment: DomesticPayment): SettlementOutcome = try {
        self.settleWithResilience(payment)
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: SettlementUnavailableException) {
        throw ex
    } catch (ex: Exception) {
        throw SettlementUnavailableException(
            "transaction-service unavailable for payment ${payment.id}",
            ex,
        )
    }

    @Retry(
        maxRetries = 2,
        delay = 300,
        jitter = 150,
        retryOn = [Exception::class],
        abortOn = [SettlementUnavailableException::class, CancellationException::class],
    )
    @Timeout(SETTLE_TIMEOUT_MS)
    open suspend fun settleWithResilience(payment: DomesticPayment): SettlementOutcome {
        // transaction-service rejects an amount whose scale exceeds the currency's fraction digits.
        // payment.amount is a kernel Money (#11604), built at the inbound boundary without rounding,
        // so it is already at exactly the currency's minor-unit scale (123.000000 in the column reads
        // back as 123.00). The previous setScale(LEDGER_POSTING) here was a no-op for every amount
        // Money can hold and silently rounded the sub-minor-unit amounts the boundary now refuses.
        val settlementAmount = payment.amount.amount

        // Credit leg: for an in-house transfer (payer + payee both at our bank) resolve the payee's
        // internal accountId and send it as targetAccountId, so transaction-service books a two-sided
        // journal (debit payer + CREDIT payee) and the payee actually receives the money. Without it
        // the debit only lands in the bank cash-clearing suspense and the payee is never credited.
        // External transfers keep a null target (the money genuinely leaves the bank).
        val targetAccountId = resolveInternalCreditorAccountId(payment)

        val request = InitiateTransactionRequest(
            idempotencyKey = "domestic-settlement-${payment.id}",
            type = "DEBIT",
            sourceAccountId = payment.debtorAccountId,
            targetAccountId = targetAccountId,
            amount = settlementAmount,
            currencyCode = payment.currency,
            description = buildDescription(payment),
            valueDate = LocalDate.now(clock),
            rail = "DOMESTIC",
            instructionType = "ONE_OFF",
        )
        val txId = confirm(payment, request, initiate(request))
        log.infof("Settlement booked for payment %s → transactionId=%s", payment.id, txId)
        return SettlementOutcome(settled = true, transactionId = txId)
    }

    /** Count one unsuccessful provider call, inside each resilience attempt, never again in [settle]. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun initiate(request: InitiateTransactionRequest): TransactionCallOutcome<TransactionResponse> =
        try {
            transactions.initiate(request).awaitSuspending()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val outcome = when (ex) {
                // FAILED includes a replayed FAILED response, not proof this request was a replay.
                is TransactionCompletionUncertainException ->
                    if (ex.completionFailure == TransactionCompletionFailure.FAILED) {
                        FAILED_REPLAY
                    } else {
                        UNEXPECTED_STATUS
                    }
                is TransactionHttpException -> UNEXPECTED_STATUS
                else -> TRANSPORT_ERROR
            }
            recordUnsettled(outcome)
            throw ex
        }

    private fun recordUnsettled(outcome: String) {
        unsettledCounters.getValue(outcome).increment()
    }

    private fun refuse(outcome: String, message: String, cause: Throwable? = null): Nothing {
        recordUnsettled(outcome)
        throw SettlementUnavailableException(message, cause)
    }

    /**
     * The response-vs-request binding sepa-instant's `SettlementAdapter` applies (#13217). Messages name only
     * the reason, never body content (it may carry customer data).
     */
    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    private fun confirm(
        payment: DomesticPayment,
        request: InitiateTransactionRequest,
        outcome: TransactionCallOutcome<TransactionResponse>,
    ): UUID {
        val booked = when (outcome) {
            is TransactionCallOutcome.HttpSuccess -> outcome
            TransactionCallOutcome.Conflict -> refuse(
                CONFLICT,
                "transaction-service returned HTTP $HTTP_CONFLICT (a concurrent-state conflict, not a booking) " +
                    "for payment ${payment.id}",
            )
            is TransactionCallOutcome.Rejected -> refuse(
                UNEXPECTED_STATUS,
                "transaction-service returned HTTP ${outcome.status} for payment ${payment.id}",
            )
        }
        if (booked.status != HTTP_CREATED) {
            refuse(
                UNEXPECTED_STATUS,
                "transaction-service returned HTTP ${booked.status} for payment ${payment.id}",
            )
        }
        val tx = booked.body
            ?: refuse(UNEXPECTED_STATUS, "Settlement response has no transaction", booked.bodyError)
        val id = tx.id ?: refuse(MISMATCH, "Settlement response has no transaction id")
        val amount = tx.amount ?: refuse(MISMATCH, "Settlement response has no numeric amount")
        // valueDate is deliberately not compared: the provider resolves it to a business day. The target account
        // is compared only when one was requested; a debit-only booking's counter-leg is the provider's choice.
        val matchesRequest = tx.status == CONFIRMED_STATUS &&
            tx.type == request.type &&
            tx.sourceAccountId == request.sourceAccountId &&
            (request.targetAccountId == null || tx.targetAccountId == request.targetAccountId) &&
            amount.compareTo(request.amount) == 0 &&
            tx.currencyCode == request.currencyCode &&
            tx.rail == request.rail &&
            tx.instructionType == request.instructionType
        if (!matchesRequest) {
            refuse(MISMATCH, "Settlement response does not match payment ${payment.id}")
        }
        return id
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

    private companion object {
        const val UNSETTLED_METRIC = "openbank.payment.unsettled"
        const val UNSETTLED_RAIL = "DOMESTIC_CZK"
        const val MISMATCH = "mismatch"
        const val FAILED_REPLAY = "failed-replay"
        const val CONFLICT = "conflict"
        const val UNEXPECTED_STATUS = "unexpected-status"
        const val TRANSPORT_ERROR = "transport-error"
        val UNSETTLED_OUTCOMES = setOf(MISMATCH, FAILED_REPLAY, CONFLICT, UNEXPECTED_STATUS, TRANSPORT_ERROR)
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
        const val CONFIRMED_STATUS = "COMPLETED"
        const val MAX_DESCRIPTION = 140
        const val SETTLE_TIMEOUT_MS = 8_000L
        const val IBAN_BANK_DIGITS = 4
        const val IBAN_ACCOUNT_DIGITS = 16
        const val MOD_97 = 97
        const val MOD_97_COMPLEMENT = 98
        const val RADIX_10 = 10
    }
}
