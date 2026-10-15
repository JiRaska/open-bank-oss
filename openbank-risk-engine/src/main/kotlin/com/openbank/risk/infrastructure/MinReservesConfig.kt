// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.domain.reserves.CalendarStatus
import com.openbank.risk.domain.reserves.MaintenanceCalendar
import com.openbank.risk.domain.reserves.MaintenancePeriod
import com.openbank.risk.domain.reserves.MinReserveParameters
import com.openbank.risk.domain.reserves.ReserveClass
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `openbank.risk.min-reserves.*` — the versioned ČNB minimum reserve parameter set (ADR-0313
 * treasury gap, ADR-0315): classification, holding currency and calendar. A config MAPPING: every
 * member is required. The reserve RATIO and its REMUNERATION are deliberately not here: they are
 * ČNB facts that change over time (2 % -> 4 % on 2025-01-02), read per as-of date from
 * `cnb_policy_rate_fact`, which fx-service's ČNB ingestion feeds.
 */
@ConfigMapping(prefix = "openbank.risk.min-reserves")
interface MinReservesConfig {
    @WithName("parameter-set-id")
    fun parameterSetId(): String

    @WithName("parameter-set-version")
    fun parameterSetVersion(): String

    fun source(): String

    @WithName("holding-currency")
    fun holdingCurrency(): String

    fun classification(): Classification

    @WithName("maintenance-calendar")
    fun maintenanceCalendar(): Calendar

    /** The versioned ČNB maintenance-period calendar (ADR-0315 D8); see application.yaml for its status. */
    interface Calendar {
        fun id(): String

        fun version(): String

        /** `verified` or `sample-unverified`; every averaging result repeats it. */
        fun status(): String

        fun source(): String

        fun periods(): List<Period>
    }

    interface Period {
        fun id(): String

        /** ISO date, first day of the period (inclusive). */
        fun start(): String

        /** ISO date, last day of the period (inclusive). */
        fun end(): String

        /** ISO date whose reserve base sets this period's requirement. */
        @WithName("base-reference-date")
        fun baseReferenceDate(): String
    }

    interface Classification {
        /** GL code → class wire name. */
        @WithName("gl-accounts")
        fun glAccounts(): Map<String, String>

        /** GL account TYPE (ASSET, EQUITY, …) → class, for accounts [glAccounts] does not name. */
        @WithName("gl-account-types")
        fun glAccountTypes(): Map<String, String>
    }
}

fun MinReservesConfig.toParameters(): MinReserveParameters = MinReserveParameters(
    id = parameterSetId(),
    version = parameterSetVersion(),
    source = source(),
    holdingCurrency = holdingCurrency().trim().uppercase(),
    glAccounts = classification().glAccounts().mapKeys { it.key.trim() }.mapValues { ReserveClass.parse(it.value) },
    glAccountTypes = classification().glAccountTypes()
        .mapKeys { it.key.trim().uppercase() }
        .mapValues { ReserveClass.parse(it.value) },
)

fun MinReservesConfig.toCalendar(): MaintenanceCalendar = maintenanceCalendar().let { c ->
    MaintenanceCalendar(
        id = c.id(),
        version = c.version(),
        status = CalendarStatus.parse(c.status()),
        source = c.source(),
        periods = c.periods().map {
            MaintenancePeriod(
                id = it.id().trim(),
                start = calendarDate(it.id(), "start", it.start()),
                end = calendarDate(it.id(), "end", it.end()),
                baseReferenceDate = calendarDate(it.id(), "base-reference-date", it.baseReferenceDate()),
            )
        },
    )
}

private fun calendarDate(periodId: String, field: String, raw: String): LocalDate = try {
    LocalDate.parse(raw.trim())
} catch (e: DateTimeParseException) {
    throw IllegalArgumentException("maintenance period $periodId: $field '$raw' is not an ISO date", e)
}
