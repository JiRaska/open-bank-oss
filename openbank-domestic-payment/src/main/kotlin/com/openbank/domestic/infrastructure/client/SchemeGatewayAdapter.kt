// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.SchemeGatewayPort
import com.openbank.domestic.application.port.out.SchemeGatewayUnavailableException
import com.openbank.domestic.application.port.out.SchemeSubmissionOutcome
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs002Reader
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.SettlementMethod
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.BigInteger
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Submits a real ISO 20022 `pacs.008` to the scheme gateway and maps the `pacs.002` response to a
 * [SchemeSubmissionOutcome] (ADR-0104 D4 — domestic-payment fan-out). Czech domestic payments use
 * BBAN (account number + bank code) — the adapter converts them to Czech IBAN per ISO 13616 before
 * building the pacs.008. The local instrument `DOMICILE` marks the transfer as a domestic CZK
 * clearing item for CERTIS; the simulator accepts and echoes the same flag.
 *
 * Fails **closed** (ADR-0032): gateway unreachable → [SchemeGatewayUnavailableException] so the
 * caller holds the payment in VALIDATED rather than releasing it without confirmation.
 */
@ApplicationScoped
class SchemeGatewayAdapter(
    @RestClient private val client: ClearingSimulatorClient,
    @ConfigProperty(name = "openbank.bank.bic") private val ownBankBic: String,
    @ConfigProperty(name = "openbank.bank.bank-code") private val ownBankCode: String,
) : SchemeGatewayPort {

    @Inject
    lateinit var self: SchemeGatewayAdapter

    private val builder = Pacs008Builder()
    private val validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)
    private val statusReader = Pacs002Reader()
    private val log = Logger.getLogger(SchemeGatewayAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun submit(payment: DomesticPayment): SchemeSubmissionOutcome {
        val debtorIban = bbanToIban(payment.debtorAccountNumber, payment.debtorBankCode)
        val creditorIban = bbanToIban(payment.creditorAccountNumber, payment.creditorBankCode)
        val creditorAgentBic = bankCodeToBic(payment.creditorBankCode)

        val pacs008 = builder.build(instruction(payment, debtorIban, creditorIban, creditorAgentBic))
        check(validator.validate(pacs008) is Iso20022ValidationResult.Valid) {
            "rail built a non-conforming pacs.008 for payment ${payment.id}"
        }

        val pacs002 = try {
            self.submitWithResilience(pacs008)
        } catch (ex: Exception) {
            // #4218: say whether the request can possibly have reached the scheme. Only a refused
            // connection or an unresolvable host proves it did not; a timeout does NOT, since the
            // gateway may have accepted the pacs.008 and merely answered too late.
            val left = requestLeftThisProcess(ex)
            log.warnf(
                ex,
                "Scheme gateway unavailable for payment %s; holding (requestLeftThisProcess=%s)",
                payment.id,
                left,
            )
            throw SchemeGatewayUnavailableException(ex, requestLeftThisProcess = left)
        }

        val status = statusReader.read(pacs002)
        return SchemeSubmissionOutcome(
            accepted = status.status == PaymentStatus.ACSC,
            reasonCode = status.reasonCode,
        )
    }

    @Suppress("MagicNumber")
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 150)
    @Timeout(5_000)
    open suspend fun submitWithResilience(pacs008Xml: String): String =
        client.submitCreditTransfer(pacs008Xml).awaitSuspending()

    /**
     * Did the pacs.008 leave this process (#4218)? Answered from the cause chain, and biased hard
     * towards `true`: the caller uses `false` to justify submitting the payment AGAIN, so only a
     * failure that provably happened before any byte was written may say so.
     *
     * `ConnectException` — the peer refused the TCP connection. `UnknownHostException` — DNS never
     * resolved. Both are pre-transmission by construction. Everything else, including
     * `TimeoutException` and a circuit-breaker rejection wrapping a timed-out attempt, is
     * ambiguous: `@Retry` above may also have delivered an earlier attempt the gateway processed.
     */
    private fun requestLeftThisProcess(ex: Throwable): Boolean {
        var cause: Throwable? = ex
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is ConnectException || cause is UnknownHostException) return false
            cause = cause.cause
        }
        return true
    }

    private fun instruction(
        payment: DomesticPayment,
        debtorIban: String,
        creditorIban: String,
        creditorAgentBic: String,
    ): CreditTransferInstruction {
        // Domestic transfers settle same-day via the clearing system (SttlmMtd CLRG) — there
        // is no scheme-supplied value date to carry, unlike SWIFT MT103's field 32A.
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return CreditTransferInstruction(
            messageId = "DOM-${payment.endToEndId}".take(MAX_35),
            creationDateTime = now,
            interbankSettlementDate = now,
            endToEndId = payment.endToEndId,
            transactionId = null,
            amount = payment.amount,
            currency = payment.currency,
            chargeBearer = ChargeBearer.SLEV,
            settlementMethod = SettlementMethod.CLRG,
            debtorName = payment.debtorName,
            debtorIban = debtorIban,
            debtorAgentBic = "$ownBankBic",
            creditorAgentBic = creditorAgentBic,
            creditorName = payment.creditorName,
            creditorIban = creditorIban,
            remittanceInfo = buildRemittanceInfo(payment),
        )
    }

    private fun buildRemittanceInfo(payment: DomesticPayment): String? {
        val parts = listOfNotNull(
            payment.variableSymbol?.let { "VS:$it" },
            payment.specificSymbol?.let { "SS:$it" },
            payment.constantSymbol?.let { "KS:$it" },
            payment.messageForPayee,
        )
        return parts.joinToString(" ").ifBlank { null }?.take(MAX_140)
    }

    /**
     * Derives a Czech IBAN (CZ + 2 check digits + 4-digit bank code + 6-digit prefix + 10-digit
     * account number) from a BBAN account number and bank code per ISO 13616 / CNB standard.
     * The BBAN has no prefix (treated as 000000). Input is right-padded to the standard widths.
     */
    @Suppress("MagicNumber")
    private fun bbanToIban(accountNumber: String, bankCode: String): String {
        val bban = bankCode.padStart(4, '0') +
            "000000" + // 6-digit prefix (no prefix ⟹ zeros)
            accountNumber.padStart(10, '0')
        val numeric = bban.map { it.digitToIntOrNull() ?: (it.code - 'A'.code + 10) }.joinToString("") +
            "1235" + "00" // CZ = 12 35; check digits placeholder 00
        val checkDigits = (98 - (BigInteger(numeric).mod(BigInteger.valueOf(97))).toInt())
            .toString().padStart(2, '0')
        return "CZ$checkDigits$bban"
    }

    /** Maps a Czech 4-digit bank code to a BIC (only own bank code is known; others use a synthetic form). */
    private fun bankCodeToBic(bankCode: String): String = if (bankCode == ownBankCode) ownBankBic else "${bankCode}CZPP"

    private companion object {
        const val MAX_35 = 35
        const val MAX_140 = 140
    }
}
