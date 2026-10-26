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
import com.openbank.libs.iso20022.Pacs009Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.swift.application.port.out.InstitutionTransferSubmission
import com.openbank.swift.application.port.out.InstitutionTransferVerdict
import com.openbank.swift.application.port.out.NonConformingMessageException
import com.openbank.swift.application.port.out.SchemeGatewayPort
import com.openbank.swift.application.port.out.SchemeGatewayUnavailableException
import com.openbank.swift.application.port.out.SchemeStatus
import com.openbank.swift.application.port.out.SchemeSubmissionOutcome
import com.openbank.swift.domain.institution.InstitutionTransfer
import com.openbank.swift.domain.model.SwiftMessage
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
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
    private val pacs009Builder = Pacs009Builder()
    private val pacs009Validator = Iso20022Validator.forSchema(Pacs009Builder.SCHEMA_RESOURCE)
    private val statusReader = Pacs002Reader()
    private val log = Logger.getLogger(SchemeGatewayAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun submit(message: SwiftMessage): SchemeSubmissionOutcome {
        val pacs008 = builder.build(instruction(message))

        // The validity check must be inside this try/catch, not before it: a SwiftMessage with a
        // legitimately-nullable debtor field (orderingCustomerAccount/Name — see instruction())
        // builds a pacs.008 the XSD rejects (empty IBAN/name), and the class's own fail-closed
        // contract promises that surfaces as SchemeGatewayUnavailableException, not a raw crash.
        val pacs002 = try {
            check(validator.validate(pacs008) is Iso20022ValidationResult.Valid) {
                "rail built a non-conforming pacs.008 for SWIFT message ${message.id}"
            }
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

    /**
     * Bank-own transfers: build the `pacs.009` for the rail from the transfer's PERSISTED identity
     * (MsgId, UETR, CreDtTm — [Pacs009InstructionMapper]), XSD-validate it (a failure is
     * [NonConformingMessageException], never sent), submit it ONCE on the rail's route and map the
     * `pacs.002`. There is deliberately no automatic retry: a submission is not idempotent from
     * this side, so a timeout after the scheme took it must not become a second payment. Any
     * failure to obtain a readable answer is [SchemeGatewayUnavailableException]; the caller marks
     * the transfer SUBMISSION_UNKNOWN and resolves it by [queryInstitutionTransferStatus].
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun submitInstitutionTransfer(transfer: InstitutionTransfer): InstitutionTransferSubmission {
        val pacs009 = pacs009Builder.build(Pacs009InstructionMapper.instruction(transfer))
        val validation = pacs009Validator.validate(pacs009)
        if (validation is Iso20022ValidationResult.Invalid) {
            throw NonConformingMessageException(
                "non-conforming pacs.009 for payment ${transfer.paymentId}: ${validation.errors}",
            )
        }
        val status = try {
            statusReader.read(
                self.submitInstitutionOnce(Pacs009InstructionMapper.routeOf(transfer.rail), pacs009),
            )
        } catch (ex: Exception) {
            log.warnf(ex, "No readable answer for institution transfer %s; outcome unknown", transfer.paymentId)
            throw SchemeGatewayUnavailableException(ex)
        }
        return InstitutionTransferSubmission(verdictOf(status.status), status.reasonCode, pacs009)
    }

    /**
     * Read-only status query by UETR — the only way an unanswered submission is resolved, and
     * deliberately suspicious of its answer (see [SchemeStatus]): a 404 counts as "never received"
     * ONLY with the scheme's typed body naming this UETR, and a pacs.002 counts only when its
     * `OrgnlUETR` and `OrgnlMsgId` are this transfer's. Safe to retry: it moves nothing.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun queryInstitutionTransferStatus(transfer: InstitutionTransfer): SchemeStatus {
        val body = try {
            self.queryInstitutionWithResilience(transfer.uetr)
        } catch (ex: WebApplicationException) {
            if (ex.response?.status != HTTP_NOT_FOUND) throw SchemeGatewayUnavailableException(ex)
            return if (isTypedUnknown(ex, transfer.uetr)) {
                SchemeStatus.NotKnown
            } else {
                SchemeStatus.Ambiguous(SchemeStatus.UNTYPED_NOT_FOUND)
            }
        } catch (ex: Exception) {
            throw SchemeGatewayUnavailableException(ex)
        }
        val status = try {
            statusReader.read(body)
        } catch (ex: Exception) {
            throw SchemeGatewayUnavailableException(ex)
        }
        if (status.originalUetr != transfer.uetr || status.originalMessageId != transfer.messageId) {
            log.errorf("Status for UETR %s names another message; not acting on it", transfer.uetr)
            return SchemeStatus.Ambiguous(SchemeStatus.MISMATCHED)
        }
        return SchemeStatus.Known(
            InstitutionTransferSubmission(verdictOf(status.status), status.reasonCode, transfer.rawPacs009.orEmpty()),
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isTypedUnknown(ex: WebApplicationException, uetr: String): Boolean = try {
        val node = mapper.readTree(ex.response.readEntity(String::class.java))
        node.path("code").asText() == UNKNOWN_UETR && node.path("uetr").asText() == uetr
    } catch (e: Exception) {
        false
    }

    private fun verdictOf(status: PaymentStatus) = when (status) {
        PaymentStatus.ACSC -> InstitutionTransferVerdict.SETTLED
        PaymentStatus.RJCT -> InstitutionTransferVerdict.REJECTED
        PaymentStatus.ACSP, PaymentStatus.RCVD -> InstitutionTransferVerdict.IN_PROCESS
    }

    // No @Retry here on purpose — see submitInstitutionTransfer.
    @Suppress("MagicNumber")
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Timeout(5_000)
    open suspend fun submitInstitutionOnce(route: String, pacs009Xml: String): String =
        client.submitInstitutionCreditTransfer(route, pacs009Xml).awaitSuspending()

    @Suppress("MagicNumber")
    @Retry(maxRetries = 2, delay = 300, jitter = 150, abortOn = [WebApplicationException::class])
    @Timeout(5_000)
    open suspend fun queryInstitutionWithResilience(uetr: String): String =
        client.institutionTransferStatus(uetr).awaitSuspending()

    @Suppress("MagicNumber")
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 2, delay = 300, jitter = 150)
    @Timeout(5_000)
    open suspend fun submitWithResilience(pacs008Xml: String): String =
        client.submitCreditTransfer(pacs008Xml).awaitSuspending()

    /**
     * Maps SWIFT MT103 fields to a [CreditTransferInstruction]. The MT103 value date is parsed
     * from the `YYYYMMDD` format used by SWIFT; the amount is the message's kernel `Money`.
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
            interbankSettlementDate = valueDate,
            endToEndId = msg.transactionReference.take(MAX_35),
            transactionId = null,
            // At the currency's own minor unit (#11604): JPY 0dp, KWD 3dp — not a fixed two.
            amount = msg.amount.amount,
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
        const val HTTP_NOT_FOUND = 404
        const val UNKNOWN_UETR = "UNKNOWN_UETR"
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
