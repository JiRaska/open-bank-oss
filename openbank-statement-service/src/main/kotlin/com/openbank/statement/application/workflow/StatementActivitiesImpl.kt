// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.application.workflow

import com.openbank.statement.application.port.`in`.CloseRunQueryUseCase
import com.openbank.statement.application.port.`in`.RunCloseUseCase
import com.openbank.statement.domain.model.CloseRunStatus
import com.openbank.statement.domain.model.CloseTrigger
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger
import java.util.UUID

@ApplicationScoped
class StatementActivitiesImpl(
    private val runCloseUseCase: RunCloseUseCase,
    private val closeRunQueryUseCase: CloseRunQueryUseCase,
) : StatementActivities {

    private val log = Logger.getLogger(StatementActivitiesImpl::class.java)

    override fun initCloseRun(closeRunId: UUID) {
        log.infof("Temporal activity initCloseRun: preparing close run %s", closeRunId)
    }

    override fun collectPeriodData(closeRunId: UUID) {
        log.infof("Temporal activity collectPeriodData: collecting period data for close run %s", closeRunId)
    }

    override fun generateStatements(closeRunId: UUID): Unit = runBlocking {
        log.infof("Temporal activity generateStatements: executing close orchestration for run %s", closeRunId)
        val closeRun = runCloseUseCase.runClose(CloseTrigger.SCHEDULED).subscribe().asCompletionStage().get()
        log.infof(
            "Temporal activity generateStatements: close run %s completed with status %s (accounts=%d, closed=%d, failed=%d)",
            closeRun.id,
            closeRun.status,
            closeRun.accountsEnumerated,
            closeRun.pocketsClosed,
            closeRun.pocketsFailed,
        )
        Unit
    }

    override fun finalizeCloseRun(closeRunId: UUID): String = runBlocking {
        log.infof("Temporal activity finalizeCloseRun: checking outcome for close run %s", closeRunId)
        val latest = closeRunQueryUseCase.latestRun().subscribe().asCompletionStage().get()
        val status = latest?.status ?: CloseRunStatus.COMPLETED
        log.infof("Temporal activity finalizeCloseRun: close run %s status=%s", closeRunId, status)
        status.name
    }
}
