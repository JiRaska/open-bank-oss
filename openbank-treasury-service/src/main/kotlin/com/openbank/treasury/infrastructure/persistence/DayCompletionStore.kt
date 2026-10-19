// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.persistence

import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.domain.model.DayStepStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "treasury_day_completion")
class DayCompletionEntity : PanacheEntity() {
    @Column(name = "business_date", nullable = false)
    lateinit var businessDate: LocalDate

    @Column(name = "step", nullable = false)
    lateinit var step: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "item_count")
    var itemCount: Int? = null

    @Column(name = "detail")
    var detail: String? = null

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant
}

@ApplicationScoped
class DayCompletionPanacheRepository : PanacheRepository<DayCompletionEntity>

@ApplicationScoped
class DayCompletionStore(private val rows: DayCompletionPanacheRepository) : DayCompletionRepository {
    /** Upsert on (date, step) under the unique constraint: the last run's outcome is the record. */
    override suspend fun record(r: DayStepRecord) {
        Panache.withTransaction {
            rows.find("businessDate = ?1 and step = ?2", r.date, r.step.name).firstResult().flatMap { existing ->
                val e = existing ?: DayCompletionEntity().apply {
                    businessDate = r.date
                    step = r.step.name
                }
                e.status = r.status.name
                e.itemCount = r.count
                e.detail = r.detail
                e.recordedAt = checkNotNull(r.at) { "a recorded step has a time" }
                if (existing == null) rows.persist(e) else io.smallrye.mutiny.Uni.createFrom().item(e)
            }
        }.awaitSuspending()
    }

    override suspend fun day(date: LocalDate): List<DayStepRecord> = Panache.withSession {
        rows.find("businessDate", date).list()
    }.awaitSuspending().map {
        DayStepRecord(
            it.businessDate,
            DayStep.valueOf(it.step),
            DayStepStatus.valueOf(it.status),
            it.itemCount,
            it.detail,
            it.recordedAt,
        )
    }
}
