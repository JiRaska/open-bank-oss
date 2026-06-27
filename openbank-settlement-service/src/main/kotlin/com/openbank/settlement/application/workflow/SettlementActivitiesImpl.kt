// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.SettlementStatus
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
open class SettlementActivitiesImpl(
    private val settlementRepository: SettlementRepository,
    private val debitPort: DebitPort,
    private val creditPort: CreditPort,
    private val ledgerPort: LedgerPort,
) : SettlementActivities {

    private val log = Logger.getLogger(SettlementActivitiesImpl::class.java)

    override fun debitPayer(settlementId: UUID): Unit = runOnVertxContext {
        log.infof("Debiting payer for settlement %s", settlementId)
        debitPort.debit(settlementId)
        settlementRepository.updateStatus(settlementId, SettlementStatus.DEBITED)
        Unit
    }

    override fun creditPayee(settlementId: UUID): Unit = runOnVertxContext {
        log.infof("Crediting payee for settlement %s", settlementId)
        creditPort.credit(settlementId)
        settlementRepository.updateStatus(settlementId, SettlementStatus.CREDITED)
        Unit
    }

    override fun bookToLedger(settlementId: UUID): Unit = runOnVertxContext {
        log.infof("Booking settlement %s to ledger", settlementId)
        ledgerPort.book(settlementId)
        settlementRepository.updateStatus(settlementId, SettlementStatus.BOOKED)
        Unit
    }

    override fun reverseDebit(settlementId: UUID): Unit = runOnVertxContext {
        log.warnf(
            "Reversing debit for settlement %s (compensation — stub: wire reversal to balance-service)",
            settlementId,
        )
        settlementRepository.updateStatus(settlementId, SettlementStatus.REVERSED)
        Unit
    }

    override fun reverseCredit(settlementId: UUID): Unit = runOnVertxContext {
        log.warnf(
            "Reversing credit for settlement %s (compensation — stub: wire reversal to balance-service)",
            settlementId,
        )
        settlementRepository.updateStatus(settlementId, SettlementStatus.CREDITED_REVERSED)
        Unit
    }

    override fun reverseBookToLedger(settlementId: UUID): Unit = runOnVertxContext {
        log.warnf(
            "Reversing ledger booking for settlement %s (compensation — stub: wire reversal to ledger-service)",
            settlementId,
        )
        settlementRepository.updateStatus(settlementId, SettlementStatus.LEDGER_REVERSED)
        Unit
    }

    override fun rejectSettlement(settlementId: UUID): Unit = runOnVertxContext {
        log.warnf("Rejecting settlement %s after compensation", settlementId)
        settlementRepository.updateStatus(settlementId, SettlementStatus.REJECTED)
        Unit
    }

    /**
     * Run a reactive (Hibernate Reactive / Mutiny) suspend [block] on a Vert.x duplicated context.
     *
     * Temporal activity methods run on a Temporal worker thread with **no** current Vert.x context, so a
     * naive `runBlocking { panache.withSession { ... } }` fails with `IllegalStateException: No current
     * Vertx context found` and the reactive Panache session cannot open. (Surfaced by the ADR-0101 P3
     * settlement go-live e2e: the settlement workflow REJECTED because every activity's repo access threw.)
     *
     * [VertxContextSupport.subscribeAndAwait] establishes a fresh duplicated context, subscribes the
     * resulting Uni on it and blocks the worker thread until it completes — the activity-thread equivalent
     * of a request thread's ambient context. Mirrors `SepaPaymentActivitiesImpl.runOnVertxContext`.
     * `protected open` so tests can override it to run synchronously.
     */
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
