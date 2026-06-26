// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs002Reader
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.sepainstant.application.port.out.SchemeGatewayPort
import com.openbank.sepainstant.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepainstant.application.port.out.SchemeSubmissionOutcome
import com.openbank.sepainstant.domain.model.SctInstPayment
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Submits a real ISO 20022 `pacs.008` to the scheme gateway and maps the `pacs.002` response to a
 * [SchemeSubmissionOutcome] (ADR-0104 D4, SCT Inst). Builds the message with the shared
 * `openbank-libs` builder (identical wire format to every other rail), validates it against the
 * XSD before it leaves the rail, and reads the verdict with the shared reader. Reactive (`Uni`).
 *
 * Fails **closed**: any gateway/transport/parse failure becomes a [SchemeGatewayUnavailableException]
 * so the caller holds the payment rather than settling it.
 */
@ApplicationScoped
class SchemeGatewayAdapter(
    @RestClient private val client: ClearingSimulatorClient,
    @ConfigProperty(name = "openbank.bank.bic") private val ownBankBic: String,
) : SchemeGatewayPort {

    private val builder = Pacs008Builder()
    private val validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)
    private val statusReader = Pacs002Reader()

    @Suppress("MagicNumber") // resilience tuning constants (mirror SanctionsScreeningAdapter)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 150)
    @Timeout(5_000)
    override fun submit(payment: SctInstPayment): Uni<SchemeSubmissionOutcome> {
        // No creditor agent BIC → the scheme would reject (RC01); surface it without a round-trip.
        val creditorAgentBic = payment.creditorBic
            ?: return Uni.createFrom().item(SchemeSubmissionOutcome(accepted = false, reasonCode = "RC01"))

        val pacs008 = builder.build(instruction(payment, creditorAgentBic))
        check(validator.validate(pacs008) is Iso20022ValidationResult.Valid) {
            "rail built a non-conforming pacs.008 for payment ${payment.paymentId}"
        }

        return client.submitCreditTransfer(pacs008)
            .map { pacs002 ->
                val status = statusReader.read(pacs002)
                SchemeSubmissionOutcome(status.status == PaymentStatus.ACSC, status.reasonCode)
            }
            .onFailure().transform { SchemeGatewayUnavailableException(it) }
    }

    private fun instruction(payment: SctInstPayment, creditorAgentBic: String) = CreditTransferInstruction(
        messageId = "MSG-${payment.endToEndId}".take(MAX_35),
        creationDateTime = OffsetDateTime.now(ZoneOffset.UTC),
        endToEndId = payment.endToEndId,
        transactionId = null,
        amount = payment.amount,
        currency = payment.currency,
        chargeBearer = ChargeBearer.SLEV,
        settlementMethod = SettlementMethod.CLRG,
        debtorName = payment.debtorName,
        debtorIban = payment.debtorIban,
        debtorAgentBic = ownBankBic,
        creditorAgentBic = creditorAgentBic,
        creditorName = payment.creditorName,
        creditorIban = payment.creditorIban,
        remittanceInfo = payment.remittanceInfo,
    )

    private companion object {
        const val MAX_35 = 35
    }
}
