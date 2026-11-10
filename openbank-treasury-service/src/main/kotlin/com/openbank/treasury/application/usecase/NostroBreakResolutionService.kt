// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.`in`.NostroBreakResolutionUseCase
import com.openbank.treasury.application.port.`in`.NostroBreakView
import com.openbank.treasury.application.port.out.NostroAccountNotFoundException
import com.openbank.treasury.application.port.out.NostroBreakNotFoundException
import com.openbank.treasury.application.port.out.NostroBreakRepository
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.BreakAlertPolicy
import com.openbank.treasury.domain.model.NostroBreak
import com.openbank.treasury.domain.model.ResolutionStep
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/**
 * Four-eyes manual resolution of a nostro break that will never match (ADR-0315 D7, ADR-0369):
 * propose with a reason, then a DIFFERENT person approves or rejects. The rules live on
 * [NostroBreak]; this class loads, applies one step conditionally on the stored state, and records
 * it in the append-only history. Nothing here posts to the ledger.
 */
class NostroBreakResolutionService(
    private val breakRepo: NostroBreakRepository,
    accounts: Map<String, String>,
    private val policy: BreakAlertPolicy,
    private val clock: Clock,
) : NostroBreakResolutionUseCase {

    private val ibans: Set<String> = accounts.keys.map { normalize(it) }.toSet()

    override suspend fun proposeResolution(
        iban: String,
        breakId: UUID,
        reason: String,
        actor: Actor,
        idempotencyKey: String,
    ): NostroBreakView {
        val text = NostroBreak.normalizeReason(reason)
        return step(iban, breakId, ResolutionStep.PROPOSED, actor, text, idempotencyKey) {
            it.proposeResolution(text, actor, Instant.now(clock).truncatedTo(ChronoUnit.MICROS))
        }
    }

    override suspend fun approveResolution(
        iban: String,
        breakId: UUID,
        proposalRead: Instant,
        actor: Actor,
        idempotencyKey: String,
    ): NostroBreakView =
        step(iban, breakId, ResolutionStep.APPROVED, actor, null, idempotencyKey, "proposal@$proposalRead") {
            it.approveResolution(actor, bankToday(), proposalRead)
        }

    override suspend fun rejectResolution(
        iban: String,
        breakId: UUID,
        reason: String,
        actor: Actor,
        idempotencyKey: String,
    ): NostroBreakView {
        val text = NostroBreak.normalizeReason(reason)
        return step(iban, breakId, ResolutionStep.REJECTED, actor, text, idempotencyKey) {
            it.rejectResolution(text, actor)
        }
    }

    /** One four-eyes step: replay check, load (404), domain transition, conditional store + history. */
    private suspend fun step(
        iban: String,
        breakId: UUID,
        step: ResolutionStep,
        actor: Actor,
        reason: String?,
        idempotencyKey: String,
        discriminator: String? = null,
        transition: (NostroBreak) -> NostroBreak,
    ): NostroBreakView {
        val account = normalize(iban)
        if (account !in ibans) throw NostroAccountNotFoundException(account)
        val fingerprint = fingerprint(account, breakId, step, reason ?: discriminator, actor)
        replayed(idempotencyKey, fingerprint)?.let { return it }
        val stored =
            breakRepo.find(breakId)?.takeIf { it.iban == account } ?: throw NostroBreakNotFoundException(breakId)
        val after = transition(stored)
        val written = breakRepo.applyResolutionStep(
            stored,
            after,
            step,
            actor.id,
            reason,
            Instant.now(clock),
            idempotencyKey,
            fingerprint,
        )
        if (!written) {
            // The row moved between the read and the conditional UPDATE: the same request (same key)
            // answers its outcome; anything else is a conflict with the new state.
            replayed(idempotencyKey, fingerprint)?.let { return it }
            error("nostro break $breakId changed concurrently; reload and retry")
        }
        return view(after.copy(version = stored.version + 1), bankToday()) // as now stored
    }

    /** The stored outcome of a replay; the key reused for a DIFFERENT request is a 400, never a second resolution. */
    private suspend fun replayed(key: String, fingerprint: String): NostroBreakView? {
        val recorded = breakRepo.recordedResolution(key) ?: return null
        require(recorded.fingerprint == fingerprint) { "Idempotency-Key already used for a different request" }
        val brk = checkNotNull(breakRepo.find(recorded.breakId)) { "resolved break ${recorded.breakId} vanished" }
        return view(brk, bankToday())
    }

    private fun view(b: NostroBreak, today: LocalDate) =
        NostroBreakView(b, b.ageBusinessDays(today), policy.isAged(b, today))

    private fun fingerprint(account: String, breakId: UUID, step: ResolutionStep, reason: String?, actor: Actor) =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest("$account|$breakId|$step|${actor.id}|$reason".toByteArray()),
        )

    /** The Prague bank day, as the sweep and the listing use it. */
    private fun bankToday(): LocalDate = AccountingClock.bank(clock).today()

    private fun normalize(iban: String) = iban.replace(" ", "").uppercase()
}
