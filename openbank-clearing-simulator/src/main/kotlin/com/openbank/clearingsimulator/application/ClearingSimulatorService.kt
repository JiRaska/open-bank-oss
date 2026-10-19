// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.application

import com.openbank.clearingsimulator.application.dto.ReturnRequest
import com.openbank.clearingsimulator.domain.ClearingRoute
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
import com.openbank.libs.iso20022.Pacs009Builder
import com.openbank.libs.iso20022.Pacs009ParseException
import com.openbank.libs.iso20022.Pacs009Reader
import com.openbank.libs.iso20022.PaymentReturn
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.PaymentStatusReport
import com.openbank.libs.iso20022.ReceivedCreditTransfer
import com.openbank.libs.iso20022.SettlementMethod
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
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
    private val pacs009Validator = Iso20022Validator.forSchema(Pacs009Builder.SCHEMA_RESOURCE)
    private val reader = Pacs008Reader()
    private val pacs009Reader = Pacs009Reader()
    private val pacs002Builder = Pacs002Builder()
    private val pacs004Builder = Pacs004Builder()
    private val camt054Builder = Camt054Builder()
    private val engine = SchemeDecisionEngine()

    /**
     * Seconds between the `ACSP` answer and the asynchronous `ACSC` of a deferred bank-own
     * transfer (amount remainder 03). Non-production knob.
     */
    @field:ConfigProperty(name = "openbank.clearing-simulator.fi-settlement-lag-seconds", defaultValue = "30")
    var fiSettlementLagSeconds: Long = DEFAULT_FI_SETTLEMENT_LAG_SECONDS

    /** Bank-own transfers already taken, by UETR (deduplication + status query). */
    val institutionTransfers = InstitutionTransferRegistry()

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
        val received = parseWith(pacs008Xml, pacs008Validator) { reader.read(it) }
            ?: return ClearingResult(rejectInvalid(now), settled = false, creditNotificationXml = null)

        val decision = engine.decide(received)
        val statusReport = buildStatusReport(received.endToEndId, received.transactionId, decision, now)
        val notification = if (decision.settled) buildNotification(received, now) else null
        return ClearingResult(statusReport, decision.settled, notification)
    }

    /**
     * Processes one inbound `pacs.009` (bank-own FI credit transfer, CORE) on [route] and returns
     * its `pacs.002`. Same contract as [clear]: always a status report, never an exception —
     * `RJCT`/`FF01` for a message failing the XSD or unparseable, `RJCT`/`AM03` for a currency or
     * clearing system the route does not clear, otherwise the deterministic amount-keyed verdict.
     * `ACSC` means settled on the route; there is no camt.054 for an FI transfer (the parties are
     * the institutions themselves, whose nostro statements are a separate flow).
     */
    fun clearInstitutionTransfer(
        route: ClearingRoute,
        pacs009Xml: String,
        now: Instant = Instant.now(clock),
    ): ClearingResult {
        val received = parseWith(pacs009Xml, pacs009Validator) { pacs009Reader.read(it) }
            ?: return ClearingResult(rejectInvalid(now), settled = false, creditNotificationXml = null)
        fun report(status: SchemeDecision, at: Instant): ClearingResult {
            val r = PaymentStatusReport(
                messageId = id("SIM-STS-", received.endToEndId),
                creationDateTime = at.atUtc(),
                originalEndToEndId = received.endToEndId,
                originalTransactionId = received.transactionId,
                status = status.status,
                reasonCode = status.reason?.code,
                additionalInfo = status.reason?.description,
                // Names the message it answers, so a rail can verify a status reply is about the
                // transfer it asked for (MsgId + UETR) before acting on it.
                originalMessageId = received.messageId,
                originalMessageNameId = PACS_009_NAME,
                originalUetr = received.uetr,
            )
            return ClearingResult(
                validated(pacs002Builder.build(r), pacs002Validator, "pacs.002"),
                status.settled,
                null,
            )
        }
        val decide = {
            val routeOk = route.accepts(received.currency, received.clearingSystem)
            if (routeOk && engine.defersSettlement(received.amount)) {
                // Accepted now (ACSP), settled asynchronously after the configured lag (ACSC).
                val settleAt = now.plusSeconds(fiSettlementLagSeconds)
                InstitutionTransferRegistry.Recorded(
                    initial = report(SchemeDecision.ACCEPTED_PENDING, now),
                    later = report(SchemeDecision.SETTLED, settleAt),
                    settleAt = settleAt,
                )
            } else {
                val decision = if (routeOk) {
                    engine.decide(
                        received.amount,
                    )
                } else {
                    SchemeDecision.rejected(RejectReason.AM03)
                }
                InstitutionTransferRegistry.Recorded(report(decision, now))
            }
        }
        // Same UETR twice → the same pacs.002 and one settlement (the scheme's dedup key).
        return received.uetr?.let { institutionTransfers.recordOnce(it, now, decide) } ?: decide().initial
    }

    /**
     * XSD-validates then reads an inbound message; `null` when either fails. The readers throw
     * only their typed parse exceptions, which is the one failure this swallows into a reject.
     */
    private fun <T> parseWith(xml: String, validator: Iso20022Validator, read: (String) -> T): T? {
        if (validator.validate(xml) !is Iso20022ValidationResult.Valid) return null
        return try {
            read(xml)
        } catch (_: Pacs008ParseException) {
            null
        } catch (_: Pacs009ParseException) {
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

    private fun buildStatusReport(
        endToEndId: String,
        transactionId: String?,
        decision: SchemeDecision,
        now: Instant,
    ): String {
        val report = PaymentStatusReport(
            messageId = id("SIM-STS-", endToEndId),
            creationDateTime = now.atUtc(),
            originalEndToEndId = endToEndId,
            originalTransactionId = transactionId,
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
        const val PACS_009_NAME = "pacs.009.001.08"
        const val DEFAULT_FI_SETTLEMENT_LAG_SECONDS = 30L
        const val RETURN_ID_SUFFIX_LEN = 8
    }
}
