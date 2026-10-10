// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.tax.application.port.out.StatutoryReturnRepository
import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.returns.Periodicity
import com.openbank.tax.domain.returns.ReportingPeriod
import com.openbank.tax.domain.returns.ReturnStatus
import com.openbank.tax.domain.returns.StatutoryReturn
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "statutory_return")
class StatutoryReturnEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: UUID = Ids.newId()

    @Column(name = "catalogue_id", nullable = false)
    var catalogueId: String = ""

    @Column(name = "catalogue_version", nullable = false)
    var catalogueVersion: Int = 0

    @Column(name = "return_code", nullable = false)
    var returnCode: String = ""

    @Column(name = "entity_id", nullable = false)
    var entityId: String = ""

    @Column(name = "periodicity", nullable = false)
    var periodicity: String = ""

    @Column(name = "period_end", nullable = false)
    var periodEnd: LocalDate = LocalDate.EPOCH

    @Column(name = "revision", nullable = false)
    var revision: Int = 0

    @Column(name = "status", nullable = false)
    var status: String = ""

    @Column(name = "datapoints_json", nullable = false)
    var datapointsJson: String = "{}"

    @Column(name = "content_hash", nullable = false)
    var contentHash: String = ""

    @Column(name = "due_date", nullable = false)
    var dueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "assembled_by", nullable = false)
    var assembledBy: String = ""

    @Column(name = "assembled_at", nullable = false)
    var assembledAt: Instant? = null

    @Column(name = "approved_by")
    var approvedBy: String? = null

    @Column(name = "approved_at")
    var approvedAt: Instant? = null

    @Column(name = "attested_hash")
    var attestedHash: String? = null

    @Column(name = "submitted_by")
    var submittedBy: String? = null

    @Column(name = "submitted_at")
    var submittedAt: Instant? = null

    @Column(name = "submission_reference")
    var submissionReference: String? = null

    @Column(name = "version", nullable = false)
    var version: Long = 0L
}

@ApplicationScoped
class PanacheStatutoryReturnRepository(private val objectMapper: ObjectMapper) :
    StatutoryReturnRepository,
    PanacheRepositoryBase<StatutoryReturnEntity, UUID> {

    override suspend fun insert(statutoryReturn: StatutoryReturn): StatutoryReturn = Panache.withTransaction {
        persist(statutoryReturn.toEntity())
    }.awaitSuspending().toDomain()

    override suspend fun findReturn(id: UUID): StatutoryReturn? = Panache.withSession {
        find("id = ?1", id).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun latestRevision(
        catalogueId: String,
        returnCode: String,
        entityId: String,
        period: ReportingPeriod,
    ): StatutoryReturn? = Panache.withSession {
        find(
            "catalogueId = ?1 and returnCode = ?2 and entityId = ?3 and periodicity = ?4 and periodEnd = ?5 order by revision desc",
            catalogueId,
            returnCode,
            entityId,
            period.periodicity.name,
            period.endDate,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun listReturns(): List<StatutoryReturn> = Panache.withSession {
        find("order by periodEnd desc, returnCode asc, entityId asc, revision desc").list()
    }.awaitSuspending().map { it.toDomain() }

    /** Lifecycle columns only — the figures and their hash are immutable once a revision exists. */
    override suspend fun save(statutoryReturn: StatutoryReturn, expectedVersion: Long): StatutoryReturn =
        Panache.withTransaction {
            update(
                "status = ?1, approvedBy = ?2, approvedAt = ?3, attestedHash = ?4, submittedBy = ?5, " +
                    "submittedAt = ?6, submissionReference = ?7, version = ?8 where id = ?9 and version = ?10",
                statutoryReturn.status.name,
                statutoryReturn.approvedBy,
                statutoryReturn.approvedAt,
                statutoryReturn.attestedHash,
                statutoryReturn.submittedBy,
                statutoryReturn.submittedAt,
                statutoryReturn.submissionReference,
                statutoryReturn.version,
                statutoryReturn.id,
                expectedVersion,
            ).map { updated ->
                if (updated != 1) {
                    throw TaxConflictException(
                        "${statutoryReturn.returnCode} ${statutoryReturn.period.label} changed concurrently — reload and retry",
                    )
                }
                statutoryReturn
            }
        }.awaitSuspending()

    private fun StatutoryReturn.toEntity() = StatutoryReturnEntity().also {
        it.id = id
        it.catalogueId = catalogueId
        it.catalogueVersion = catalogueVersion
        it.returnCode = returnCode
        it.entityId = entityId
        it.periodicity = period.periodicity.name
        it.periodEnd = period.endDate
        it.revision = revision
        it.status = status.name
        it.datapointsJson = objectMapper.writeValueAsString(datapoints.mapValues { (_, v) -> v.toPlainString() })
        it.contentHash = contentHash
        it.dueDate = dueDate
        it.assembledBy = assembledBy
        it.assembledAt = assembledAt
        it.approvedBy = approvedBy
        it.approvedAt = approvedAt
        it.attestedHash = attestedHash
        it.submittedBy = submittedBy
        it.submittedAt = submittedAt
        it.submissionReference = submissionReference
        it.version = version
    }

    // Values are stored as strings so a JSON number round-trip cannot alter the scale or precision
    // of a figure whose hash was attested.
    private fun StatutoryReturnEntity.toDomain() = StatutoryReturn(
        id = id,
        catalogueId = catalogueId,
        catalogueVersion = catalogueVersion,
        returnCode = returnCode,
        entityId = entityId,
        period = ReportingPeriod(Periodicity.valueOf(periodicity), periodEnd),
        revision = revision,
        status = ReturnStatus.valueOf(status),
        datapoints = objectMapper.readValue(datapointsJson, STRING_MAP).mapValues { (_, v) ->
            BigDecimal(v)
        }.toSortedMap(),
        contentHash = contentHash,
        dueDate = dueDate,
        assembledBy = assembledBy,
        assembledAt = requireNotNull(assembledAt) { "statutory_return $id has no assembled_at" },
        approvedBy = approvedBy,
        approvedAt = approvedAt,
        attestedHash = attestedHash,
        submittedBy = submittedBy,
        submittedAt = submittedAt,
        submissionReference = submissionReference,
        version = version,
    )

    private companion object {
        val STRING_MAP = object : TypeReference<Map<String, String>>() {}
    }
}
