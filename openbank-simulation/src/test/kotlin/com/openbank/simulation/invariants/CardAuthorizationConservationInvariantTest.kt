// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.invariants

import com.openbank.cardprocessing.domain.model.AuthorizationStatus
import com.openbank.cardprocessing.domain.model.CardAuthorization
import com.openbank.cardprocessing.domain.model.PresentmentChannel
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import com.openbank.simulation.scenario.CardAuthorizationScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A checker that only ever passes proves nothing — each test here plants one realistic
 * card-money-path defect in an otherwise healthy [World] and asserts
 * [MoneyPathInvariants.cardAuthorizationConservation] catches it: a stranded hold, a replayed
 * capture posting, and a release that frees less than the uncleared remainder.
 */
class CardAuthorizationConservationInvariantTest {

    private val accountId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val now: Instant = Instant.parse("2026-06-01T12:00:00Z")

    private fun authorization(
        status: AuthorizationStatus,
        amountMinor: Long = 10_000L,
        clearedMinor: Long = 0L,
    ): CardAuthorization {
        val id = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val declineReason = if (status == AuthorizationStatus.DECLINED) "INSUFFICIENT_FUNDS" else null
        return CardAuthorization(
            id = id,
            cardId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            accountId = accountId,
            partyId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
            amountMinorUnits = amountMinor,
            currencyCode = "CZK",
            channel = PresentmentChannel.ONLINE,
            mcc = null,
            merchantName = null,
            merchantCountry = null,
            status = status,
            category = "uncategorized",
            declineReason = declineReason,
            clearedAmountMinorUnits = clearedMinor,
            networkReference = null,
            authorizedAt = now,
            expiresAt = now.plusSeconds(3600),
            updatedAt = now,
        )
    }

    private fun healthyWorld(): World {
        val world = World(SimulationContext(42L, FaultProfile.NONE), SimulationConfig())
        CardAuthorizationScenario.step(world)
        world.context.scheduler.drain()
        return world
    }

    @Test
    fun `a healthy settled run passes`() {
        val world = healthyWorld()
        assertThat(world.cardAuths.all()).isNotEmpty
        assertThat(MoneyPathInvariants.cardAuthorizationConservation.check(world)).isNull()
    }

    @Test
    fun `a declined request may retain its requested amount without clearing or releasing funds`() {
        val world = healthyWorld()
        world.cardAuths.place(authorization(AuthorizationStatus.DECLINED, amountMinor = 10_000L, clearedMinor = 0L))

        assertThat(MoneyPathInvariants.cardAuthorizationConservation.check(world)).isNull()
    }

    @Test
    fun `a declined authorization cannot release funds that were never held`() {
        val world = healthyWorld()
        val declined = authorization(AuthorizationStatus.DECLINED, amountMinor = 10_000L, clearedMinor = 0L)
        world.cardAuths.place(declined)
        world.cardAuths.recordReleased(declined.id, 1L)

        val violation = MoneyPathInvariants.cardAuthorizationConservation.check(world)
        assertThat(violation).isNotNull
        assertThat(violation!!.invariant).isEqualTo("card-authorization-conservation")
        assertThat(violation.detail).contains("declined", "released=1")
    }

    @Test
    fun `matching capture and posting cannot make a declined authorization valid`() {
        val world = healthyWorld()
        val declined = authorization(AuthorizationStatus.DECLINED, amountMinor = 10_000L, clearedMinor = 1L)
        world.cardAuths.place(declined)
        world.cardAuths.recordPosted(declined.id, 1L)

        val violation = MoneyPathInvariants.cardAuthorizationConservation.check(world)
        assertThat(violation).isNotNull
        assertThat(violation!!.invariant).isEqualTo("card-authorization-conservation")
        assertThat(violation.detail).contains("declined", "cleared=1", "posted=1", "released=0")
    }

    @Test
    fun `a hold that never reaches a terminal state is a permanent freeze`() {
        val world = healthyWorld()
        world.cardAuths.place(authorization(AuthorizationStatus.APPROVED))

        val violation = MoneyPathInvariants.cardAuthorizationConservation.check(world)
        assertThat(violation).isNotNull
        assertThat(violation!!.invariant).isEqualTo("card-authorization-conservation")
        assertThat(violation.detail).contains("still holds funds")
    }

    @Test
    fun `a replayed capture posting breaks the cleared-equals-posted law`() {
        val world = healthyWorld()
        // Simulate a re-delivered presentment double-posting the capture: the aggregate is a
        // legitimately cleared 100.00, but the ledger received the journal twice.
        world.cardAuths.place(authorization(AuthorizationStatus.CLEARED, amountMinor = 10_000L, clearedMinor = 10_000L))
        world.cardAuths.recordPosted(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            10_000L,
        )
        world.cardAuths.recordPosted(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            10_000L,
        )

        val violation = MoneyPathInvariants.cardAuthorizationConservation.check(world)
        assertThat(violation).isNotNull
        assertThat(violation!!.detail).contains("cleared=10000 but posted=20000")
    }

    @Test
    fun `a dropped capture posting breaks the cleared-equals-posted law the other way`() {
        val world = healthyWorld()
        // The policy accepted the clearing and advanced the aggregate, but the ledger post was
        // dropped: cleared says 40.00, posted says nothing. (The status is terminal in the
        // book — a mid-flight PARTIALLY_CLEARED would trip the settled-holds law first.)
        world.cardAuths.place(
            authorization(AuthorizationStatus.CLEARED, amountMinor = 10_000L, clearedMinor = 4_000L),
        )

        val violation = MoneyPathInvariants.cardAuthorizationConservation.check(world)
        assertThat(violation).isNotNull
        assertThat(violation!!.detail).contains("cleared=4000 but posted=0")
    }

    @Test
    fun `a release that frees less than the uncleared remainder silently freezes funds`() {
        val world = healthyWorld()
        // A reversal that released nothing: cleared 30.00 + released 0 != authorized 100.00 —
        // the customer is down the full hold while the scheme kept only the cleared part.
        world.cardAuths.place(
            authorization(AuthorizationStatus.REVERSED, amountMinor = 10_000L, clearedMinor = 3_000L),
        )
        world.cardAuths.recordPosted(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            3_000L,
        )

        val violation = MoneyPathInvariants.cardAuthorizationConservation.check(world)
        assertThat(violation).isNotNull
        assertThat(violation!!.detail).contains("cleared=3000 + released=0 != authorized=10000")
    }

    @Test
    fun `a release that overshoots frees money the scheme can still present against`() {
        val world = healthyWorld()
        // The remainder was only 70.00 but 80.00 was released: cleared 30.00 + released 80.00
        // overshoots the authorized 100.00 by 10.00 of phantom available balance.
        world.cardAuths.place(
            authorization(AuthorizationStatus.REVERSED, amountMinor = 10_000L, clearedMinor = 3_000L),
        )
        world.cardAuths.recordPosted(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            3_000L,
        )
        world.cardAuths.recordReleased(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            8_000L,
        )

        val violation = MoneyPathInvariants.cardAuthorizationConservation.check(world)
        assertThat(violation).isNotNull
        assertThat(violation!!.detail).contains("cleared=3000 + released=8000 != authorized=10000")
    }
}
