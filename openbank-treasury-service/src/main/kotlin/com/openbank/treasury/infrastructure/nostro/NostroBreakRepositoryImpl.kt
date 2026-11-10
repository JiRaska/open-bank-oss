// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.nostro

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.treasury.application.port.out.NostroBreakRepository
import com.openbank.treasury.application.port.out.RecordedBreakResolution
import com.openbank.treasury.application.port.out.TreasuryOutboxRepository
import com.openbank.treasury.domain.model.BreakChanges
import com.openbank.treasury.domain.model.BreakResolutionKind
import com.openbank.treasury.domain.model.BreakSide
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.ResolutionStep
import com.openbank.treasury.domain.model.Side
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * `nostro_breaks` (V14, manual resolution V33). A resolution change updates the LOADED row inside the transaction, never
 * `persist` of a new entity: the break key is unique, and an INSERT of a stored key fails at flush.
 */
@ApplicationScoped
class NostroBreakRepositoryImpl(private val outbox: TreasuryOutboxRepository) :
    NostroBreakRepository,
    PanacheRepository<NostroBreakEntity> {

    private val log: Logger = Logger.getLogger(NostroBreakRepositoryImpl::class.java)

    override suspend fun inScope(
        glCode: String,
        statementUuid: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<NostroBreak> = Panache.withSession {
        list(
            "glCode = ?1 and (statementUuid = ?2 or (side = ?3 and bookingDate >= ?4 and bookingDate <= ?5))",
            glCode,
            statementUuid,
            BreakSide.LEDGER.name,
            from,
            to,
        )
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun apply(changes: BreakChanges) {
        val updates = (changes.resolved + changes.reopened).associateBy { it.id }
        Panache.withTransaction {
            val inserted: Uni<Void> =
                if (changes.opened.isEmpty()) {
                    Uni.createFrom().voidItem()
                } else {
                    persist(changes.opened.map { b -> NostroBreakEntity().also { it.fill(b) } })
                }
            // Every write to an existing break is a compare-and-set on the VERSION the change was
            // computed from: an approval, proposal or rejection that committed after this sweep read
            // the row has moved it, so the sweep's stale copy is skipped (0 rows) instead of
            // overwriting it; the next pass recomputes from the new state. Never an exception — one
            // lost race must not abort the sweep.
            inserted.flatMap {
                Panache.getSession().flatMap { s ->
                    updates.values.fold(Uni.createFrom().item(0)) { acc, b ->
                        acc.flatMap { lost ->
                            casUpdate(s, b, null, b.version).map { n ->
                                if (n ==
                                    1
                                ) {
                                    lost
                                } else {
                                    lost + 1
                                }
                            }
                        }
                    }
                }
            }
        }.awaitSuspending().let { lost ->
            if (lost >
                0
            ) {
                log.infof("nostro break sweep: %d break(s) changed concurrently, left for the next pass", lost)
            }
        }
    }

    override suspend fun byIban(iban: String, includeResolved: Boolean): List<NostroBreak> = Panache.withSession {
        if (includeResolved) list("iban", iban) else list("iban = ?1 and resolvedOn is null", iban)
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun open(): List<NostroBreak> =
        Panache.withSession { list("resolvedOn is null") }.awaitSuspending().map { it.toDomain() }

    override suspend fun find(breakId: UUID): NostroBreak? =
        Panache.withSession { find("breakUuid", breakId).firstResult() }.awaitSuspending()?.toDomain()

    /**
     * The conditional UPDATE is the claim: it matches only while the row is still open with the
     * proposer [before] had, so of two concurrent steps exactly one updates the row and records
     * itself; the other writes nothing. The history row is written in the SAME transaction. A
     * [commandKey] already recorded by another request fails the whole transaction on its unique
     * constraint — answered as "not written", and the caller's replay lookup decides.
     */
    override suspend fun applyResolutionStep(
        before: NostroBreak,
        after: NostroBreak,
        step: ResolutionStep,
        actor: String,
        reason: String?,
        at: Instant,
        commandKey: String,
        fingerprint: String,
    ): Boolean = try {
        Panache.withTransaction {
            Panache.getSession().flatMap { s ->
                casUpdate(s, after, at, before.version).flatMap { n ->
                    if (n != 1) {
                        Uni.createFrom().item(false)
                    } else {
                        native(
                            s,
                            HISTORY_INSERT,
                            before.id,
                            step.name,
                            at.atOffset(ZoneOffset.UTC),
                            actor,
                            reason,
                            commandKey,
                            fingerprint,
                        ).replaceWith(true)
                    }
                }
            }
        }.awaitSuspending()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception, // classified below; anything else rethrown
    ) {
        if (mentions(e, COMMAND_KEY_UNIQUE)) false else throw e
    }

    override suspend fun recordedResolution(commandKey: String): RecordedBreakResolution? = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createNativeQuery<Array<Any?>>(
                "SELECT break_uuid, request_fingerprint FROM nostro_break_resolution_history WHERE command_key = ?1",
            ).setParameter(1, commandKey).resultList
        }
    }.awaitSuspending().firstOrNull()?.let { RecordedBreakResolution(it[0] as UUID, (it[1] as String).trim()) }

    /**
     * The conditional UPDATE is the claim: of two pods sweeping at once (an Argo canary runs both),
     * exactly one sees one row updated and writes the event; the other writes nothing.
     */
    override suspend fun markAlerted(breakId: UUID, alertedAt: Instant, eventType: String, payload: String): Boolean =
        Panache.withTransaction {
            update("alertedAt = ?1 where breakUuid = ?2 and alertedAt is null", alertedAt, breakId).flatMap { n ->
                if (n != 1) {
                    Uni.createFrom().item(false)
                } else {
                    outbox.persistInTransaction(
                        OutboxMessage(
                            aggregateId = breakId,
                            eventType = eventType,
                            payload = payload,
                            createdAt = alertedAt,
                        ),
                    ).replaceWith(true)
                }
            }
        }.awaitSuspending()

    private fun NostroBreakEntity.fill(b: NostroBreak) {
        breakUuid = b.id
        breakKey = b.breakKey
        iban = b.iban
        glCode = b.glCode
        currency = b.currency
        side = b.side.name
        ourSide = b.ourSide.name
        amount = b.amount
        bookingDate = b.bookingDate
        reference = b.reference
        statementUuid = b.statementUuid
        statementSequence = b.statementSequence
        ledgerLineId = b.ledgerLineId
        firstSeenOn = b.firstSeenOn
        resolvedOn = b.resolvedOn
        alertedAt = b.alertedAt
        resolutionKind = b.resolutionKind?.name
        resolutionReason = b.resolutionReason
        resolvedBy = b.resolvedBy
        proposedBy = b.proposedBy
        proposedAt = b.proposedAt
    }

    private fun NostroBreakEntity.toDomain() = NostroBreak(
        id = breakUuid,
        breakKey = breakKey,
        iban = iban,
        glCode = glCode,
        currency = currency,
        side = BreakSide.valueOf(side),
        ourSide = Side.valueOf(ourSide),
        amount = amount,
        bookingDate = bookingDate,
        reference = reference,
        statementUuid = statementUuid,
        statementSequence = statementSequence,
        ledgerLineId = ledgerLineId,
        firstSeenOn = firstSeenOn,
        resolvedOn = resolvedOn,
        alertedAt = alertedAt,
        // A row resolved before V33 that escaped the backfill can only have been matched.
        resolutionKind = resolutionKind?.let { BreakResolutionKind.valueOf(it) }
            ?: resolvedOn?.let { BreakResolutionKind.MATCHED },
        resolutionReason = resolutionReason,
        resolvedBy = resolvedBy,
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        version = version,
    )

    private companion object {
        const val COMMAND_KEY_UNIQUE = "uq_nostro_break_resolution_history_command_key"
        const val HISTORY_INSERT =
            "INSERT INTO nostro_break_resolution_history " +
                "(break_uuid, step, at, actor_id, reason, command_key, request_fingerprint) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)"
    }
}

/** A failure anywhere in [e]'s cause chain (or its suppressed members) naming [constraint]. */
private fun mentions(e: Throwable, constraint: String): Boolean =
    generateSequence(e) { it.cause }.take(MAX_CAUSES).any { t ->
        t.message?.contains(constraint) == true || t.suppressed.any { it.message?.contains(constraint) == true }
    }

private const val MAX_CAUSES = 16

/** Native statement with positional parameters; a null is bound typed by Hibernate Reactive as NULL. */
private fun native(s: Mutiny.Session, sql: String, vararg params: Any?): Uni<Int> =
    params.foldIndexed(s.createNativeQuery<Int>(sql)) { i, q, v -> q.setParameter(i + 1, v) }.executeUpdate()

/**
 * Writes [b]'s resolution and proposal state if the stored row is still at [expectedVersion].
 * [approvedAt] stamps `resolved_at` for a manual approval; any other state clears it.
 */
private fun casUpdate(s: Mutiny.Session, b: NostroBreak, approvedAt: Instant?, expectedVersion: Long): Uni<Int> =
    native(
        s,
        "UPDATE nostro_breaks SET resolved_on = ?1, resolution_kind = ?2, resolution_reason = ?3, " +
            "resolved_by = ?4, resolved_at = ?5, proposed_by = ?6, proposed_at = ?7, version = version + 1 " +
            "WHERE break_uuid = ?8 AND version = ?9",
        b.resolvedOn,
        b.resolutionKind?.name,
        b.resolutionReason,
        b.resolvedBy,
        if (b.resolvedBy != null) approvedAt?.atOffset(ZoneOffset.UTC) else null,
        b.proposedBy,
        b.proposedAt?.atOffset(ZoneOffset.UTC),
        b.id,
        expectedVersion,
    )
