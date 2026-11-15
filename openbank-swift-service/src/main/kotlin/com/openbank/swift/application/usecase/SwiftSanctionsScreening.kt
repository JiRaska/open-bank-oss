// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.fincrime.AmlCaseSeverity
import com.openbank.libs.fincrime.ScreeningDecision
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningPolicy
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.swift.application.port.out.AmlCasePort
import com.openbank.swift.application.port.out.OpenAmlCaseCommand
import com.openbank.swift.application.port.out.SanctionsScreeningPort
import com.openbank.swift.application.port.out.ScreeningUnavailableException
import com.openbank.swift.domain.model.SwiftMessage
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger

/** What the rail may do with a message after screening. */
enum class SwiftScreeningVerdict {
    /** Every name cleared: the message may be released to the scheme. */
    RELEASE,

    /** A potential hit, or screening could not run: hold the message (fail closed) for a human. */
    HOLD,

    /** A confirmed or high-score hit: the message must be rejected and never released. */
    REJECT,
}

/**
 * Sanctions gate in front of every SWIFT release (ADR-0032, ADR-0353 D4). Swift's compliance doc
 * used to say screening was expected UPSTREAM; nothing upstream did it, so an MT103 reached the
 * scheme gateway with neither party screened. This screens the ordering customer (debtor) and the
 * beneficiary (creditor) through the shared [ScreeningPolicy], the same decision sepa-payment,
 * domestic-payment and fx-service use:
 *
 *  - BLOCK  → [SwiftScreeningVerdict.REJECT] + a CRITICAL AML case;
 *  - REVIEW → [SwiftScreeningVerdict.HOLD] + a HIGH AML case;
 *  - sanctions-service unreachable → [SwiftScreeningVerdict.HOLD] + a MEDIUM case (fail closed);
 *  - a blank debtor or beneficiary name → [SwiftScreeningVerdict.HOLD] (never screened as "nobody");
 *  - CLEAR  → [SwiftScreeningVerdict.RELEASE].
 */
@ApplicationScoped
class SwiftSanctionsScreening(
    private val screeningPort: SanctionsScreeningPort,
    private val amlCasePort: AmlCasePort,
) {
    private val log = Logger.getLogger(SwiftSanctionsScreening::class.java)

    suspend fun screen(message: SwiftMessage): SwiftScreeningVerdict {
        // Fail closed on an unscreenable party: a blank name must never mean "skip that side".
        val debtor = message.orderingCustomerName?.takeIf { it.isNotBlank() }
        val creditor = message.beneficiaryName.takeIf { it.isNotBlank() }
        if (debtor == null || creditor == null) {
            log.warnf("SWIFT message %s has a blank party name; holding unscreened", message.id)
            openCaseQuietly(message, AmlCaseSeverity.MEDIUM, ALERT_PARTY_UNSCREENABLE, "blank party name", null)
            return SwiftScreeningVerdict.HOLD
        }
        val subjects = listOf(debtor to ScreeningRole.DEBTOR, creditor to ScreeningRole.CREDITOR)
        // One nonce per screening attempt: the adapter's own retries reuse it (dedupe), but a held
        // message screened again gets a FRESH screen, never sanctions-service's stored answer for an
        // old key — which would replay a stale CLEAR after a list update or a name change. Same
        // shape as sdd's CollectionScreeningGate.
        val attempt = "swift-${message.id}-${Ids.randomId()}"
        val results = try {
            subjects.map { (name, role) ->
                screeningPort.screen(name, role, "$attempt:${role.name.lowercase()}")
            }
        } catch (ex: ScreeningUnavailableException) {
            log.warnf(ex, "Sanctions screening unavailable for SWIFT message %s; holding", message.id)
            openCaseQuietly(message, AmlCaseSeverity.MEDIUM, ALERT_SCREENING_UNAVAILABLE, ex.message, null)
            return SwiftScreeningVerdict.HOLD
        }
        val decision = ScreeningPolicy.decide(results)
        if (decision != ScreeningDecision.CLEAR) {
            val nonClear = results.filter {
                it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
            }
            openCaseQuietly(
                message,
                if (decision == ScreeningDecision.BLOCK) AmlCaseSeverity.CRITICAL else AmlCaseSeverity.HIGH,
                ALERT_SANCTIONS_HIT,
                nonClear.joinToString("; ") { "${it.role}:${it.status}:${it.score}" },
                nonClear.firstNotNullOfOrNull { it.matchedEntity },
            )
        }
        return when (decision) {
            ScreeningDecision.BLOCK -> SwiftScreeningVerdict.REJECT
            ScreeningDecision.REVIEW -> SwiftScreeningVerdict.HOLD
            ScreeningDecision.CLEAR -> SwiftScreeningVerdict.RELEASE
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun openCaseQuietly(
        message: SwiftMessage,
        riskLevel: AmlCaseSeverity,
        alertCode: String,
        detail: String?,
        matchedEntity: String?,
    ) {
        val accountId = message.orderingCustomerAccountId
        if (accountId == null) {
            // aml-service requires a party/account; a message with no ordering-customer account id
            // cannot carry one. The verdict above still holds or rejects the message — only the
            // case is lost, and loudly.
            log.errorf(
                "aml.case.not_opened swift_message_id=%s alert=%s — no orderingCustomerAccountId",
                message.id,
                alertCode,
            )
            return
        }
        try {
            amlCasePort.openCase(
                OpenAmlCaseCommand(
                    idempotencyKey = "aml-swift-${message.id}-$alertCode",
                    messageId = message.id,
                    orderingCustomerAccountId = accountId,
                    customerReference = "${message.orderingCustomerName.orEmpty()} / ${message.transactionReference}",
                    riskLevel = riskLevel,
                    alertCode = alertCode,
                    alertDetail = detail,
                    matchedEntity = matchedEntity,
                ),
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.errorf(ex, "Failed to open AML case (%s) for SWIFT message %s", alertCode, message.id)
        }
    }

    companion object {
        const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
        const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"
        const val ALERT_PARTY_UNSCREENABLE = "PARTY_UNSCREENABLE"
    }
}
