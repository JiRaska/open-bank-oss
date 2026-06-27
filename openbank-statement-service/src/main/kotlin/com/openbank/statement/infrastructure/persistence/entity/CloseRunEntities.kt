// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.persistence.entity

import com.openbank.statement.domain.model.CloseFailureReason
import com.openbank.statement.domain.model.CloseRunStatus
import com.openbank.statement.domain.model.CloseTrigger
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Operational telemetry for one scheduled/manual close pass (ADR-0069 D3 / issue #470). */
@Entity
@Table(name = "statement_close_run")
class CloseRunEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "trigger", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    lateinit var trigger: CloseTrigger

    @Column(name = "status", nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    lateinit var status: CloseRunStatus

    @Column(name = "period_from")
    var periodFrom: LocalDate? = null

    @Column(name = "period_to")
    var periodTo: LocalDate? = null

    @Column(name = "accounts_enumerated", nullable = false)
    var accountsEnumerated: Int = 0

    @Column(name = "pockets_closed", nullable = false)
    var pocketsClosed: Int = 0

    @Column(name = "pockets_failed", nullable = false)
    var pocketsFailed: Int = 0

    @Column(name = "pockets_skipped", nullable = false)
    var pocketsSkipped: Int = 0

    @Column(name = "started_at", nullable = false)
    lateinit var startedAt: Instant

    @Column(name = "finished_at")
    var finishedAt: Instant? = null
}

/** A single per-pocket failure within a [CloseRunEntity]. */
@Entity
@Table(name = "statement_close_failure")
class CloseFailureEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "run_id", nullable = false)
    lateinit var runId: UUID

    @Column(name = "account_id", nullable = false)
    lateinit var accountId: UUID

    @Column(name = "pocket_currency", nullable = false, length = 3)
    lateinit var pocketCurrency: String

    @Column(name = "period_from", nullable = false)
    lateinit var periodFrom: LocalDate

    @Column(name = "period_to", nullable = false)
    lateinit var periodTo: LocalDate

    @Column(name = "reason", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    lateinit var reason: CloseFailureReason

    @Column(name = "detail", columnDefinition = "TEXT")
    var detail: String? = null

    @Column(name = "failed_at", nullable = false)
    lateinit var failedAt: Instant
}
