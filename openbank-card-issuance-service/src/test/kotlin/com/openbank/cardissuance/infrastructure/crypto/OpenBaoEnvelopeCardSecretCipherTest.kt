// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.crypto

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Optional

private const val WRAPPED_DEK_FIXTURE = "vault:v1:not-a-real-wrapped-value"

// Shared, not one per call: these are unrelated test fixtures, not real key material, so reusing
// one generator is both fine and what CodeQL's "Random object created and used only once" wants.
private val testRandom = SecureRandom()

private fun randomDek(size: Int = 32): ByteArray = ByteArray(size).also { testRandom.nextBytes(it) }

// Never actually invoked by any test here — real HTTP calls to OpenBao live in
// OpenBaoTransitDekUnwrapper and are exercised in that class's own test. This cipher test only
// cares that it calls unwrapFn (or the injected unwrapper, wired but never reached when unwrapFn
// is set) with the configured wrapped DEK and uses the result.
private val unusedUnwrapper = mockk<OpenBaoTransitDekUnwrapper>()

/** Builds a cipher with [OpenBaoTransitDekUnwrapper.unwrap] stubbed via the lambda test seam. */
private fun cipher(
    wrapped: Optional<String>,
    allowEphemeral: Boolean,
    unwrapFn: ((String) -> ByteArray)? = { randomDek() },
) = OpenBaoEnvelopeCardSecretCipher(
    unwrapper = unusedUnwrapper,
    wrappedDek = wrapped,
    allowEphemeralKey = allowEphemeral,
    unwrapFn = unwrapFn,
)

class OpenBaoEnvelopeCardSecretCipherTest {

    @Test fun `round trips a PAN through the unwrapped DEK`() {
        val dek = randomDek()
        val c = cipher(Optional.of(WRAPPED_DEK_FIXTURE), allowEphemeral = false, unwrapFn = { dek })
        val pan = "4111111234567893"

        assertThat(c.decrypt(c.encrypt(pan))).isEqualTo(pan)
    }

    @Test fun `unwraps with the configured wrapped DEK`() {
        var seenWrapped: String? = null
        val c = cipher(
            wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
            allowEphemeral = false,
            unwrapFn = { wrapped ->
                seenWrapped = wrapped
                randomDek()
            },
        )
        c.encrypt("4111111234567893")

        assertThat(seenWrapped).isEqualTo(WRAPPED_DEK_FIXTURE)
    }

    @Test fun `never unwraps when no wrapped DEK is configured and ephemeral is allowed`() {
        // If loadDelegate() called unwrapFn here, this would throw — proving the ephemeral path
        // never reaches OpenBao when no wrapped DEK is configured.
        val c = cipher(
            wrapped = Optional.empty(),
            allowEphemeral = true,
            unwrapFn = { error("must not be called: no wrapped DEK configured") },
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

    @Test fun `refuses to start when the unwrapped DEK has the wrong length`() {
        assertThatThrownBy {
            cipher(
                wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
                allowEphemeral = false,
                unwrapFn = { randomDek(size = 16) },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unwrapped a DEK of 16 bytes, expected 32 (AES-256)")
    }

    @Test fun `a different unwrapped DEK does not decrypt what the first one encrypted`() {
        val sealed = cipher(
            wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
            allowEphemeral = false,
            unwrapFn = { randomDek() },
        ).encrypt("4111111234567893")

        val otherCipher = cipher(
            wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
            allowEphemeral = false,
            unwrapFn = { randomDek() },
        )

        assertThatThrownBy { otherCipher.decrypt(sealed) }.isInstanceOf(Exception::class.java)
    }

    @Test fun `propagates an unwrap failure instead of falling back silently`() {
        assertThatThrownBy {
            cipher(
                wrapped = Optional.of(WRAPPED_DEK_FIXTURE),
                allowEphemeral = false,
                unwrapFn = { error("OpenBao transit decrypt failed for key 'card-pan': HTTP 403") },
            )
        }.isInstanceOf(Exception::class.java)
    }
}
