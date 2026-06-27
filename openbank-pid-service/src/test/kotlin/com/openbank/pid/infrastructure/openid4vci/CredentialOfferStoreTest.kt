// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vci

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class CredentialOfferStoreTest {

    private val store = InMemoryCredentialOfferStore(ttlSeconds = 600)
    private val t0 = Instant.parse("2026-06-21T10:00:00Z")
    private val claims = OfferedClaims("CZ-1", "A", "B", "1990-01-01")

    @Test
    fun `a pre-authorized code is redeemable exactly once`(): Unit = runBlocking {
        store.create("code-1", claims, t0)
        assertThat(store.authorize("code-1", "tok-1", "nonce-1", t0)).isNotNull
        // Replay: the same code cannot be redeemed again.
        assertThat(store.authorize("code-1", "tok-2", "nonce-2", t0)).isNull()
        val byToken = store.findByAccessToken("tok-1", t0)
        assertThat(byToken).isNotNull
        assertThat(byToken!!.cNonce).isEqualTo("nonce-1")
        assertThat(byToken.status).isEqualTo(CredentialOfferStore.Status.AUTHORIZED)
    }

    @Test
    fun `a credential is minted exactly once per access token`(): Unit = runBlocking {
        store.create("code-2", claims, t0)
        store.authorize("code-2", "tok-2", "nonce-2", t0)
        assertThat(store.markIssued("tok-2", t0)).isTrue()
        // Replay: a second credential request on the same token is rejected.
        assertThat(store.markIssued("tok-2", t0)).isFalse()
        assertThat(store.findByAccessToken("tok-2", t0)!!.status).isEqualTo(CredentialOfferStore.Status.ISSUED)
    }

    @Test
    fun `an expired offer cannot be redeemed`(): Unit = runBlocking {
        store.create("code-3", claims, t0)
        assertThat(store.authorize("code-3", "tok-3", "nonce-3", t0.plusSeconds(601))).isNull()
    }

    @Test
    fun `an unknown code or token yields nothing`(): Unit = runBlocking {
        assertThat(store.authorize("nope", "t", "n", t0)).isNull()
        assertThat(store.findByAccessToken("nope", t0)).isNull()
        assertThat(store.markIssued("nope", t0)).isFalse()
    }
}
