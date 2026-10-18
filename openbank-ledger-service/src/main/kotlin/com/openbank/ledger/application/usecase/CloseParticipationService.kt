// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.CloseParticipationUseCase
import com.openbank.ledger.application.port.`in`.ReportParticipationCommand
import com.openbank.ledger.application.port.`in`.WaiveParticipationCommand
import com.openbank.ledger.application.port.out.AppendGuard
import com.openbank.ledger.application.port.out.AppendVerdict
import com.openbank.ledger.application.port.out.CloseParticipationRepository
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.CloseCompleteness
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.domain.model.ParticipationOutcome
import com.openbank.ledger.domain.model.requireValid
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
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

    /**
     * `off | shadow | enforce` — whether a missing or REFUSED participant holds `CUTOFF → TIED_OUT`.
     * Any other value fails the boot ([CloseParticipantsModeValidator]) rather than silently
     * behaving as one of the three.
     */
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
        val MODES: Set<String> = setOf(MODE_OFF, MODE_SHADOW, MODE_ENFORCE)

        /** Throws [IllegalStateException] unless [mode] is exactly one of [MODES]. */
        fun requireKnownMode(mode: String): String {
            check(mode in MODES) {
                "openbank.ledger.close.participants-mode='$mode' is not one of ${MODES.sorted()}"
            }
            return mode
        }
    }
}

/**
 * Fails the boot on an unknown `participants-mode` (ADR-0342). The rollout convention keeps
 * `shadow` as the default, but a typo such as `enforced` would otherwise fall through every
 * `when` as 'shadow' and leave the operator believing the day was held.
 */
@ApplicationScoped
class CloseParticipantsModeValidator(private val config: CloseParticipantsConfig) {
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        CloseParticipantsConfig.requireKnownMode(config.participantsMode())
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
            guard = reportGuard(command.outcome),
        )
        if (record.outcome == ParticipationOutcome.WAIVED && command.outcome != ParticipationOutcome.WAIVED) {
            log.infof(
                "Day-close participant %s reported %s for %s but an operator WAIVED it - the waiver stands",
                command.participant,
                command.outcome,
                command.businessDate,
            )
        }
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
        // A waiver is the operator's way out of a HELD day, so it only exists for a day that is held:
        // an existing CUTOFF day. An unknown day (never opened, hence also any future day) is 404,
        // and an OPEN/TIED_OUT/LOCKED day is a conflict - waiving ahead of time would pre-approve a
        // close, and waiving after it would only rewrite history.
        //
        // Append-only: a waiver never rewrites a row, it supersedes the LATEST one. It may supersede
        // a REFUSED report (the owner's explicit decision to hold the day without that work), but a
        // participant whose latest report is RAN has nothing to waive. Both checks run inside the
        // repository's append transaction (see [waiveGuard]) so the status and the latest row they
        // read are the ones the insert commits against.
        return record(
            businessDate = command.businessDate,
            participant = command.participant,
            outcome = ParticipationOutcome.WAIVED,
            journalsPosted = 0,
            itemsProcessed = 0,
            reason = command.reason,
            by = command.waivedBy,
            guard = waiveGuard(command.businessDate, command.participant),
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
        guard: AppendGuard,
    ): CloseParticipationRecord {
        val record = CloseParticipationRecord(
            businessDate = businessDate,
            participant = participant,
            outcome = outcome,
            journalsPosted = journalsPosted,
            itemsProcessed = itemsProcessed,
            reason = reason?.trim()?.takeIf { it.isNotEmpty() },
            reportedBy = by,
            // Placeholders: the repository seals the real instant and `late` under its lock.
            reportedAt = clock.instant(),
            late = false,
        )
        val saved = repository.appendGuarded(record, guard)
        // An existing WAIVED row was kept: nothing new was stored, so nothing is counted.
        if (saved.outcome == ParticipationOutcome.WAIVED && outcome != ParticipationOutcome.WAIVED) return saved
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

        /**
         * A participant's non-RAN report must not supersede an operator WAIVED row: the reporter
         * re-sends its last few days after every restart, and that would silently undo the waiver and
         * re-hold the day. A RAN report does supersede it - the work really was done.
         */
        fun reportGuard(outcome: ParticipationOutcome) = AppendGuard { latest, _ ->
            if (latest?.outcome == ParticipationOutcome.WAIVED && outcome != ParticipationOutcome.RAN) {
                AppendVerdict.KeepExisting
            } else {
                AppendVerdict.Append
            }
        }

        /**
         * Day must exist and be in CUTOFF; the participant's LATEST report must not be RAN. A waiver
         * replayed onto a participant that is already WAIVED returns the existing row instead of
         * appending a duplicate, which is what makes `PUT …/waiver` idempotent on its path
         * (ADR-0294 idiom).
         */
        fun waiveGuard(businessDate: LocalDate, participant: String) = AppendGuard { latest, dayStatus ->
            when {
                latest?.outcome == ParticipationOutcome.WAIVED -> AppendVerdict.KeepExisting
                dayStatus == null -> AppendVerdict.Reject(
                    AccountingDayNotFoundException(
                        "Accounting day $businessDate has not been opened - nothing to waive",
                    ),
                )
                dayStatus != AccountingDayStatus.CUTOFF -> AppendVerdict.Reject(
                    LedgerConflictException(
                        "Participant '$participant' can only be waived for a day in CUTOFF; " +
                            "$businessDate is $dayStatus",
                    ),
                )
                latest?.outcome == ParticipationOutcome.RAN -> AppendVerdict.Reject(
                    LedgerConflictException(
                        "Participant '$participant' already reported RAN for $businessDate; " +
                            "a RAN report cannot be waived",
                    ),
                )
                else -> AppendVerdict.Append
            }
        }
    }
}

/** The named participant is not declared in configuration. Mapped to 404. */
class CloseParticipantNotDeclaredException(message: String) : RuntimeException(message)

/** The caller is not the service-account declared for the participant it reports for. Mapped to 403. */
class CloseParticipantForbiddenException(message: String) : RuntimeException(message)
