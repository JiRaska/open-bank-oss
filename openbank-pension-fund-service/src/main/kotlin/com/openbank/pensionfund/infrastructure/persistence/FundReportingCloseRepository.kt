// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.persistence

import com.openbank.libs.domain.error.ResourceConflictException
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

const val FUND_REPORTING_PAYLOAD_SCHEMA_VERSION = "1.3.0"

@Entity
@Table(name = "fund_reporting_closes")
class FundReportingCloseEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", nullable = false)
    lateinit var id: UUID

    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    @Column(name = "fund_id", nullable = false)
    lateinit var fundId: UUID

    @Column(name = "period_start", nullable = false)
    lateinit var periodStart: LocalDate

    @Column(name = "period_end", nullable = false)
    lateinit var periodEnd: LocalDate

    @Column(name = "closing_nav_id", nullable = false)
    lateinit var closingNavId: UUID

    @Column(name = "source_fingerprint", nullable = false)
    lateinit var sourceFingerprint: String

    @Column(name = "payload_schema_version", nullable = false)
    lateinit var payloadSchemaVersion: String

    @Column(name = "payload_json", nullable = false)
    lateinit var payloadJson: String

    @Column(name = "payload_sha256", nullable = false)
    lateinit var payloadSha256: String

    @Column(name = "created_by", nullable = false)
    lateinit var createdBy: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

data class FundReportingClose(
    val id: UUID,
    val revision: Long,
    val fundId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val closingNavId: UUID,
    val sourceFingerprint: String,
    val payloadSchemaVersion: String,
    val payloadJson: String,
    val payloadSha256: String,
    val createdBy: String,
    val createdAt: Instant,
)

data class FundReportingCloseReceipt(val requestSha256: String, val close: FundReportingClose)

data class FundReportingSealCommand(
    val fundId: UUID,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val closingNavId: UUID,
    val sourceFingerprint: String,
    val payloadSchemaVersion: String,
    val payloadJson: String,
    val actor: String,
    val at: Instant,
    val idempotencyKey: String? = null,
    val requestSha256: String? = null,
)

/** Stored source bytes no longer match their evidence digest; never serve them as a close. */
class FundReportingCloseIntegrityException(id: UUID) :
    RuntimeException("reporting close $id failed integrity verification")

/** Insert-only source evidence: a retry of one scoped fingerprint returns its original close. */
@ApplicationScoped
class FundReportingCloseRepository : PanacheRepositoryBase<FundReportingCloseEntity, UUID> {
    // Keep the INSERT, source-identity check and command receipt in one reactive DB transaction.
    @Suppress("LongMethod", "DestructuringDeclarationWithTooManyEntries")
    suspend fun seal(command: FundReportingSealCommand): FundReportingClose {
        val (
            fundId, periodStart, periodEnd, closingNavId, sourceFingerprint, payloadSchemaVersion,
            payloadJson, actor, at, idempotencyKey, requestSha256,
        ) = command
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}"))) { "source fingerprint must be SHA-256" }
        require(payloadSchemaVersion.isNotBlank()) { "payload schema version is required" }
        require((idempotencyKey == null) == (requestSha256 == null)) { "command receipt must be complete" }
        val digest = sha256(payloadJson)
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Int>(
                    "INSERT INTO fund_reporting_closes " +
                        "(id, fund_id, period_start, period_end, closing_nav_id, source_fingerprint, " +
                        "payload_schema_version, payload_json, payload_sha256, created_by, created_at) " +
                        "VALUES (:id, :fund, :start, :end, :nav, :fingerprint, :schema, :payload, " +
                        ":digest, :actor, :at) " +
                        "ON CONFLICT (fund_id, period_start, period_end, source_fingerprint, payload_schema_version) DO NOTHING",
                )
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("fund", fundId)
                    .setParameter("start", periodStart)
                    .setParameter("end", periodEnd)
                    .setParameter("nav", closingNavId)
                    .setParameter("fingerprint", sourceFingerprint)
                    .setParameter("schema", payloadSchemaVersion)
                    .setParameter("payload", payloadJson)
                    .setParameter("digest", digest)
                    .setParameter("actor", actor)
                    .setParameter("at", at)
                    .executeUpdate()
                    .flatMap {
                        find(
                            "fundId = ?1 and periodStart = ?2 and periodEnd = ?3 and sourceFingerprint = ?4 " +
                                "and payloadSchemaVersion = ?5",
                            fundId,
                            periodStart,
                            periodEnd,
                            sourceFingerprint,
                            payloadSchemaVersion,
                        ).firstResult()
                    }
                    .flatMap { row ->
                        val saved = checkNotNull(row) { "inserted reporting close is missing" }.checked()
                        if (saved.payloadSha256 != digest || saved.closingNavId != closingNavId) {
                            throw ResourceConflictException("source fingerprint reused for different close content")
                        }
                        if (idempotencyKey == null) {
                            Uni.createFrom().item(saved)
                        } else {
                            session.createNativeQuery<Int>(
                                "INSERT INTO fund_reporting_close_receipts " +
                                    "(fund_id, actor, idempotency_key, request_sha256, close_id, created_at) " +
                                    "VALUES (:fund, :actor, :key, :request, :close, :at)",
                            )
                                .setParameter("fund", fundId)
                                .setParameter("actor", actor)
                                .setParameter("key", idempotencyKey)
                                .setParameter("request", requestSha256)
                                .setParameter("close", saved.id)
                                .setParameter("at", at)
                                .executeUpdate().replaceWith(saved)
                        }
                    }
            }
        }.awaitSuspending()
    }

    suspend fun byCommand(actor: String, key: String): FundReportingCloseReceipt? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<String>(
                "SELECT fund_id::text || ':' || close_id::text || ':' || request_sha256 " +
                    "FROM fund_reporting_close_receipts WHERE actor = :actor AND idempotency_key = :key",
            )
                .setParameter("actor", actor)
                .setParameter("key", key)
                .resultList
        }
    }.awaitSuspending().firstOrNull()?.let { serialized ->
        val (fundId, id, requestSha256) = serialized.split(':', limit = 3)
        FundReportingCloseReceipt(
            requestSha256,
            checkNotNull(byId(UUID.fromString(fundId), UUID.fromString(id))),
        )
    }

    suspend fun byId(fundId: UUID, id: UUID): FundReportingClose? = Panache.withSession {
        find("fundId = ?1 and id = ?2", fundId, id).firstResult()
    }.awaitSuspending()?.checked()

    private fun FundReportingCloseEntity.checked(): FundReportingClose {
        if (sha256(payloadJson) != payloadSha256) throw FundReportingCloseIntegrityException(id)
        return FundReportingClose(
            id, revision, fundId, periodStart, periodEnd, closingNavId, sourceFingerprint, payloadSchemaVersion,
            payloadJson, payloadSha256, createdBy, createdAt,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
