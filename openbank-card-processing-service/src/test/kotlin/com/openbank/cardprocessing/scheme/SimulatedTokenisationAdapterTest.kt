// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.scheme

import com.openbank.cardprocessing.infrastructure.scheme.RoutedTokenisationPort
import com.openbank.cardprocessing.infrastructure.scheme.SimulatedTokenisationAdapter
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.NetworkToken
import com.openbank.libs.domain.cards.scheme.NetworkTokenStatus
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import com.openbank.libs.domain.cards.scheme.TokenRequestor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The token lifecycle a caller depends on: provision, list, change status — and the two states that
 * are easy to model wrongly, an empty list and a terminal DELETE.
 */
class SimulatedTokenisationAdapterTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)
    private val adapter = SimulatedTokenisationAdapter(clock)
    private val wallet = TokenRequestor("wallet-1", "Simulated Wallet")

    @Test
    fun `a provisioned token is listed back for its card`(): Unit = runBlocking {
        val provisioned = adapter.provision("card-1", wallet) as SchemeResult.Answered

        val listed = adapter.listTokens("card-1") as SchemeResult.Answered

        assertThat(listed.value.map { it.tokenReference }).containsExactly(provisioned.value.tokenReference)
        assertThat(provisioned.value.status).isEqualTo(NetworkTokenStatus.ACTIVE)
        assertThat(provisioned.scheme).isEqualTo(CardScheme.SIMULATOR)
    }

    @Test
    fun `nothing the simulator mints is PAN-shaped`(): Unit = runBlocking {
        val token = (adapter.provision("card-1", wallet) as SchemeResult.Answered).value

        // The ports were designed so no signature accepts a PAN; the simulator must not smuggle
        // one in through the back door by minting something that looks like one.
        assertThat(token.tokenReference).startsWith("sim-tok-")
        assertThat(token.last4).isEqualTo("0000")
        assertThat(token.tokenReference).doesNotMatch(".*\\d{12,}.*")
    }

    @Test
    fun `a card with no tokens answers with an empty list, not a failure`(): Unit = runBlocking {
        val listed = adapter.listTokens("card-with-nothing")

        // "No tokens" is an answer; "we could not ask" is not. A failure here would make a caller
        // treat a normal state as an error and the "no wallets yet" branch would never render.
        val answered = listed as SchemeResult.Answered
        assertThat(answered.value).isEmpty()
    }

    @Test
    fun `a token can be suspended and resumed`(): Unit = runBlocking {
        val token = (adapter.provision("card-1", wallet) as SchemeResult.Answered).value

        val suspended = adapter.changeStatus(token.tokenReference, NetworkTokenStatus.SUSPENDED)
        val resumed = adapter.changeStatus(token.tokenReference, NetworkTokenStatus.ACTIVE)

        assertThat((suspended as SchemeResult.Answered).value.status).isEqualTo(NetworkTokenStatus.SUSPENDED)
        assertThat((resumed as SchemeResult.Answered).value.status).isEqualTo(NetworkTokenStatus.ACTIVE)
    }

    @Test
    fun `DELETED is terminal — a deleted token cannot be resumed`(): Unit = runBlocking {
        val token = (adapter.provision("card-1", wallet) as SchemeResult.Answered).value
        adapter.changeStatus(token.tokenReference, NetworkTokenStatus.DELETED)

        val resurrect = adapter.changeStatus(token.tokenReference, NetworkTokenStatus.ACTIVE)

        // Pretending otherwise would let a caller believe it had restored a wallet credential the
        // network has already destroyed.
        assertThat((resurrect as SchemeResult.Unanswered).failure).isEqualTo(SchemeFailure.MALFORMED)
    }

    @Test
    fun `an unknown token is NOT_FOUND`(): Unit = runBlocking {
        val result = adapter.changeStatus("sim-tok-nope", NetworkTokenStatus.SUSPENDED)

        assertThat((result as SchemeResult.Unanswered).failure).isEqualTo(SchemeFailure.NOT_FOUND)
    }

    @Test
    fun `choosing a vendor binding says a contract is needed, and names the network`(): Unit = runBlocking {
        val router = RoutedTokenisationPort(adapter, "visa")

        val result: SchemeResult<NetworkToken> = router.provision("card-1", wallet)

        val unanswered = result as SchemeResult.Unanswered
        assertThat(unanswered.failure).isEqualTo(SchemeFailure.NOT_BOUND)
        // The failure names the scheme the caller ASKED for. Attributing it to the simulator would
        // read as the simulator being broken.
        assertThat(unanswered.scheme).isEqualTo(CardScheme.VISA)
        assertThat(unanswered.detail).contains("contract")
    }

    @Test
    fun `the default binding is the simulator`(): Unit = runBlocking {
        val router = RoutedTokenisationPort(adapter, "simulator")

        val result = router.provision("card-1", wallet)

        assertThat((result as SchemeResult.Answered).scheme).isEqualTo(CardScheme.SIMULATOR)
    }
}
