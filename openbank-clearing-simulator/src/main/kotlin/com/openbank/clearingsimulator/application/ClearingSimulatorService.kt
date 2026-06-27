// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearingsimulator.application

import com.openbank.clearingsimulator.application.dto.ReturnRequest
import com.openbank.clearingsimulator.domain.RejectReason
import com.openbank.clearingsimulator.domain.SchemeDecision
import com.openbank.clearingsimulator.domain.SchemeDecisionEngine
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.iso20022.Camt054Builder
import com.openbank.libs.iso20022.CreditDebitIndicator
import com.openbank.libs.iso20022.DebitCreditNotification
import com.openbank.libs.iso20022.Iso20022ValidationResult
import com.openbank.libs.iso20022.Iso20022Validator
import com.openbank.libs.iso20022.Pacs002Builder
import com.openbank.libs.iso20022.Pacs004Builder
import com.openbank.libs.iso20022.Pacs008Builder
import com.openbank.libs.iso20022.Pacs008ParseException
import com.openbank.libs.iso20022.Pacs008Reader
import com.openbank.libs.iso20022.PaymentReturn
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.PaymentStatusReport
import com.openbank.libs.iso20022.ReceivedCreditTransfer
import com.openbank.libs.iso20022.SettlementMethod
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The pacs.002 status report plus, when the transfer settles, the camt.054 beneficiary notice. */
data class ClearingResult(val statusReportXml: String, val settled: Boolean, val creditNotificationXml: String?)

/**
 * The clearing-simulator's core: receive a `pacs.008`, validate it against the real XSD, decide
 * (ACSC/RJCT) deterministically, and produce the `pacs.002` status report and — on settlement — a
 * `camt.054` credit notification (ADR-0104 D2).
 *
 * Stateless and side-effect-free (no persistence, no network, no posting authority). Builds NO
 * messages of its own — it reuses `openbank-libs`' ISO 20022 builders so the wire format is
 * identical to the rail's. Every outbound message is validated against its schema before it is
 * returned: a non-conforming output is a simulator bug ([IllegalStateException]), never a reject.
 */
@ApplicationScoped
class ClearingSimulatorService {

    @Inject
    lateinit var clock: Clock
    private val pacs008Validator = Iso20022Validator.forSchema(Pacs008Builder.SCHEMA_RESOURCE)
    private val pacs002Validator = Iso20022Validator.forSchema(Pacs002Builder.SCHEMA_RESOURCE)
    private val camt054Validator = Iso20022Validator.forSchema(Camt054Builder.SCHEMA_RESOURCE)
    private val reader = Pacs008Reader()
    private val pacs002Builder = Pacs002Builder()
    private val pacs004Builder = Pacs004Builder()
    private val camt054Builder = Camt054Builder()
    private val engine = SchemeDecisionEngine()

    /**
     * Generates a `pacs.004` R-transaction XML for the given [ReturnRequest]. Called by the
     * `/api/v1/clearing/returns` endpoint which forwards the XML to the sepa-payment return handler.
     * The produced XML is validated against the pacs.004 XSD before it is returned.
     */
    fun generateReturn(request: ReturnRequest, now: Instant = Instant.now(clock)): String {
        val pacs004Validator = Iso20022Validator.forSchema(Pacs004Builder.SCHEMA_RESOURCE)
        val paymentReturn = PaymentReturn(
            messageId = "RET-${Ids.randomId()}".take(MAX_35),
            creationDateTime = now.atUtc(),
            settlementMethod = SettlementMethod.CLRG,
            returnId = "RTRN-${Ids.randomId().toString().take(RETURN_ID_SUFFIX_LEN)}",
            originalEndToEndId = request.originalEndToEndId,
            originalTransactionId = request.originalTransactionId,
            returnedAmount = request.amount,
            currency = request.currency,
            returnReasonCode = request.returnReasonCode,
            additionalInfo = request.additionalInfo,
        )
        return validated(pacs004Builder.build(paymentReturn), pacs004Validator, "pacs.004")
    }

    /**
     * Processes one inbound `pacs.008` and returns the `pacs.002` (and the `camt.054` if it settled).
     * A message that fails XSD validation or cannot be parsed is rejected with `RJCT`/`FF01` — the
     * simulator always answers with a status report, exactly as a CSM does.
     */
    fun clear(pacs008Xml: String, now: Instant = Instant.now(clock)): ClearingResult {
        val received = parse(pacs008Xml)
            ?: return ClearingResult(rejectInvalid(now), settled = false, creditNotificationXml = null)

        val decision = engine.decide(received)
        val statusReport = buildStatusReport(received, decision, now)
        val notification = if (decision.settled) buildNotification(received, now) else null
        return ClearingResult(statusReport, decision.settled, notification)
    }

    private fun parse(pacs008Xml: String): ReceivedCreditTransfer? {
        if (pacs008Validator.validate(pacs008Xml) !is Iso20022ValidationResult.Valid) return null
        return try {
            reader.read(pacs008Xml)
        } catch (_: Pacs008ParseException) {
            null
        }
    }

    private fun rejectInvalid(now: Instant): String {
        val report = PaymentStatusReport(
            messageId = "SIM-STS-INVALID",
            creationDateTime = now.atUtc(),
            originalEndToEndId = null,
            originalTransactionId = null,
            status = PaymentStatus.RJCT,
            reasonCode = RejectReason.FF01.code,
            additionalInfo = RejectReason.FF01.description,
        )
        return validated(pacs002Builder.build(report), pacs002Validator, "pacs.002")
    }

    private fun buildStatusReport(received: ReceivedCreditTransfer, decision: SchemeDecision, now: Instant): String {
        val report = PaymentStatusReport(
            messageId = id("SIM-STS-", received.endToEndId),
            creationDateTime = now.atUtc(),
            originalEndToEndId = received.endToEndId,
            originalTransactionId = received.transactionId,
            status = decision.status,
            reasonCode = decision.reason?.code,
            additionalInfo = decision.reason?.description,
        )
        return validated(pacs002Builder.build(report), pacs002Validator, "pacs.002")
    }

    private fun buildNotification(received: ReceivedCreditTransfer, now: Instant): String {
        val notification = DebitCreditNotification(
            messageId = id("SIM-NTF-", received.endToEndId),
            creationDateTime = now.atUtc(),
            notificationId = id("SIM-NID-", received.endToEndId),
            accountIban = received.creditorIban,
            entryReference = id("SIM-ENT-", received.endToEndId),
            amount = received.amount,
            currency = received.currency,
            direction = CreditDebitIndicator.CRDT,
            bookingDate = LocalDate.ofInstant(now, ZoneOffset.UTC),
            endToEndId = received.endToEndId,
        )
        return validated(camt054Builder.build(notification), camt054Validator, "camt.054")
    }

    private fun validated(xml: String, validator: Iso20022Validator, label: String): String {
        val result = validator.validate(xml)
        check(result is Iso20022ValidationResult.Valid) {
            "simulator produced a non-conforming $label: ${(result as Iso20022ValidationResult.Invalid).errors}"
        }
        return xml
    }

    /** Deterministic id derived from the original reference, kept within ISO 20022 Max35Text. */
    private fun id(prefix: String, reference: String): String = "$prefix$reference".take(MAX_35)

    private fun Instant.atUtc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val MAX_35 = 35
        const val RETURN_ID_SUFFIX_LEN = 8
    }
}
