// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.ledger.application.port.`in`.AccountingDayUseCase
import com.openbank.ledger.application.port.`in`.GetAccountingDayQuery
import com.openbank.ledger.application.port.`in`.ListAccountingDaysQuery
import com.openbank.ledger.application.port.`in`.OpenAccountingDayCommand
import com.openbank.ledger.application.port.`in`.TransitionAccountingDayCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.domain.event.AccountingDayTransitionedEvent
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.checkConflict
import com.openbank.ledger.domain.model.requireValid
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate

/**
 * The accounting-day authority (ADR-0207 D2/D4).
 *
 * Owns the `OPEN → CUTOFF → TIED_OUT → LOCKED` lifecycle and publishes every transition on the
 * existing outbox, in the same transaction as the state change. Consumers **react** to those
 * events; they do not query this service per posting (ADR-0207 D4 — a synchronous day-state
 * lookup on every money-path hot path would make ledger-service a hard availability dependency
 * of every other service, a worse failure than the one being fixed).
 */
@ApplicationScoped
class AccountingDayService(
    private val accountingDayRepository: AccountingDayRepository,
    private val objectMapper: ObjectMapper,
    private val accountingClock: AccountingClock,
    private val clock: Clock,
) : AccountingDayUseCase {

    override fun currentBusinessDate(): LocalDate = accountingClock.today()

    override suspend fun open(command: OpenAccountingDayCommand): AccountingDayRecord {
        requireValid(!accountingClock.isFuture(command.businessDate)) {
            "Cannot open accounting day ${command.businessDate} — it is in the future " +
                "(current accounting day is ${accountingClock.today()})"
        }
        checkConflict(accountingDayRepository.findByDate(command.businessDate) == null) {
            "Accounting day ${command.businessDate} is already open — a day is opened once"
        }

        val record = AccountingDayRecord.open(
            businessDate = command.businessDate,
            openedAt = clock.instant(),
            openedBy = command.openedBy,
        )
        return accountingDayRepository.saveOpened(
            record,
            transitionMessage(record, from = STATUS_NONE, by = command.openedBy),
        )
    }

    override suspend fun transition(command: TransitionAccountingDayCommand): AccountingDayRecord {
        val current = accountingDayRepository.findByDate(command.businessDate)
            ?: throw AccountingDayNotFoundException("Accounting day ${command.businessDate} has not been opened")

        // transitionTo enforces monotonic single-step progression and throws a 409 conflict
        // naming the only legal next state, so an operator driving the wrong day is told so
        // rather than silently no-op'ing.
        val moved = current.transitionTo(command.to, command.transitionedBy, clock.instant())

        return accountingDayRepository.saveTransition(
            record = moved,
            expectedVersion = current.version,
            outbox = transitionMessage(moved, from = current.status.name, by = command.transitionedBy),
        )
    }

    override suspend fun get(query: GetAccountingDayQuery): AccountingDayRecord =
        accountingDayRepository.findByDate(query.businessDate)
            ?: throw AccountingDayNotFoundException("Accounting day ${query.businessDate} has not been opened")

    override suspend fun list(query: ListAccountingDaysQuery): List<AccountingDayRecord> {
        requireValid(!query.from.isAfter(query.to)) { "from (${query.from}) must not be after to (${query.to})" }
        return accountingDayRepository.findRange(query.from, query.to)
    }

    private fun transitionMessage(record: AccountingDayRecord, from: String, by: String) = OutboxMessage(
        aggregateId = record.id,
        eventType = ACCOUNTING_DAY_TRANSITIONED,
        payload = objectMapper.writeValueAsString(
            AccountingDayTransitionedEvent(
                aggregateId = record.id,
                version = record.version,
                occurredAt = clock.instant(),
                businessDate = record.businessDate,
                fromStatus = from,
                toStatus = record.status.name,
                transitionedBy = by,
            ),
        ),
    )

    companion object {
        private const val ACCOUNTING_DAY_TRANSITIONED = "AccountingDayTransitioned"

        /**
         * `fromStatus` for the opening event: the day did not exist before. Opening is published
         * on the same event type as every other transition so a consumer needs one subscription,
         * not two, to track the whole lifecycle.
         */
        const val STATUS_NONE = "NONE"

        /** Statuses an operator may drive a day to, in order. */
        val OPERATOR_TRANSITIONS: List<AccountingDayStatus> = listOf(
            AccountingDayStatus.CUTOFF,
            AccountingDayStatus.TIED_OUT,
            AccountingDayStatus.LOCKED,
        )
    }
}

/** No accounting-day row for the requested date. Mapped to 404. */
class AccountingDayNotFoundException(message: String) : RuntimeException(message)
