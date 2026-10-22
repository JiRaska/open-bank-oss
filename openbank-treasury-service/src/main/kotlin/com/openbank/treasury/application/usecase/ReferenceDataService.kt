// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.`in`.ProposeReferenceCommand
import com.openbank.treasury.application.port.`in`.ReferenceActivity
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
@Suppress("TooManyFunctions") // one use case per steward operation, plus its load/replay helpers
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
        val today = AccountingClock.bank(clock).today()
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
            today = today,
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
        seniorApprover: Boolean,
    ): ReferenceRecord {
        replay(key, APPROVE, kind, recordKey, version)?.let { return it }
        val today = AccountingClock.bank(clock).today()
        val at = clock.instant()
        return records.decide(
            kind,
            recordKey,
            ReferenceCommandKey(key, APPROVE, kind, recordKey, version),
        ) { versions ->
            val pending = versions.find(kind, recordKey, version)
            val effectiveNow = versions.lastOrNull { it.isEffectiveOn(today) }
            val approved = pending.approve(actor, at, today, seniorApprover, effectiveNow)
            val displaced = ReferenceSupersession.resolve(
                approved,
                versions.filter {
                    it.status ==
                        ReferenceStatus.ACTIVE
                },
            )
            listOf(ReferenceWrite(approved, ReferenceStatus.PENDING)) +
                displaced.map { ReferenceWrite(it, ReferenceStatus.ACTIVE) }
        }.first { it.version == version }
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
        val at = clock.instant()
        return records.decide(kind, recordKey, ReferenceCommandKey(key, REJECT, kind, recordKey, version)) { versions ->
            listOf(
                ReferenceWrite(
                    versions.find(kind, recordKey, version).reject(actor, reason, at),
                    ReferenceStatus.PENDING,
                ),
            )
        }.single()
    }

    override suspend fun verify(
        kind: ReferenceKind,
        recordKey: String,
        version: Int,
        actor: Actor,
        key: String,
    ): ReferenceRecord {
        replay(key, VERIFY, kind, recordKey, version)?.let { return it }
        return records.decide(kind, recordKey, ReferenceCommandKey(key, VERIFY, kind, recordKey, version)) { versions ->
            val current = versions.find(kind, recordKey, version)
            listOf(ReferenceWrite(current.verify(actor), current.status))
        }.single()
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

    override suspend fun importProposals(
        kind: ReferenceKind,
        commands: List<ProposeReferenceCommand>,
        actor: Actor,
        key: String,
    ): List<ReferenceRecord> {
        require(commands.isNotEmpty() && commands.size <= MAX_IMPORT) { "an import carries 1..$MAX_IMPORT items" }
        records.findCommand(key)?.let { prior ->
            require(prior.action == IMPORT && prior.kind == kind) {
                "Idempotency-Key was already used for ${prior.action} on another request"
            }
            // Replay: every record of the import shares its proposer and timestamp.
            val first = records.versions(kind, prior.recordKey).first { it.version == prior.version }
            return records.list(kind, null, null).filter {
                it.createdBy == first.createdBy &&
                    it.createdAt == first.createdAt
            }
        }
        val today = AccountingClock.bank(clock).today()
        val at = clock.instant()
        val next = mutableMapOf<String, Int>()
        val proposed = commands.mapIndexed { i, c ->
            require(c.kind == kind) { "item $i is a ${c.kind.wire}, not a ${kind.wire}" }
            val version = (next[c.key] ?: (records.versions(kind, c.key).maxOfOrNull { it.version } ?: 0)) + 1
            next[c.key] = version
            try {
                ReferenceRecord.propose(
                    kind, c.key, version, c.validFrom, c.validTo, c.payload, c.reason, actor, at, today,
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("item $i: ${e.message}", e)
            }
        }
        val head = proposed.first()
        records.save(
            proposed.map {
                ReferenceWrite(it, null)
            },
            ReferenceCommandKey(key, IMPORT, kind, head.key, head.version),
        )
        return proposed
    }

    override suspend fun activity(since: java.time.Instant?): List<ReferenceActivity> =
        ReferenceKind.entries.flatMap { k -> records.list(k, null, null) }.flatMap { r ->
            buildList {
                add(ReferenceActivity(r.createdAt, r.createdBy.id, PROPOSE, r.kind.wire, r.key, r.version))
                val decided = r.approvedAt
                if (decided != null && r.approvedBy != null) {
                    val action = if (r.status == ReferenceStatus.REJECTED) REJECT else APPROVE
                    add(ReferenceActivity(decided, r.approvedBy.id, action, r.kind.wire, r.key, r.version))
                }
                r.verifiedBy?.let {
                    add(ReferenceActivity(decided ?: r.createdAt, it.id, VERIFY, r.kind.wire, r.key, r.version))
                }
            }
        }.filter { since == null || !it.at.isBefore(since) }.sortedByDescending { it.at }

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
        const val IMPORT = "IMPORT"
        const val MAX_IMPORT = 500
    }
}
