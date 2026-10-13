// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.usecase

import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.idempotency.RequestFingerprint
import com.openbank.pension.application.ProviderBoundary
import com.openbank.pension.application.port.out.ParticipantSnapshot
import com.openbank.pension.application.port.out.ParticipantSnapshotRepository
import com.openbank.pension.application.port.out.ParticipantSnapshotSerializer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Aggregate-only technical source capture. It does not approve a statutory filing. */
class ParticipantSnapshotService(
    private val reporting: ParticipantReportingService,
    private val snapshots: ParticipantSnapshotRepository,
    private val serializer: ParticipantSnapshotSerializer,
    private val boundary: ProviderBoundary,
    private val clock: Clock,
) {
    suspend fun capture(
        verifiedActor: String,
        key: String,
        companyId: UUID,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): ParticipantSnapshot {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "invalid Idempotency-Key" }
        boundary.requireProvider(companyId)
        require(!periodEnd.isBefore(periodStart)) { "periodEnd must not be before periodStart" }
        require(periodEnd.isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            "only ended UTC reporting periods can be captured"
        }
        val requestHash = RequestFingerprint.of("POST", SNAPSHOT_PATH, "$companyId:$periodStart:$periodEnd")
        val scopeHash = sha256("$verifiedActor\nparticipant-snapshot.capture\n$key")
        snapshots.receipt(scopeHash, requestHash)?.let { return it }
        val payload = serializer.canonicalJson(reporting.aggregates(companyId, periodStart, periodEnd))
        return snapshots.capture(
            scopeHash,
            requestHash,
            companyId,
            periodStart,
            periodEnd,
            SCHEMA_VERSION,
            payload,
            sha256(payload),
        )
    }

    suspend fun byId(snapshotId: UUID, companyId: UUID): ParticipantSnapshot? {
        if (companyId != boundary.providerEntityId) return null
        val saved = snapshots.byId(snapshotId, companyId) ?: return null
        if (saved.payloadSchemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            throw ResourceConflictException("unsupported participant snapshot payload schema")
        }
        check(sha256(saved.payloadJson) == saved.sha256) { "stored participant aggregate digest mismatch" }
        return saved
    }

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_KEY_LENGTH = 256 // Existing pension POST key policy.
        private const val SNAPSHOT_PATH = "/api/v2/pension/reporting/participant-snapshots"
        private const val SCHEMA_VERSION = "participant-period-aggregates/2.0"

        // Keep every previously published schema when the capture payload evolves.
        private val SUPPORTED_SCHEMA_VERSIONS = setOf(SCHEMA_VERSION)
    }
}
