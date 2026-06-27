// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vp

import com.openbank.pid.application.port.`in`.EudiResolutionResult
import com.openbank.pid.application.port.`in`.ResolutionResult
import com.openbank.pid.domain.model.PidClaims
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class PresentationExchangeStoreTest {

    private val store = InMemoryPresentationExchangeStore(ttlSeconds = 300)
    private val t0 = Instant.parse("2026-06-21T10:00:00Z")

    private fun aResult() = EudiResolutionResult(
        claims = PidClaims(
            subjectId = "sub:CZ-1",
            givenName = "A",
            familyName = "B",
            birthDate = LocalDate.of(1990, 1, 1),
            issuingCountry = "CZ",
            issuer = "https://issuer",
        ),
        resolution = ResolutionResult.NoMatch,
    )

    @Test
    fun `a created exchange is PENDING and preserves nonce and audience`(): Unit = runBlocking {
        store.create("tx-1", "nonce-1", "openbank-pid", t0)
        val found = store.find("tx-1", t0)
        assertThat(found).isNotNull
        assertThat(found!!.status).isEqualTo(PresentationExchangeStore.Status.PENDING)
        assertThat(found.nonce).isEqualTo("nonce-1")
        assertThat(found.audience).isEqualTo("openbank-pid")
    }

    @Test
    fun `complete spends the nonce exactly once`(): Unit = runBlocking {
        store.create("tx-2", "nonce-2", "openbank-pid", t0)
        assertThat(store.complete("tx-2", aResult(), t0)).isTrue()
        // Replay: the same exchange cannot be completed again.
        assertThat(store.complete("tx-2", aResult(), t0)).isFalse()
        val found = store.find("tx-2", t0)
        assertThat(found!!.status).isEqualTo(PresentationExchangeStore.Status.COMPLETED)
        assertThat(found.result).isNotNull
    }

    @Test
    fun `an expired exchange cannot be completed and reads as EXPIRED`(): Unit = runBlocking {
        store.create("tx-3", "nonce-3", "openbank-pid", t0)
        val afterTtl = t0.plusSeconds(301)
        assertThat(store.complete("tx-3", aResult(), afterTtl)).isFalse()
        assertThat(store.find("tx-3", afterTtl)!!.status).isEqualTo(PresentationExchangeStore.Status.EXPIRED)
    }

    @Test
    fun `an unknown transaction is not found and cannot be completed`(): Unit = runBlocking {
        assertThat(store.find("nope", t0)).isNull()
        assertThat(store.complete("nope", aResult(), t0)).isFalse()
    }
}
