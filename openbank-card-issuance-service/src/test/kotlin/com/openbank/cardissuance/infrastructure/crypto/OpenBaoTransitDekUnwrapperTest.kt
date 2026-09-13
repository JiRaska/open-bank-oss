// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom

private const val WRAPPED_DEK_FIXTURE = "vault:v1:not-a-real-wrapped-value"
private const val LOGIN_TOKEN_FIXTURE = "stub-token"

private val testRandom = SecureRandom()
private fun randomDek(size: Int = 32): ByteArray = ByteArray(size).also { testRandom.nextBytes(it) }

/**
 * Stubs the two HTTP seams by subclassing (see the production class's own doc for why: this
 * class is injected by concrete type, which rules out the constructor-defaulted-lambda seam used
 * elsewhere in this package). Safe here — [login]/[unwrapDek] are only ever called on demand from
 * [OpenBaoTransitDekUnwrapper.unwrap], never from this class's own `init`, so there is no
 * Kotlin superclass-init-order risk.
 */
private open class StubUnwrapper(
    private val onLogin: () -> String = { LOGIN_TOKEN_FIXTURE },
    private val onUnwrap: (String, String) -> ByteArray = { _, _ -> randomDek() },
) : OpenBaoTransitDekUnwrapper(
    baoAddr = "http://openbao.invalid:8200",
    role = "card-issuance-pan-dek",
    transitMount = "transit",
    kekName = "card-pan",
    saTokenPath = "/nonexistent/token",
    objectMapper = ObjectMapper(),
) {
    override fun login(): String = onLogin()

    override fun unwrapDek(token: String, wrapped: String): ByteArray = onUnwrap(token, wrapped)
}

class OpenBaoTransitDekUnwrapperTest {

    @Test fun `logs in and unwraps with the given wrapped DEK and derived token`() {
        var seenToken: String? = null
        var seenWrapped: String? = null
        val dek = randomDek()
        val u = StubUnwrapper(
            onUnwrap = { token, wrapped ->
                seenToken = token
                seenWrapped = wrapped
                dek
            },
        )

        assertThat(u.unwrap(WRAPPED_DEK_FIXTURE)).isEqualTo(dek)
        assertThat(seenToken).isEqualTo(LOGIN_TOKEN_FIXTURE)
        assertThat(seenWrapped).isEqualTo(WRAPPED_DEK_FIXTURE)
    }

    @Test fun `retries a transient login failure and succeeds on a later attempt`() {
        var attempts = 0
        val dek = randomDek()
        val u = StubUnwrapper(
            onLogin = {
                attempts++
                if (attempts < 2) error("connection refused") else LOGIN_TOKEN_FIXTURE
            },
            onUnwrap = { _, _ -> dek },
        )

        assertThat(u.unwrap(WRAPPED_DEK_FIXTURE)).isEqualTo(dek)
        assertThat(attempts).isEqualTo(2)
    }

    @Test fun `retries a transient decrypt failure, logging in again each attempt`() {
        var loginAttempts = 0
        var decryptAttempts = 0
        val dek = randomDek()
        val u = StubUnwrapper(
            onLogin = {
                loginAttempts++
                "token-$loginAttempts"
            },
            onUnwrap = { token, _ ->
                decryptAttempts++
                if (decryptAttempts < 3) {
                    error("HTTP 503")
                } else {
                    // The retry restarts from a fresh login each attempt — never reuses a token
                    // from an earlier failed pass.
                    assertThat(token).isEqualTo("token-3")
                    dek
                }
            },
        )

        assertThat(u.unwrap(WRAPPED_DEK_FIXTURE)).isEqualTo(dek)
        assertThat(loginAttempts).isEqualTo(3)
    }

    @Test fun `gives up and throws after exhausting all attempts`() {
        var attempts = 0
        val u = StubUnwrapper(onLogin = {
            attempts++
            error("OpenBao unreachable")
        })

        assertThatThrownBy { u.unwrap(WRAPPED_DEK_FIXTURE) }.isInstanceOf(Exception::class.java)
        assertThat(attempts).isEqualTo(4)
    }
}
