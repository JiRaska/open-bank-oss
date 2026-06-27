// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.client

import com.openbank.libs.iso20022.ChargeBearer
import com.openbank.libs.iso20022.CreditTransferInstruction
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs002Reader
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SchemeSubmissionOutcome
import com.openbank.swift.domain.model.SwiftMessage
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Submits a real ISO 20022 `pacs.008` to the scheme gateway and maps the `pacs.002` response to a
 * [SchemeSubmissionOutcome] (ADR-0104 D4 — swift-service fan-out). Converts the SWIFT MT103 fields
 * to a [CreditTransferInstruction] and also populates `rawMt` with the built pacs.008 XML so the
 * wire message is persisted for audit (the `rawMt` field on [SwiftMessage] was previously null).
 *
 * Fails **closed** (ADR-0032): gateway unreachable → [SchemeGatewayUnavailableException] so the
 * caller holds the message in VALIDATED rather than releasing it without confirmation.
 */
@ApplicationScoped
class SchemeGatewayAdapter(@RestClient private val client: ClearingSimulatorClient) : SchemeGatewayPort {

    @Inject
    lateinit var self: SchemeGatewayAdapter

    private val builder = Pacs008Builder()
    private val validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)
    private val statusReader = Pacs002Reader()
    private val log = Logger.getLogger(SchemeGatewayAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun submit(message: SwiftMessage): SchemeSubmissionOutcome {
        val pacs008 = builder.build(instruction(message))
        check(validator.validate(pacs008) is Iso20022ValidationResult.Valid) {
            "rail built a non-conforming pacs.008 for SWIFT message ${message.id}"
        }

        val pacs002 = try {
            self.submitWithResilience(pacs008)
        } catch (ex: Exception) {
            log.warnf(ex, "Scheme gateway unavailable for SWIFT message %s; holding", message.id)
            throw SchemeGatewayUnavailableException(ex)
        }

        val status = statusReader.read(pacs002)
        return SchemeSubmissionOutcome(
            accepted = status.status == PaymentStatus.ACSC,
            reasonCode = status.reasonCode,
            rawMt = pacs008,
        )
    }

    @Suppress("MagicNumber")
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 150)
    @Timeout(5_000)
    open suspend fun submitWithResilience(pacs008Xml: String): String =
        client.submitCreditTransfer(pacs008Xml).awaitSuspending()

    /**
     * Maps SWIFT MT103 fields to a [CreditTransferInstruction]. The MT103 value date is parsed
     * from the `YYYYMMDD` format used by SWIFT; the amount is converted from minor units.
     * SWIFT charge codes map to pacs.008 `ChrgBr`: OUR→DEBT, SHA→SHAR, BEN→CRED.
     */
    @Suppress("MagicNumber")
    private fun instruction(msg: SwiftMessage): CreditTransferInstruction {
        val valueDate = runCatching {
            OffsetDateTime.parse(msg.valueDate + "T00:00:00Z", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }.getOrElse { OffsetDateTime.now(ZoneOffset.UTC) }

        val chargeBearer = when (msg.chargeCode) {
            "OUR" -> ChargeBearer.DEBT
            "BEN" -> ChargeBearer.CRED
            else -> ChargeBearer.SHAR
        }

        return CreditTransferInstruction(
            messageId = "SWIFT-${msg.transactionReference}".take(MAX_35),
            creationDateTime = OffsetDateTime.now(ZoneOffset.UTC),
            endToEndId = msg.transactionReference.take(MAX_35),
            transactionId = null,
            amount = BigDecimal.valueOf(msg.amountMinorUnits).movePointLeft(2),
            currency = msg.currency,
            chargeBearer = chargeBearer,
            settlementMethod = SettlementMethod.COVE,
            debtorName = msg.orderingCustomerName ?: "",
            debtorIban = msg.orderingCustomerAccount ?: "",
            debtorAgentBic = msg.senderBic,
            creditorAgentBic = msg.receiverBic,
            creditorName = msg.beneficiaryName,
            creditorIban = msg.beneficiaryAccount,
            remittanceInfo = msg.remittanceInfo,
        )
    }

    private companion object {
        const val MAX_35 = 35
    }
}
