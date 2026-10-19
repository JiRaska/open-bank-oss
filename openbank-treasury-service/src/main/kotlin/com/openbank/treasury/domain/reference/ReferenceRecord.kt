// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.reference

import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorNotPermittedException
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.FourEyesViolationException
import java.time.Instant
import java.time.LocalDate

enum class ReferenceStatus { PENDING, ACTIVE, SUPERSEDED, REJECTED }

/**
 * One version of one reference record (ADR-0344 D3): effective-dated over `[validFrom, validTo)`,
 * proposed by one principal and approved by a DIFFERENT human. Four-eyes is enforced HERE, not only
 * in OPA, for the same reason as on a deal: a policy that is not enforcing would otherwise let one
 * person both write and activate the data every deal is priced and paid from.
 *
 * - An AI agent may PROPOSE (a draft for a person to review) but never approve, reject or verify.
 * - `verified` is provenance (#11107): the content was checked against its source. An unverified
 *   record is usable but must never be silently trusted; verifying is another person's act too.
 * - A record seeded by a migration is ACTIVE with a SYSTEM creator and no approver: it is not
 *   four-eyed, and it is seeded `verified = false` wherever its content is not citable, so a person
 *   still has to look at it.
 */
data class ReferenceRecord(
    val kind: ReferenceKind,
    val key: String,
    val version: Int,
    val status: ReferenceStatus,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val verified: Boolean,
    val payload: ReferencePayload,
    val createdBy: Actor,
    val createdAt: Instant,
    val reason: String?,
    val approvedBy: Actor? = null,
    val approvedAt: Instant? = null,
    val verifiedBy: Actor? = null,
    val decisionReason: String? = null,
) {
    init {
        require(version >= 1) { "version starts at 1" }
        require(payload.recordKind == kind) { "a ${payload.recordKind.wire} payload cannot be a ${kind.wire} record" }
        require(payload.key() == key) { "the key '$key' does not match the payload's key '${payload.key()}'" }
        require(validTo == null || validTo.isAfter(validFrom)) { "validTo must be after validFrom" }
    }

    val ref: ReferenceRef get() = ReferenceRef(kind, key)

    /** Effective on [date]: ACTIVE and `validFrom <= date < validTo`. */
    fun isEffectiveOn(date: LocalDate): Boolean =
        status == ReferenceStatus.ACTIVE && !date.isBefore(validFrom) && (validTo == null || date.isBefore(validTo))

    /** True when the two half-open ranges intersect. */
    fun overlaps(other: ReferenceRecord): Boolean = (other.validTo == null || validFrom.isBefore(other.validTo)) &&
        (validTo == null || other.validFrom.isBefore(validTo))

    /** Four-eyes activation. The previous ACTIVE versions are resolved by [ReferenceSupersession]. */
    fun approve(actor: Actor, at: Instant): ReferenceRecord {
        requireHuman(actor, "approve")
        requireStatus(ReferenceStatus.PENDING, "approve")
        requireNotProposer(actor, "approve")
        return copy(status = ReferenceStatus.ACTIVE, approvedBy = actor, approvedAt = at)
    }

    fun reject(actor: Actor, reason: String, at: Instant): ReferenceRecord {
        requireHuman(actor, "reject")
        requireStatus(ReferenceStatus.PENDING, "reject")
        require(reason.isNotBlank()) { "a rejection needs a reason" }
        requireNotProposer(actor, "reject")
        return copy(status = ReferenceStatus.REJECTED, approvedBy = actor, approvedAt = at, decisionReason = reason)
    }

    /**
     * Attest the content against its source. Only a PENDING or ACTIVE version; never by its
     * proposer and never by an AI agent.
     */
    fun verify(actor: Actor): ReferenceRecord {
        requireHuman(actor, "verify")
        check(status == ReferenceStatus.PENDING || status == ReferenceStatus.ACTIVE) {
            "cannot verify a $status reference record"
        }
        check(!verified) { "the record is already verified" }
        requireNotProposer(actor, "verify")
        return copy(verified = true, verifiedBy = actor)
    }

    private fun requireHuman(actor: Actor, action: String) {
        if (actor.type != ActorType.HUMAN) {
            throw ActorNotPermittedException("a ${actor.type} principal may not $action reference data")
        }
    }

    private fun requireStatus(expected: ReferenceStatus, action: String) =
        check(status == expected) { "cannot $action a $status reference record (expected $expected)" }

    private fun requireNotProposer(actor: Actor, action: String) {
        if (actor.id == createdBy.id) {
            throw FourEyesViolationException("four-eyes: the proposer of a reference record must not $action it")
        }
    }

    companion object {
        private const val MAX_REASON = 1000

        /**
         * A new PENDING version. A human or an AI agent may propose; a service account or the
         * system may not (seeds are written by migrations, not through this door).
         */
        @Suppress("LongParameterList")
        fun propose(
            kind: ReferenceKind,
            key: String,
            version: Int,
            validFrom: LocalDate,
            validTo: LocalDate?,
            payload: ReferencePayload,
            reason: String,
            actor: Actor,
            at: Instant,
        ): ReferenceRecord {
            if (actor.type != ActorType.HUMAN && actor.type != ActorType.AI_AGENT) {
                throw ActorNotPermittedException("a ${actor.type} principal may not propose reference data")
            }
            require(reason.isNotBlank() && reason.length <= MAX_REASON) { "a proposal needs a reason (1..$MAX_REASON)" }
            payload.validate()
            return ReferenceRecord(
                kind = kind,
                key = key,
                version = version,
                status = ReferenceStatus.PENDING,
                validFrom = validFrom,
                validTo = validTo,
                verified = false,
                payload = payload,
                createdBy = actor,
                createdAt = at,
                reason = reason,
            )
        }
    }
}

/**
 * What approving [approved] does to the other ACTIVE versions of the same key. An earlier version
 * that overlaps is closed at the new `validFrom`; one that starts no earlier and lies inside the new
 * range is SUPERSEDED. A new BOUNDED range that falls inside an older open one would need the old
 * version split in two — refused (409), because silently dropping the old tail would rewrite what
 * is effective after the new range ends.
 */
object ReferenceSupersession {
    fun resolve(approved: ReferenceRecord, actives: List<ReferenceRecord>): List<ReferenceRecord> =
        actives.filter { it.ref == approved.ref && it.version != approved.version && it.overlaps(approved) }
            .map { old ->
                val endsAfterNew =
                    approved.validTo != null && (old.validTo == null || old.validTo.isAfter(approved.validTo))
                check(!endsAfterNew) {
                    "version ${approved.version} [${approved.validFrom}, ${approved.validTo}) lies inside version " +
                        "${old.version}, which would have to be split; propose an open-ended version or end ${old.version} first"
                }
                if (old.validFrom.isBefore(approved.validFrom)) {
                    old.copy(validTo = approved.validFrom)
                } else {
                    old.copy(status = ReferenceStatus.SUPERSEDED)
                }
            }
}

/** A deal names reference data that is not ACTIVE on its trade date. Mapped to 422. */
class ReferenceNotEffectiveException(val missing: List<ReferenceRef>, val on: LocalDate) :
    RuntimeException("reference data not effective on $on: ${missing.joinToString()}")

/** No version of the named record. Mapped to 404. */
class ReferenceNotFoundException(ref: ReferenceRef, version: Int? = null) :
    NoSuchElementException("reference record $ref${version?.let { " version $it" } ?: ""} not found")
