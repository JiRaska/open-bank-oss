// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.fincrime.AmlCaseSeverity
import com.openbank.libs.fincrime.ScreeningDecision
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningPolicy
import com.openbank.libs.fincrime.ScreeningResult
import com.openbank.libs.fincrime.ScreeningRole
import com.openbank.sdd.application.port.out.AmlCasePort
import com.openbank.sdd.application.port.out.OpenAmlCaseCommand
import com.openbank.sdd.application.port.out.SanctionsScreeningPort
import com.openbank.sdd.application.port.out.ScreeningUnavailableException
import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.authorise.CollectionInstruction
import com.openbank.sdd.domain.model.SddMandate
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Sanctions gate in front of a collection's release (ADR-0032, ADR-0353 D4). Screens the debtor and
 * the creditor named on the mandate, renders the shared [ScreeningPolicy] verdict, and answers
 * `null` only for CLEAR. Anything else is a [AuthorisationResult.Reject] with EPC reason `RR04`
 * (regulatory reason), so no `sdd.collection.authorised` event is emitted and no debit is booked:
 *
 *  - BLOCK: rejected, CRITICAL AML case.
 *  - REVIEW: held (rejected for this presentation), HIGH AML case for a human to decide.
 *  - screening unavailable: held fail-closed, MEDIUM AML case. Never released un-screened.
 *
 * The replay guard in [SddMandateService.authorise] keys on the stamped collection date, which is
 * only stamped on CLEAR, so a held collection re-presented later is screened again.
 */
@ApplicationScoped
class CollectionScreeningGate(private val screening: SanctionsScreeningPort, private val amlCases: AmlCasePort) {
    private val log = Logger.getLogger(CollectionScreeningGate::class.java)

    fun screen(mandate: SddMandate, instruction: CollectionInstruction): Uni<AuthorisationResult?> {
        val key = "sdd-screen-${mandate.id}-${instruction.dueDate}"
        // One nonce per presentation attempt: retries inside the adapter reuse it (dedupe), but a held
        // collection presented again gets a FRESH screen, never sanctions-service's stored answer for
        // the old key (which would replay a stale CLEAR after a list update or a name change).
        val attempt = "$key-${Ids.randomId()}"
        return Uni.combine().all().unis(
            screening.screen(mandate.debtorName, ScreeningRole.DEBTOR, "$attempt:debtor"),
            screening.screen(mandate.creditorName, ScreeningRole.CREDITOR, "$attempt:creditor"),
        ).with { d, c -> listOf(d, c) }
            .onItem().transformToUni { results -> decide(mandate, key, results) }
            .onFailure(ScreeningUnavailableException::class.java).recoverWithUni { ex ->
                log.warnf(ex, "Sanctions screening unavailable for SDD mandate %s; collection held", mandate.id)
                openCaseBestEffort(mandate, key, AmlCaseSeverity.MEDIUM, ALERT_SCREENING_UNAVAILABLE, null, null)
                    .map { held(HOLD_UNAVAILABLE) }
            }
    }

    private fun decide(mandate: SddMandate, key: String, results: List<ScreeningResult>): Uni<AuthorisationResult?> {
        val decision = ScreeningPolicy.decide(results)
        if (decision == ScreeningDecision.CLEAR) return Uni.createFrom().item(null as AuthorisationResult?)
        val flagged = results.filter {
            it.status != ScreeningMatchStatus.CLEAR && it.status != ScreeningMatchStatus.WHITELISTED
        }
        val severity = if (decision == ScreeningDecision.BLOCK) AmlCaseSeverity.CRITICAL else AmlCaseSeverity.HIGH
        val detail = flagged.joinToString("; ") { "${it.role}:${it.status}:${it.score}" }
        return openCaseBestEffort(
            mandate,
            key,
            severity,
            ALERT_SANCTIONS_HIT,
            detail,
            flagged.firstOrNull()?.matchedEntity,
        )
            .map { held(if (decision == ScreeningDecision.BLOCK) BLOCKED else HOLD_REVIEW) }
    }

    private fun openCaseBestEffort(
        mandate: SddMandate,
        key: String,
        severity: AmlCaseSeverity,
        alertCode: String,
        detail: String?,
        matchedEntity: String?,
    ): Uni<Unit> = amlCases.openCase(
        OpenAmlCaseCommand(
            // Bound to the verdict: a later, worse verdict (REVIEW, then BLOCK) opens its own case
            // instead of colliding with the first one's key and being dropped as a replay.
            idempotencyKey = "$key:aml:$alertCode:$severity",
            mandateId = mandate.id,
            debtorAccountId = mandate.accountId,
            customerReference = mandate.umr,
            riskLevel = severity,
            alertCode = alertCode,
            alertDetail = detail,
            matchedEntity = matchedEntity,
        ),
    ).map { }
        .onFailure().recoverWithItem { ex ->
            // Best effort: the verdict is already rendered and must not flip on a case-store outage.
            // ERROR plus a stable event name, so a lost case (including a 409 key reuse) is alertable.
            log.errorf(ex, "aml.case.open_failed mandate_id=%s alert=%s severity=%s", mandate.id, alertCode, severity)
        }

    private fun held(reason: String): AuthorisationResult = AuthorisationResult.Reject(REASON_CODE, reason)

    private companion object {
        const val REASON_CODE = "RR04"
        const val BLOCKED = "Collection blocked by sanctions screening"
        const val HOLD_REVIEW = "Collection held for sanctions review"
        const val HOLD_UNAVAILABLE = "Collection held: sanctions screening unavailable"
        const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"
        const val ALERT_SCREENING_UNAVAILABLE = "SCREENING_UNAVAILABLE"
    }
}
