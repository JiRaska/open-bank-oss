// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.CloseParticipationUseCase
import com.openbank.ledger.application.port.`in`.ReportParticipationCommand
import com.openbank.ledger.application.port.`in`.WaiveParticipationCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.CloseParticipationRepository
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.CloseCompleteness
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.ParticipationOutcome
import com.openbank.ledger.domain.model.requireValid
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/**
 * The declared day-close participants (ADR-0342 D2), e.g.
 *
 * ```yaml
 * openbank.ledger.close:
 *   participants-mode: shadow
 *   participants:
 *     interest:
 *       principal: service-account-openbank-interest
 * ```
 *
 * The map key is the participant name a product service reports under; [Participant.principal] is
 * the ONE verified service-account allowed to report for it.
 */
@ConfigMapping(prefix = "openbank.ledger.close")
interface CloseParticipantsConfig {

    /** `off | shadow | enforce` — whether a missing participant holds `CUTOFF → TIED_OUT`. */
    @WithDefault(MODE_SHADOW)
    fun participantsMode(): String

    fun participants(): Map<String, Participant>

    interface Participant {
        fun principal(): String
    }

    companion object {
        const val MODE_OFF = "off"
        const val MODE_SHADOW = "shadow"
        const val MODE_ENFORCE = "enforce"
    }
}

/**
 * Records day-close participation and answers completeness (ADR-0342 D1–D3).
 *
 * Reporting is a write by a MACHINE that the RBAC/OPA layer admits under `ledger.create` — the grant
 * every participant already holds to post journals. That grant alone would let any participant
 * report any other as complete, so this use case narrows it by identity: the caller's verified
 * service-account must be the principal declared for the participant it names.
 */
@ApplicationScoped
class CloseParticipationService(
    private val config: CloseParticipantsConfig,
    private val repository: CloseParticipationRepository,
    private val accountingDayRepository: AccountingDayRepository,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) : CloseParticipationUseCase {

    private val log = Logger.getLogger(CloseParticipationService::class.java)

    override fun declaredParticipants(): Set<String> = config.participants().keys

    override suspend fun report(command: ReportParticipationCommand): CloseParticipationRecord {
        val declared = config.participants()[command.participant]
            ?: throw CloseParticipantNotDeclaredException(
                "'${command.participant}' is not a declared day-close participant " +
                    "(declared: ${declaredParticipants().sorted()})",
            )
        if (command.callerPrincipal == null || command.callerPrincipal != declared.principal()) {
            throw CloseParticipantForbiddenException(
                "Only ${declared.principal()} may report for participant '${command.participant}'",
            )
        }
        requireValid(command.outcome != ParticipationOutcome.WAIVED) {
            "WAIVED is an operator outcome; a participant reports RAN, SKIPPED or REFUSED"
        }
        val record = record(
            businessDate = command.businessDate,
            participant = command.participant,
            outcome = command.outcome,
            journalsPosted = command.journalsPosted,
            itemsProcessed = command.itemsProcessed,
            reason = command.reason,
            by = command.callerPrincipal,
        )
        if (record.late) {
            log.warnf(
                "Day-close participant %s reported %s for %s AFTER the day left CUTOFF — stored as late, " +
                    "the day does not move",
                record.participant,
                record.outcome,
                record.businessDate,
            )
        }
        return record
    }

    override suspend fun waive(command: WaiveParticipationCommand): CloseParticipationRecord {
        if (command.participant !in config.participants()) {
            throw CloseParticipantNotDeclaredException(
                "'${command.participant}' is not a declared day-close participant",
            )
        }
        return record(
            businessDate = command.businessDate,
            participant = command.participant,
            outcome = ParticipationOutcome.WAIVED,
            journalsPosted = 0,
            itemsProcessed = 0,
            reason = command.reason,
            by = command.waivedBy,
        )
    }

    override suspend fun completeness(businessDate: LocalDate): CloseCompleteness =
        CloseCompleteness.of(businessDate, declaredParticipants(), repository.findForDate(businessDate))

    private suspend fun record(
        businessDate: LocalDate,
        participant: String,
        outcome: ParticipationOutcome,
        journalsPosted: Int,
        itemsProcessed: Int,
        reason: String?,
        by: String,
    ): CloseParticipationRecord {
        val dayStatus = accountingDayRepository.findByDate(businessDate)?.status
        val record = CloseParticipationRecord(
            businessDate = businessDate,
            participant = participant,
            outcome = outcome,
            journalsPosted = journalsPosted,
            itemsProcessed = itemsProcessed,
            reason = reason?.trim()?.takeIf { it.isNotEmpty() },
            reportedBy = by,
            reportedAt = clock.instant(),
            late = dayStatus == AccountingDayStatus.TIED_OUT || dayStatus == AccountingDayStatus.LOCKED,
        )
        val saved = repository.append(record)
        meterRegistry.counter(
            REPORTS_METRIC,
            "participant",
            participant,
            "outcome",
            outcome.name.lowercase(),
            "late",
            saved.late.toString(),
        ).increment()
        return saved
    }

    companion object {
        const val REPORTS_METRIC = "openbank.ledger.close.participation.reports"
    }
}

/** The named participant is not declared in configuration. Mapped to 404. */
class CloseParticipantNotDeclaredException(message: String) : RuntimeException(message)

/** The caller is not the service-account declared for the participant it reports for. Mapped to 403. */
class CloseParticipantForbiddenException(message: String) : RuntimeException(message)
