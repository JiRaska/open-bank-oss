// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.`in`.ProposeReferenceCommand
import com.openbank.treasury.application.port.`in`.ReferenceDataUseCase
import com.openbank.treasury.application.port.`in`.ReferenceDependant
import com.openbank.treasury.application.port.`in`.ReferenceHistory
import com.openbank.treasury.application.port.out.DealReferenceLookup
import com.openbank.treasury.application.port.out.ReferenceCommandKey
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.application.port.out.ReferenceWrite
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceNotEffectiveException
import com.openbank.treasury.domain.reference.ReferenceNotFoundException
import com.openbank.treasury.domain.reference.ReferenceRecord
import com.openbank.treasury.domain.reference.ReferenceRef
import com.openbank.treasury.domain.reference.ReferenceStatus
import com.openbank.treasury.domain.reference.ReferenceSupersession
import java.time.Clock
import java.time.LocalDate

/**
 * Reference-data stewardship (ADR-0344 D3). Every rule about who may do what lives on
 * [ReferenceRecord]; this class loads, applies, and stores the result in one transaction, with the
 * prior status of every row it rewrites so a concurrent decision is refused, never overwritten.
 */
class ReferenceDataService(
    private val records: ReferenceRepository,
    private val deals: DealReferenceLookup,
    private val clock: Clock,
) : ReferenceDataUseCase {

    override suspend fun list(kind: ReferenceKind, status: ReferenceStatus?, asOf: LocalDate?) =
        records.list(kind, status, asOf)

    override suspend fun history(kind: ReferenceKind, key: String): ReferenceHistory {
        val versions = records.versions(kind, key)
        if (versions.isEmpty()) throw ReferenceNotFoundException(ReferenceRef(kind, key))
        val today = AccountingClock.bank(clock).today()
        return ReferenceHistory(kind, key, versions.lastOrNull { it.isEffectiveOn(today) }, versions)
    }

    override suspend fun propose(command: ProposeReferenceCommand, actor: Actor, key: String): ReferenceRecord {
        replay(key, PROPOSE, command.kind, command.key, null)?.let { return it }
        val next = (records.versions(command.kind, command.key).maxOfOrNull { it.version } ?: 0) + 1
        val record = ReferenceRecord.propose(
            kind = command.kind,
            key = command.key,
            version = next,
            validFrom = command.validFrom,
            validTo = command.validTo,
            payload = command.payload,
            reason = command.reason,
            actor = actor,
            at = clock.instant(),
        )
        records.save(
            listOf(ReferenceWrite(record, null)),
            ReferenceCommandKey(key, PROPOSE, record.kind, record.key, next),
        )
        return record
    }

    override suspend fun approve(
        kind: ReferenceKind,
        recordKey: String,
        version: Int,
        actor: Actor,
        key: String,
    ): ReferenceRecord {
        replay(key, APPROVE, kind, recordKey, version)?.let { return it }
        val versions = records.versions(kind, recordKey)
        val pending = versions.find(kind, recordKey, version)
        val approved = pending.approve(actor, clock.instant())
        val displaced = ReferenceSupersession.resolve(approved, versions.filter { it.status == ReferenceStatus.ACTIVE })
        val writes = listOf(ReferenceWrite(approved, ReferenceStatus.PENDING)) +
            displaced.map { ReferenceWrite(it, ReferenceStatus.ACTIVE) }
        records.save(writes, ReferenceCommandKey(key, APPROVE, kind, recordKey, version))
        return approved
    }

    override suspend fun reject(
        kind: ReferenceKind,
        recordKey: String,
        version: Int,
        reason: String,
        actor: Actor,
        key: String,
    ): ReferenceRecord {
        replay(key, REJECT, kind, recordKey, version)?.let { return it }
        val rejected = records.versions(
            kind,
            recordKey,
        ).find(kind, recordKey, version).reject(actor, reason, clock.instant())
        records.save(
            listOf(ReferenceWrite(rejected, ReferenceStatus.PENDING)),
            ReferenceCommandKey(key, REJECT, kind, recordKey, version),
        )
        return rejected
    }

    override suspend fun verify(
        kind: ReferenceKind,
        recordKey: String,
        version: Int,
        actor: Actor,
        key: String,
    ): ReferenceRecord {
        replay(key, VERIFY, kind, recordKey, version)?.let { return it }
        val current = records.versions(kind, recordKey).find(kind, recordKey, version)
        val verified = current.verify(actor)
        records.save(
            listOf(ReferenceWrite(verified, current.status)),
            ReferenceCommandKey(key, VERIFY, kind, recordKey, version),
        )
        return verified
    }

    override suspend fun dependencies(kind: ReferenceKind, key: String): List<ReferenceDependant> {
        val target = ReferenceRef(kind, key)
        val fromRecords = records.live()
            .filter { r -> target in r.payload.references() }
            .map { ReferenceDependant(it.kind.wire, it.key, "${it.kind.wire}@v${it.version}") }
            .distinct()
        val fromDeals = deals.dealsReferencing(target).map { ReferenceDependant("deal", it.toString(), kind.wire) }
        return fromRecords + fromDeals
    }

    /** Throws [ReferenceNotEffectiveException] listing every ref with no ACTIVE version on [on]. */
    suspend fun requireEffective(refs: Collection<ReferenceRef>, on: LocalDate) {
        val missing = records.notEffective(refs.distinct(), on)
        if (missing.isNotEmpty()) throw ReferenceNotEffectiveException(missing, on)
    }

    private fun List<ReferenceRecord>.find(kind: ReferenceKind, recordKey: String, version: Int) =
        firstOrNull { it.version == version }
            ?: throw ReferenceNotFoundException(ReferenceRef(kind, recordKey), version)

    /** Same key + same command = replay (answer with the record as it now stands); else a client bug. */
    private suspend fun replay(
        key: String,
        action: String,
        kind: ReferenceKind,
        recordKey: String,
        version: Int?,
    ): ReferenceRecord? {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) {
            "Idempotency-Key must be 1..$MAX_KEY_LENGTH characters"
        }
        val prior = records.findCommand(key) ?: return null
        require(
            prior.action == action &&
                prior.kind == kind &&
                prior.recordKey == recordKey &&
                (version == null || prior.version == version),
        ) { "Idempotency-Key was already used for ${prior.action} on another request" }
        return records.versions(kind, recordKey).find(kind, recordKey, prior.version)
    }

    private companion object {
        const val MAX_KEY_LENGTH = 128
        const val PROPOSE = "PROPOSE"
        const val APPROVE = "APPROVE"
        const val REJECT = "REJECT"
        const val VERIFY = "VERIFY"
    }
}
