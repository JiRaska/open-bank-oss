// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Optional

private const val WRAPPED_DEK_FIXTURE = "vault:v1:not-a-real-wrapped-value"
private const val LOGIN_TOKEN_FIXTURE = "stub-token"

private fun randomDek(size: Int = 32): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

/** Builds a cipher with the two OpenBao HTTP seams stubbed (see the class doc on why lambdas, not subclassing). */
private fun cipher(
    wrapped: Optional<String>,
    allowEphemeral: Boolean,
    loginFn: (() -> String)? = { LOGIN_TOKEN_FIXTURE },
    unwrapDekFn: ((String, String) -> ByteArray)? = { _, _ -> randomDek() },
) = OpenBaoEnvelopeCardSecretCipher(
    baoAddr = "http://openbao.invalid:8200",
    role = "card-issuance-pan-dek",
    transitMount = "transit",
    kekName = "card-pan",
    saTokenPath = "/nonexistent/token",
    wrappedDek = wrapped,
    allowEphemeralKey = allowEphemeral,
    objectMapper = ObjectMapper(),
    loginFn = loginFn,
    unwrapDekFn = unwrapDekFn,
)

class OpenBaoEnvelopeCardSecretCipherTest {

    @Test fun `round trips a PAN through the unwrapped DEK`() {
        val dek = randomDek()
        val c = cipher(Optional.of(WRAPPED_DEK_FIXTURE), allowEphemeral = false, unwrapDekFn = { _, _ -> dek })
        val pan = "4111111234567893"

        assertThat(c.decrypt(c.encrypt(pan))).isEqualTo(pan)
    }

    @Test fun `logs in and unwraps with the configured wrapped DEK and derived token`() {
        var seenToken: String? = null
        var seenWrapped: String? = null
        val c = cipher(
            wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
            allowEphemeral = false,
            unwrapDekFn = { token, wrapped ->
                seenToken = token
                seenWrapped = wrapped
                randomDek()
            },
        )
        c.encrypt("4111111234567893")

        assertThat(seenToken).isEqualTo(LOGIN_TOKEN_FIXTURE)
        assertThat(seenWrapped).isEqualTo(WRAPPED_DEK_FIXTURE)
    }

    @Test fun `never calls OpenBao when no wrapped DEK is configured and ephemeral is allowed`() {
        // If loadDelegate() called either seam here, these would throw — proving the ephemeral path
        // never reaches OpenBao when no wrapped DEK is configured.
        val c = cipher(
            wrapped = Optional.empty(),
            allowEphemeral = true,
            loginFn = { error("must not be called: no wrapped DEK configured") },
            unwrapDekFn = { _, _ -> error("must not be called: no wrapped DEK configured") },
        )

        assertThat(c.decrypt(c.encrypt("4111111111111111"))).isEqualTo("4111111111111111")
    }

    @Test fun `two ephemeral boots do not share a key`() {
        val a = cipher(Optional.empty(), allowEphemeral = true)
        val b = cipher(Optional.empty(), allowEphemeral = true)

        assertThatThrownBy { b.decrypt(a.encrypt("4111111111111111")) }.isInstanceOf(Exception::class.java)
    }

    @Test fun `refuses to start without a wrapped DEK and without the ephemeral escape hatch`() {
        assertThatThrownBy { cipher(Optional.empty(), allowEphemeral = false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("openbank.card.envelope.wrapped-dek is not set")
    }

    @Test fun `refuses to start when Transit unwraps a DEK of the wrong length`() {
        assertThatThrownBy {
            cipher(
                wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
                allowEphemeral = false,
                unwrapDekFn = { _, _ -> randomDek(size = 16) },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unwrapped a DEK of 16 bytes, expected 32 (AES-256)")
    }

    @Test fun `a different unwrapped DEK does not decrypt what the first one encrypted`() {
        val sealed = cipher(
            wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
            allowEphemeral = false,
            unwrapDekFn = { _, _ -> randomDek() },
        ).encrypt("4111111234567893")

        val otherCipher = cipher(
            wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
            allowEphemeral = false,
            unwrapDekFn = { _, _ -> randomDek() },
        )

        assertThatThrownBy { otherCipher.decrypt(sealed) }.isInstanceOf(Exception::class.java)
    }

    @Test fun `propagates a login failure instead of falling back silently`() {
        assertThatThrownBy {
            cipher(
                wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
                allowEphemeral = false,
                loginFn = { error("OpenBao kubernetes-auth login failed: HTTP 403") },
            )
        }.isInstanceOf(Exception::class.java)
    }
}
