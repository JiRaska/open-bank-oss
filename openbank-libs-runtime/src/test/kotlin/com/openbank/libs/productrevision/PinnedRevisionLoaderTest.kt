// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PinnedRevisionLoaderTest {

    private val irJson = checkNotNull(javaClass.getResourceAsStream("/product-ir/savings-fixed.json"))
        .readBytes().toString(Charsets.UTF_8)
    private val revision = ProductIrParser.parse(irJson)
    private val hash = revision.revisionHash
    private val signer = HmacIrSigner("0123456789abcdef0123456789abcdef".toByteArray(), "dev-hmac")
    private val registry = SimpleMeterRegistry()

    private fun count(outcome: RevisionLoadOutcome): Double =
        registry.find(PinnedRevisionLoader.METRIC).tag("outcome", outcome.tag).counter()?.count() ?: 0.0

    private fun loader(copies: Map<String, SignedRevisionCopy>, verifier: IrSignatureVerifierPort = signer) =
        PinnedRevisionLoader({ copies[it] }, verifier, registry)

    @Test
    fun `a signed copy loads once, is served from the immutable cache after, and both outcomes are counted apart`() {
        val fetches = intArrayOf(0)
        val copy = SignedRevisionCopy(irJson, signer.sign(hash))
        val loader = PinnedRevisionLoader({
            fetches[0]++
            copy.takeIf { _ -> it == hash }
        }, signer, registry)

        val first = loader.load(hash)
        val second = loader.load(hash)
        assertThat(first).isSameAs(second)
        assertThat(first.revisionHash).isEqualTo(hash)
        assertThat(fetches[0]).isEqualTo(1)
        assertThat(count(RevisionLoadOutcome.LOADED)).isEqualTo(1.0)
        assertThat(count(RevisionLoadOutcome.CACHE_HIT)).isEqualTo(1.0)
        assertThat(loader.cachedHashes()).containsExactly(hash)
    }

    @Test
    fun `a missing pin fails closed`() {
        val loader = loader(emptyMap())
        assertThatThrownBy { loader.load(hash) }
            .isInstanceOf(PinnedRevisionUnavailableException::class.java)
            .extracting { (it as PinnedRevisionUnavailableException).outcome }
            .isEqualTo(RevisionLoadOutcome.MISSING)
        assertThat(count(RevisionLoadOutcome.MISSING)).isEqualTo(1.0)
        assertThat(loader.cachedHashes()).isEmpty()
    }

    @Test
    fun `a mis-signed copy is refused with its own outcome and never cached`() {
        val forged = HmacIrSigner("fedcba9876543210fedcba9876543210".toByteArray(), "dev-hmac").sign(hash)
        val loader = loader(mapOf(hash to SignedRevisionCopy(irJson, forged)))
        assertThatThrownBy { loader.load(hash) }
            .isInstanceOf(PinnedRevisionUnavailableException::class.java)
            .hasMessageContaining("signature_invalid")
        assertThat(count(RevisionLoadOutcome.SIGNATURE_INVALID)).isEqualTo(1.0)
        assertThat(count(RevisionLoadOutcome.LOADED)).isZero()
        assertThat(loader.cachedHashes()).isEmpty()
    }

    @Test
    fun `a copy filed under the wrong hash is refused even when its signature is valid for its own hash`() {
        val other = "f".repeat(64)
        val loader = loader(mapOf(other to SignedRevisionCopy(irJson, signer.sign(hash))))
        assertThatThrownBy { loader.load(other) }.hasMessageContaining("hash_mismatch")
        assertThat(count(RevisionLoadOutcome.HASH_MISMATCH)).isEqualTo(1.0)
    }

    @Test
    fun `a copy whose bytes were edited after signing is refused as a hash mismatch`() {
        val edited = irJson.replace("\"0.0250\"", "\"0.0350\"")
        assertThat(ProductIrParser.parse(edited).revisionHash).isNotEqualTo(hash)
        val loader = loader(mapOf(hash to SignedRevisionCopy(edited, signer.sign(hash))))
        assertThatThrownBy { loader.load(hash) }.hasMessageContaining("hash_mismatch")
    }

    @Test
    fun `an invalid IR document is refused as invalid_ir`() {
        val loader = loader(mapOf(hash to SignedRevisionCopy("""{"irVersion":1}""", signer.sign(hash))))
        assertThatThrownBy { loader.load(hash) }.hasMessageContaining("invalid_ir")
        assertThat(count(RevisionLoadOutcome.INVALID_IR)).isEqualTo(1.0)
    }

    @Test
    fun `a verifier that cannot answer is unavailability, not a pass and not a signature failure`() {
        val down = IrSignatureVerifierPort { _, _ -> throw IrSigningException("Transit verify failed: HTTP 503") }
        val loader = loader(mapOf(hash to SignedRevisionCopy(irJson, signer.sign(hash))), down)
        assertThatThrownBy { loader.load(hash) }.hasMessageContaining("verifier_unavailable")
        assertThat(count(RevisionLoadOutcome.VERIFIER_UNAVAILABLE)).isEqualTo(1.0)
        assertThat(count(RevisionLoadOutcome.SIGNATURE_INVALID)).isZero()
        assertThat(count(RevisionLoadOutcome.LOADED)).isZero()
    }

    @Test
    fun `the canonical form of the loaded revision is the signed hash`() {
        val loader = loader(mapOf(hash to SignedRevisionCopy(irJson, signer.sign(hash))))
        assertThat(ProductIrCanonical.revisionHash(loader.load(hash))).isEqualTo(hash)
    }

    @Test
    fun `works without a registry`() {
        val loader = PinnedRevisionLoader({ SignedRevisionCopy(irJson, signer.sign(hash)) }, signer, registry = null)
        assertThat(loader.load(hash).productCode).isEqualTo("SAV-FIXED")
    }
}
