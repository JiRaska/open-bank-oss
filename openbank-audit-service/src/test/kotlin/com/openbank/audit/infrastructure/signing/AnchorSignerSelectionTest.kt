// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.kms.KmsClient
import java.util.Optional

/**
 * Bean selection is a BOOT-TIME decision (`AnchorSignerProducer.select`): whichever branch it
 * takes is the signer every audit anchor in that deployment is signed with, and a wrong branch is
 * only observable once anchors are unverifiable. Exercised here without CDI so the failure modes
 * — an unsupported name, and `signer=kms` with no key id — are asserted rather than assumed.
 */
class AnchorSignerSelectionTest {

    private val producer = AnchorSignerProducer()
    private val kmsClient = { mockk<KmsClient>() }

    @Test
    fun `hmac is the development signer, and its key id is recorded on every anchor`() {
        val signer = producer.select("hmac", "local-key", Optional.empty(), kmsClient)

        assertThat(signer).isInstanceOf(LocalHmacAnchorSigner::class.java)
        assertThat(signer.keyId).isEqualTo("local-hmac-sha256")
    }

    @Test
    fun `the signer name is matched case-insensitively and trimmed`() {
        val signer = producer.select("  HMAC ", "local-key", Optional.empty(), kmsClient)

        assertThat(signer).isInstanceOf(LocalHmacAnchorSigner::class.java)
    }

    @Test
    fun `kms selects the asymmetric signer when a key id is supplied`() {
        val signer = producer.select("kms", "unused", Optional.of(" arn:aws:kms:key/abc "), kmsClient)

        assertThat(signer).isInstanceOf(AwsKmsAnchorSigner::class.java)
    }

    @Test
    fun `kms with no key id fails loudly rather than falling back to a development signer`() {
        assertThatIllegalArgumentException()
            .isThrownBy { producer.select("kms", "unused", Optional.empty(), kmsClient) }
            .withMessageContaining("kms-key-id is required")
    }

    @Test
    fun `kms with a blank key id is the same failure - an empty env var is not a key`() {
        assertThatIllegalArgumentException()
            .isThrownBy { producer.select("kms", "unused", Optional.of("   "), kmsClient) }
    }

    @Test
    fun `an unsupported signer name aborts the boot instead of silently defaulting`() {
        assertThatIllegalStateException()
            .isThrownBy { producer.select("cosign", "unused", Optional.empty(), kmsClient) }
            .withMessageContaining("cosign")
    }

    @Test
    fun `the hmac signer refuses to judge an anchor signed by another key generation`() {
        val signer = LocalHmacAnchorSigner("k1")
        val digest = "digest".toByteArray()
        val signature = signer.sign(digest).value

        // null, NOT false: "I cannot verify this historical key" must stay distinguishable from
        // "this signature is invalid", or a key rotation reads as a chain rewrite.
        assertThat(signer.verify(digest, signature, "kms-2026")).isNull()
        assertThat(signer.verify(digest, signature, "local-hmac-sha256")).isTrue()
    }

    @Test
    fun `a symmetric signer exposes no public verification key`() {
        assertThat(LocalHmacAnchorSigner("k1").verificationKeyPem("local-hmac-sha256")).isNull()
    }

    @Test
    fun `the hmac signature is base64 and stable for the same key and digest`() {
        val signer = LocalHmacAnchorSigner("k1")
        val first = signer.sign("digest".toByteArray())
        val second = signer.sign("digest".toByteArray())

        assertThat(first.value).isEqualTo(second.value)
        assertThat(first.keyId).isEqualTo("local-hmac-sha256")
        assertThat(java.util.Base64.getDecoder().decode(first.value)).hasSize(32)
    }
}
