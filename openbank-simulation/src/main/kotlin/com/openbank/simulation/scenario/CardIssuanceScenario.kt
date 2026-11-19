// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.cardissuance.domain.model.Card
import com.openbank.cardissuance.domain.model.CardNetwork
import com.openbank.cardissuance.domain.model.CardStatus
import com.openbank.cardissuance.domain.model.CardType
import com.openbank.cardissuance.domain.model.DelegatedCardGrant
import com.openbank.simulation.engine.SimulatedWriteFailure
import com.openbank.simulation.runner.World
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Test Intelligence simulation gap: one step of the card-issuance lifecycle, modelled directly
 * against the **real `card-issuance-service` domain** — the `Card` status machine (activate /
 * suspend / resume / block / cancel / consume / expireUnused, every edge refused by the aggregate
 * itself), the limit and channel-control invariants, and the `DelegatedCardGrant` guard half that
 * does not depend on delegation-service (#3164 C2 / #3143 shape) — not a re-model of any of them
 * (ADR-0100). The domain package is framework-free (ADR-0002).
 *
 * A step issues one card on a random customer's account and runs a random legitimate path to a
 * settled conclusion: use it (SINGLE_USE cards consume themselves — the promise kept, not a
 * failure — ordinary cards may be re-limited and re-controlled and stay ACTIVE), let its validity
 * window expire (EXPIRED, never CANCELLED: nothing was spent), or lose it (BLOCKED, then
 * CANCELLED — and the block reason survives onto the cancelled card, because it is the answer to
 * "why is this card not usable"). An injected write fault at activation leaves the card PENDING,
 * which is a legitimate live state the completeness invariant accepts. A delegated card records
 * the grant it rests on; when that grant ends, ADR-0249 D2 requires the card to be BLOCKED, so
 * the scenario blocks it — a card that still transacts after its authority ended is the failure
 * everyone would remember.
 */
object CardIssuanceScenario {

    private const val PRODUCT_SPACE = 10
    private const val MAX_DAILY_LIMIT_MINOR = 500_000L // 5 000.00
    private const val MAX_MONTHLY_LIMIT_MINOR = 2_000_000L // 20 000.00
    private const val DELEGATE_GRANT_VALIDITY_DAYS = 30L
    private const val SINGLE_USE_MIN_VALIDITY_DAYS = 1L
    private const val SINGLE_USE_MAX_EXTRA_DAYS = 3

    // Standard cards expire at a SIMULATED scale: the lifecycle under test is the expiry sweep
    // itself, not the calendar length — a year of virtual validity would make the EXPIRE path
    // advance the whole world's clock past the other domains' time horizons (SDD's 36-month
    // idle expiry, delegation's 1-year grant validity).
    private const val STANDARD_VALIDITY_DAYS = 30L
    private const val MASKED_PAN_LAST4_BASE = 1000
    private const val MASKED_PAN_LAST4_SPACE = 9000
    private const val ISSUED_DAILY_DIVISOR = 10L
    private const val ISSUED_MONTHLY_DIVISOR = 2L
    private const val HOLDER_NAME_ID_PREFIX_LENGTH = 8
    private const val EMBOSSED_EXPIRY_YEARS = 3L
    private const val FAIR_CHANCE = 0.5

    fun step(world: World) {
        val now = world.context.clock.instant()
        val card = issueCard(world, now)
        expireDueCards(world, now)

        // The path: use it, outlive it, or lose it — each to a settled conclusion.
        when (Path.entries[world.context.random.nextInt(Path.entries.size)]) {
            Path.USE -> use(world, card, now)
            Path.EXPIRE -> expire(world, card, now)
            Path.LOSE -> lose(world, card, now)
        }
        reconcileGrants(world, now)
    }

    private fun issueCard(world: World, now: Instant): Card {
        val random = world.context.random
        val cardType = CardType.entries[random.nextInt(CardType.entries.size)]
        val accountId = world.customerAccounts[random.nextInt(world.customerAccounts.size)]
        // SINGLE_USE cards carry a near expiry so the EXPIRE path is reachable for them too; the
        // others expire far in the virtual future.
        val expiresAt = if (cardType == CardType.SINGLE_USE) {
            now.plus(
                Duration.ofDays(
                    SINGLE_USE_MIN_VALIDITY_DAYS + random.nextInt(SINGLE_USE_MAX_EXTRA_DAYS).toLong(),
                ),
            )
        } else {
            now.plus(Duration.ofDays(STANDARD_VALIDITY_DAYS))
        }
        val card = Card(
            id = random.nextUuid(),
            idempotencyKey = "card-${random.nextUuid()}",
            partyId = accountId,
            accountId = accountId,
            productCode = "CARD-${random.nextInt(PRODUCT_SPACE)}",
            cardType = cardType,
            network = CardNetwork.entries[random.nextInt(CardNetwork.entries.size)],
            maskedPan = "**** **** **** ${MASKED_PAN_LAST4_BASE + random.nextInt(MASKED_PAN_LAST4_SPACE)}",
            cardholderName = "Holder ${accountId.toString().take(HOLDER_NAME_ID_PREFIX_LENGTH)}",
            embossedName = "HOLDER",
            expiryDate = now.atZone(ZoneOffset.UTC).toLocalDate().plusYears(EMBOSSED_EXPIRY_YEARS),
            status = CardStatus.PENDING,
            dailyLimitMinorUnits = MAX_DAILY_LIMIT_MINOR / ISSUED_DAILY_DIVISOR,
            monthlyLimitMinorUnits = MAX_MONTHLY_LIMIT_MINOR / ISSUED_MONTHLY_DIVISOR,
            currency = world.currency,
            deliveryAddress = null,
            activatedAt = null,
            blockedAt = null,
            blockedReason = null,
            createdAt = now,
            updatedAt = now,
            expiresAt = expiresAt,
        )

        val activated = try {
            if (world.context.faults.shouldFailWrite()) {
                throw SimulatedWriteFailure("card ${card.id} activation failed")
            }
            card.activate(now)
        } catch (e: SimulatedWriteFailure) {
            world.cards.add(card)
            world.audit.append("card ${card.id} left PENDING: ${e.message}")
            return card
        }
        world.cards.add(activated)
        world.audit.append("card ${activated.id} activated")
        return activated
    }

    private fun use(world: World, card: Card, now: Instant) {
        val random = world.context.random
        if (card.status != CardStatus.ACTIVE) return
        if (card.cardType == CardType.SINGLE_USE) {
            val consumed = card.consume(now)
            world.cards[world.cards.lastIndex] = consumed
            world.audit.append("card ${consumed.id} consumed (single-use promise kept)")
            return
        }
        var current = card
        if (random.chance(FAIR_CHANCE)) {
            current = current.suspend(now).resume(now)
            world.audit.append("card ${current.id} suspended and resumed")
        }
        // Customer-set limits: the REAL aggregate refuses daily > monthly and negatives.
        val daily = random.nextLong(0, MAX_DAILY_LIMIT_MINOR / 2)
        current = current.withLimits(daily, daily + random.nextLong(0, MAX_MONTHLY_LIMIT_MINOR - daily), now)
        current = current.withControls(
            contactless = random.chance(FAIR_CHANCE),
            online = random.chance(FAIR_CHANCE),
            atm = random.chance(FAIR_CHANCE),
            abroad = random.chance(FAIR_CHANCE),
            now = now,
        )
        world.cards[world.cards.lastIndex] = current
        world.audit.append("card ${current.id} re-limited and re-controlled")
    }

    private fun expire(world: World, card: Card, now: Instant) {
        // Carry the virtual clock past the card's own expiry so expireUnused is the true path.
        val expiresAt = card.expiresAt ?: now.plus(Duration.ofDays(1))
        if (expiresAt.isAfter(now)) {
            world.context.clock.advance(Duration.ofMillis(expiresAt.toEpochMilli() - now.toEpochMilli() + 1))
        }
        val at = world.context.clock.instant()
        // The advance crossed EVERY card's window it passed, not just this one's — the sweep
        // after a jump must run over the whole book, the same way the production expiry job does.
        expireDueCards(world, at)
    }

    private fun lose(world: World, card: Card, now: Instant) {
        if (card.status !in setOf(CardStatus.ACTIVE, CardStatus.SUSPENDED)) return
        var current = card.block("reported lost or stolen", now)
        world.audit.append("card ${current.id} blocked")
        if (world.context.faults.shouldConflict()) {
            // The customer hangs up mid-flow: the card stays BLOCKED with its reason — a
            // legitimate settled state, not a stuck one.
            world.cards[world.cards.lastIndex] = current
            return
        }
        current = current.cancel("customer confirmed loss", now)
        world.cards[world.cards.lastIndex] = current
        world.audit.append("card ${current.id} cancelled")
    }

    /** Every live card whose validity window has run out expires — nothing spent, so EXPIRED, not CANCELLED. */
    private fun expireDueCards(world: World, now: Instant, only: UUID? = null) {
        world.cards.replaceAll { card ->
            if (only != null && card.id != only) return@replaceAll card
            val expiresAt = card.expiresAt ?: return@replaceAll card
            if (card.status in Card.LIVE_STATUSES && !expiresAt.isAfter(now)) {
                world.audit.append("card ${card.id} expired unused")
                card.expireUnused(now)
            } else {
                card
            }
        }
    }

    /**
     * ADR-0249 D2: a delegated card whose grant has ended must be BLOCKED — the card must not
     * outlive the authority it rests on. The step back-issues an ACTIVE delegated card (the
     * grant is set once at issue, like production), then withdraws the grant and blocks the card.
     */
    private fun reconcileGrants(world: World, now: Instant) {
        val random = world.context.random
        if (!random.chance(FAIR_CHANCE)) return
        val index = world.cards.indexOfLast { it.status == CardStatus.ACTIVE && it.delegationGrantId == null }
        if (index < 0) return
        val card = world.cards[index]
        val grant = DelegatedCardGrant(
            id = random.nextUuid(),
            cardId = card.id,
            grantorPartyId = card.partyId,
            granteePartyId = random.nextUuid(),
            capabilities = setOf(DelegatedCardGrant.CAP_CARD_VIEW),
            validFrom = now.atZone(ZoneOffset.UTC).toOffsetDateTime().minusDays(1),
            validTo = now.atZone(ZoneOffset.UTC).toOffsetDateTime().plusDays(DELEGATE_GRANT_VALIDITY_DAYS),
            active = true,
        )
        world.delegatedCardGrants.add(grant)
        world.cards[index] = card.copy(delegationGrantId = grant.id)
        // The authority ends: ADR-0249 D2 — the card must be blocked, not merely "hidden".
        world.delegatedCardGrants[world.delegatedCardGrants.lastIndex] = grant.copy(active = false)
        world.cards[index] = world.cards[index].block("delegation grant ended (ADR-0249 D2)", now)
        world.audit.append("card ${card.id} blocked: its delegation grant ended")
    }

    /** Which lifecycle path this step's card takes. */
    private enum class Path { USE, EXPIRE, LOSE }
}
