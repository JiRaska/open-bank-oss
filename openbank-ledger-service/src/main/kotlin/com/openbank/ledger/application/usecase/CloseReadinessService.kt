// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.out.CloseEvidenceRepository
import com.openbank.ledger.domain.model.CloseReadiness
import com.openbank.libs.domain.calendar.AccountingClock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.YearMonth

@ApplicationScoped
class CloseReadinessService(
    private val repository: CloseEvidenceRepository,
    private val config: CloseParticipantsConfig,
    private val clock: Clock,
    private val readinessConfig: CloseReadinessConfig,
) {
    suspend fun get(month: String?): CloseReadiness {
        val today = AccountingClock.bank(clock).today()
        val period = if (month == null) {
            YearMonth.from(today).minusMonths(1)
        } else {
            require(Regex("\\d{4}-\\d{2}").matches(month)) { "month must use YYYY-MM" }
            try {
                YearMonth.parse(month)
            } catch (_: java.time.DateTimeException) {
                throw IllegalArgumentException("month must use YYYY-MM")
            }
        }
        require(period < YearMonth.from(today)) { "month must be a completed calendar month" }
        return CloseReadiness.evaluate(
            period,
            today,
            config.participants().mapValues { it.value.principal() },
            repository.findMonth(period.atDay(1), period.atEndOfMonth()),
            readinessConfig.requiredJobs(),
        )
    }
}
