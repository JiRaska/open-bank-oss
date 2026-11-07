// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.signing

import com.openbank.libs.productrevision.IrSigningException
import com.openbank.libs.productrevision.RevisionIdentity
import com.openbank.productcatalog.infrastructure.signing.IrSignerProducer
import com.openbank.productcatalog.infrastructure.signing.IrSigningMode
import io.quarkus.runtime.LaunchMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

/** The signing mode cannot put a forgeable signer into a production launch (review finding B1). */
class IrSigningModeTest {

    @Test
    fun `hmac is permitted only in dev and test launch modes, every other mode everywhere`() {
        assertThat(IrSigningMode.permitted(IrSigningMode.HMAC, LaunchMode.NORMAL)).isFalse()
        assertThat(IrSigningMode.permitted(IrSigningMode.HMAC, LaunchMode.DEVELOPMENT)).isTrue()
        assertThat(IrSigningMode.permitted(IrSigningMode.HMAC, LaunchMode.TEST)).isTrue()
        for (launch in LaunchMode.entries) {
            assertThat(IrSigningMode.permitted(IrSigningMode.OPENBAO, launch)).isTrue()
            assertThat(IrSigningMode.permitted(IrSigningMode.DISABLED, launch)).isTrue()
        }
    }

    @Test
    fun `the default mode is disabled and an unknown mode is a boot error`() {
        assertThat(IrSigningMode.parse(IrSigningMode.DEFAULT)).isEqualTo("disabled")
        assertThatThrownBy { IrSigningMode.parse("hmac-please") }.hasMessageContaining("must be one of")
    }

    @Test
    fun `disabled signs nothing, and hmac without a secret is refused instead of using a default`() {
        assertThatThrownBy { producer("disabled", Optional.empty()).irSigner().sign(IDENTITY) }
            .isInstanceOf(IrSigningException::class.java)
            .hasMessageContaining("disabled")
        assertThatThrownBy { producer("hmac", Optional.empty()).irSigner() }
            .hasMessageContaining("needs openbank.catalog.ir-signing.hmac-secret")
        val signer = producer("hmac", Optional.of("unit-test-secret-not-for-production")).irSigner()
        val signed = signer.sign(IDENTITY)
        assertThat(signed.algorithm).isEqualTo("hmac-sha256")
    }

    private fun producer(mode: String, secret: Optional<String>) = IrSignerProducer(
        mode = mode,
        keyId = "product-ir-publish",
        hmacSecret = secret,
        baoAddress = "https://openbao.example.invalid:8200",
        transitMount = "transit",
        role = "product-catalog-ir-signing",
        saTokenPath = "/nonexistent",
    )

    private companion object {
        val IDENTITY = RevisionIdentity(
            "SAV-FIXED",
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            1,
            1,
            "a".repeat(64),
        )
    }
}
