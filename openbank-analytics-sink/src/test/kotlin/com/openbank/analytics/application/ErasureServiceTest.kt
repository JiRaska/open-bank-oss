// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.openbank.analytics.application.port.out.CryptoErasure
import com.openbank.libs.analytics.AggregateKey
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * What [ErasureService] is allowed to claim (#9671).
 *
 * `ErasureDecision` is described in its own KDoc as the position to show a supervisor or the data
 * subject, and it used to carry `erased = true` on every path that was not a statutory refusal —
 * a literal, not a measurement. The deployed image contains `NoOpCryptoErasure` (the vault adapter
 * is `@IfBuildProperty(openbank.analytics.erasure.backend=vault)`, resolved at augmentation, and
 * the property is set only in `docker-compose.yml`), so every Art. 17 request answered
 * `erased: true, rowsAffected: 0` with the explanation "Crypto-shredded analytics data".
 *
 * There was no test over this class at all before this one, which is how a literal survived.
 *
 * **`rowsAffected` cannot be the discriminator, and the third test is what says so.** A real
 * backend returns 0 for a subject with no warehouse data, so `0` is shared by "erased nothing
 * because there was nothing" and "erased nothing because this build cannot". Only asking the port
 * separates them, and only that test fails if someone reintroduces a row-count heuristic.
 */
class ErasureServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC)

    /** Stands in for the deployed default: logs, destroys nothing, reports zero. */
    private class StubErasure : CryptoErasure {
        override val performsErasure = false
        var called = false
        override suspend fun erase(key: AggregateKey): Long {
            called = true
            return 0
        }
    }

    /** A real backend that happens to find nothing for this subject. */
    private class RealErasureFindingNothing : CryptoErasure {
        override suspend fun erase(key: AggregateKey): Long = 0
    }

    private class RealErasure(private val rows: Long) : CryptoErasure {
        override suspend fun erase(key: AggregateKey): Long = rows
    }

    private fun serviceWith(backend: CryptoErasure) = ErasureService().apply {
        cryptoErasure = backend
        clock = this@ErasureServiceTest.clock
    }

    /** An erasable category, so the legal gate is open and the backend decides the outcome. */
    private val erasableType = "CONSENT"

    @Test
    fun `a build with no erasure backend reports NO_BACKEND, never an erasure`(): Unit = runBlocking {
        val backend = StubErasure()

        val d = serviceWith(backend).erase(erasableType, "subject-1", "dpo@openbank.cz")

        assertThat(d.outcome).isEqualTo(ErasureOutcome.NO_BACKEND)
        assertThat(d.erased).isFalse()
        assertThat(d.rowsAffected).isZero()
        // The text is the part a supervisor reads, so it must not assert a shred either.
        assertThat(d.explanation).doesNotContain("Crypto-shredded")
        assertThat(d.explanation).contains("No erasure backend")
        // And it must not read as a legal refusal, which is a defensible position this is not.
        assertThat(d.explanation).contains("NOT a refusal")
        // No point calling a backend that cannot erase.
        assertThat(backend.called).isFalse()
    }

    @Test
    fun `a real backend that erased rows reports ERASED`(): Unit = runBlocking {
        val d = serviceWith(RealErasure(rows = 7)).erase(erasableType, "subject-2", "dpo@openbank.cz")

        assertThat(d.outcome).isEqualTo(ErasureOutcome.ERASED)
        assertThat(d.erased).isTrue()
        assertThat(d.rowsAffected).isEqualTo(7)
        assertThat(d.explanation).contains("Crypto-shredded")
    }

    @Test
    fun `a real backend finding nothing still reports ERASED, so rowsAffected is not the signal`(): Unit = runBlocking {
        val d = serviceWith(RealErasureFindingNothing())
            .erase(erasableType, "subject-3", "dpo@openbank.cz")

        // Same rowsAffected as the NO_BACKEND case above, opposite outcome. Reintroduce a
        // `rows == 0` heuristic in place of `performsErasure` and this is the test that goes red.
        assertThat(d.rowsAffected).isZero()
        assertThat(d.outcome).isEqualTo(ErasureOutcome.ERASED)
        assertThat(d.erased).isTrue()
    }

    @Test
    fun `a category under a statutory hold is refused, and that is distinct from NO_BACKEND`(): Unit = runBlocking {
        // TRANSACTION-shaped data is held for the accounting/AML retention period.
        val d = serviceWith(RealErasure(rows = 99)).erase("TRANSACTION", "subject-4", "dpo@openbank.cz")

        assertThat(d.outcome).isEqualTo(ErasureOutcome.REFUSED_LEGAL_HOLD)
        assertThat(d.erased).isFalse()
        assertThat(d.rowsAffected).isZero()
        assertThat(d.explanation).contains("Art. 17(3)(b)")
    }
}
