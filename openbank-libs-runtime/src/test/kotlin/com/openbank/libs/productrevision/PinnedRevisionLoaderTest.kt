// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.productrevision

import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test
import java.util.UUID

class PinnedRevisionLoaderTest {

    private val irJson = checkNotNull(javaClass.getResourceAsStream("/product-ir/savings-fixed.json"))
        .readBytes().toString(Charsets.UTF_8)
    private val revision = ProductIrParser.parse(irJson)
    private val hash = revision.revisionHash
    private val pin = RevisionIdentity(revision.productCode, REVISION_A, 1, revision.irVersion, hash)

    /** A second, equally valid product: same terms, another product code, so another hash. */
    private val otherJson = irJson.replace("\"SAV-FIXED\"", "\"SAV-OTHER\"")
    private val other = ProductIrParser.parse(otherJson)
    private val otherPin = RevisionIdentity(other.productCode, REVISION_B, 1, other.irVersion, other.revisionHash)

    private val signer = HmacIrSigner("0123456789abcdef0123456789abcdef".toByteArray(), "dev-hmac")
    private val registry = SimpleMeterRegistry()

    private fun count(outcome: RevisionLoadOutcome): Double =
        registry.find(PinnedRevisionLoader.METRIC).tag("outcome", outcome.tag).counter()?.count() ?: 0.0

    private fun signed(json: String, identity: RevisionIdentity) =
        SignedRevisionCopy(json, identity, signer.sign(identity))

    private fun loader(source: SignedRevisionSource, verifier: IrSignatureVerifierPort = signer) =
        PinnedRevisionLoader(source, verifier, registry)

    private fun loader(copy: SignedRevisionCopy, verifier: IrSignatureVerifierPort = signer) =
        loader({ copy }, verifier)

    private fun refusedAs(outcome: RevisionLoadOutcome, call: ThrowingCallable) {
        assertThatThrownBy(call)
            .isInstanceOf(PinnedRevisionUnavailableException::class.java)
            .extracting { (it as PinnedRevisionUnavailableException).outcome }
            .isEqualTo(outcome)
    }

    // ---- identity binding (negative cases first) -------------------------------------------------

    @Test
    fun `a valid signature for product A presented under product B's pin is refused`() {
        val genuineA = signed(irJson, pin)
        // The source serves A's genuine copy where B's pin is expected.
        val servesA = loader({ genuineA })
        refusedAs(RevisionLoadOutcome.IDENTITY_MISMATCH) { servesA.load(otherPin) }
        // Relabelled as B, B's IR, but A's signature: the signature is checked against the PIN.
        val relabelled = SignedRevisionCopy(otherJson, otherPin, genuineA.signature)
        refusedAs(RevisionLoadOutcome.SIGNATURE_INVALID) { loader(relabelled).load(otherPin) }
        // A contract that names product B but A's hash: A's IR is never served as B.
        val mispinned = otherPin.copy(revisionHash = hash)
        refusedAs(RevisionLoadOutcome.IDENTITY_MISMATCH) { loader(signed(irJson, mispinned)).load(mispinned) }
        assertThat(count(RevisionLoadOutcome.IDENTITY_MISMATCH)).isEqualTo(2.0)
        assertThat(count(RevisionLoadOutcome.SIGNATURE_INVALID)).isEqualTo(1.0)
        assertThat(count(RevisionLoadOutcome.LOADED)).isZero()
    }

    @Test
    fun `a valid signature for one revision id is refused under another revision id with the same IR`() {
        val pinned = pin.copy(revisionId = REVISION_B)
        val genuine = signed(irJson, pin)
        refusedAs(RevisionLoadOutcome.IDENTITY_MISMATCH) { loader(genuine).load(pinned) }
        val relabelled = SignedRevisionCopy(irJson, pinned, genuine.signature)
        refusedAs(RevisionLoadOutcome.SIGNATURE_INVALID) { loader(relabelled).load(pinned) }
        assertThat(count(RevisionLoadOutcome.LOADED)).isZero()
    }

    @Test
    fun `a valid signature for one revision number is refused under another revision number with the same IR`() {
        val pinned = pin.copy(revisionNumber = 2)
        val genuine = signed(irJson, pin)
        refusedAs(RevisionLoadOutcome.IDENTITY_MISMATCH) { loader(genuine).load(pinned) }
        val relabelled = SignedRevisionCopy(irJson, pinned, genuine.signature)
        refusedAs(RevisionLoadOutcome.SIGNATURE_INVALID) { loader(relabelled).load(pinned) }
        assertThat(count(RevisionLoadOutcome.LOADED)).isZero()
    }

    @Test
    fun `an IR version the pin does not name is refused even when signed for that version`() {
        val pinned = pin.copy(irVersion = revision.irVersion + 1)
        refusedAs(RevisionLoadOutcome.IDENTITY_MISMATCH) { loader(signed(irJson, pinned)).load(pinned) }
    }

    @Test
    fun `a signature over the bare hash, the pre-envelope form, does not verify`() {
        val bare = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec("0123456789abcdef0123456789abcdef".toByteArray(), "HmacSHA256"))
        }.doFinal(hash.toByteArray(Charsets.US_ASCII))
        val legacy = IrSignature("dev-hmac", HmacIrSigner.ALGORITHM, java.util.Base64.getEncoder().encodeToString(bare))
        refusedAs(RevisionLoadOutcome.SIGNATURE_INVALID) { loader(SignedRevisionCopy(irJson, pin, legacy)).load(pin) }
    }

    @Test
    fun `the signing envelope is canonical, domain-tagged and changes with every bound field`() {
        val text = pin.signingInput().toString(Charsets.UTF_8)
        assertThat(text).isEqualTo(
            """{"domain":"openbank.product-revision-signature.v1","irVersion":${pin.irVersion},""" +
                """"productCode":"SAV-FIXED","revisionHash":"$hash","revisionId":"$REVISION_A","revisionNumber":1}""",
        )
        val variants = listOf(
            pin.copy(productCode = "SAV-OTHER"),
            pin.copy(revisionId = REVISION_B),
            pin.copy(revisionNumber = 2),
            pin.copy(irVersion = pin.irVersion + 1),
            pin.copy(revisionHash = "f".repeat(64)),
        ).map { it.signingInput().toString(Charsets.UTF_8) }
        assertThat(variants).doesNotContain(text).doesNotHaveDuplicates()
    }

    // ---- positive and pre-existing outcomes -----------------------------------------------------

    @Test
    fun `a copy signed for exactly the pin loads once, then comes from the cache, and both outcomes are counted`() {
        val fetches = intArrayOf(0)
        val copy = signed(irJson, pin)
        val loader = loader({
            fetches[0]++
            copy.takeIf { _ -> it == pin }
        })

        val first = loader.load(pin)
        val second = loader.load(pin)
        assertThat(first).isSameAs(second)
        assertThat(first.revisionHash).isEqualTo(hash)
        assertThat(fetches[0]).isEqualTo(1)
        assertThat(count(RevisionLoadOutcome.LOADED)).isEqualTo(1.0)
        assertThat(count(RevisionLoadOutcome.CACHE_HIT)).isEqualTo(1.0)
        assertThat(loader.cachedPins()).containsExactly(pin)
    }

    @Test
    fun `two revisions with identical IR are cached apart, each under its own pin`() {
        val second = pin.copy(revisionId = REVISION_B, revisionNumber = 2)
        val copies = mapOf(pin to signed(irJson, pin), second to signed(irJson, second))
        val loader = loader({ copies[it] })
        loader.load(pin)
        loader.load(second)
        assertThat(loader.cachedPins()).containsExactlyInAnyOrder(pin, second)
    }

    @Test
    fun `a missing pin fails closed`() {
        val loader = loader({ null })
        refusedAs(RevisionLoadOutcome.MISSING) { loader.load(pin) }
        assertThat(count(RevisionLoadOutcome.MISSING)).isEqualTo(1.0)
        assertThat(loader.cachedPins()).isEmpty()
    }

    @Test
    fun `a mis-signed copy is refused with its own outcome and never cached`() {
        val forged = HmacIrSigner("fedcba9876543210fedcba9876543210".toByteArray(), "dev-hmac").sign(pin)
        val loader = loader(SignedRevisionCopy(irJson, pin, forged))
        assertThatThrownBy { loader.load(pin) }
            .isInstanceOf(PinnedRevisionUnavailableException::class.java)
            .hasMessageContaining("signature_invalid")
        assertThat(count(RevisionLoadOutcome.SIGNATURE_INVALID)).isEqualTo(1.0)
        assertThat(count(RevisionLoadOutcome.LOADED)).isZero()
        assertThat(loader.cachedPins()).isEmpty()
    }

    @Test
    fun `a copy filed under the wrong hash is refused even when its signature is valid for that identity`() {
        val wrong = pin.copy(revisionHash = "f".repeat(64))
        assertThatThrownBy { loader(signed(irJson, wrong)).load(wrong) }.hasMessageContaining("hash_mismatch")
        assertThat(count(RevisionLoadOutcome.HASH_MISMATCH)).isEqualTo(1.0)
    }

    @Test
    fun `a copy whose bytes were edited after signing is refused as a hash mismatch`() {
        val edited = irJson.replace("\"0.0250\"", "\"0.0350\"")
        assertThat(ProductIrParser.parse(edited).revisionHash).isNotEqualTo(hash)
        val copy = SignedRevisionCopy(edited, pin, signer.sign(pin))
        assertThatThrownBy { loader(copy).load(pin) }.hasMessageContaining("hash_mismatch")
    }

    @Test
    fun `an invalid IR document is refused as invalid_ir`() {
        val copy = SignedRevisionCopy("""{"irVersion":1}""", pin, signer.sign(pin))
        assertThatThrownBy { loader(copy).load(pin) }.hasMessageContaining("invalid_ir")
        assertThat(count(RevisionLoadOutcome.INVALID_IR)).isEqualTo(1.0)
    }

    @Test
    fun `a verifier that cannot answer is unavailability, not a pass and not a signature failure`() {
        val down = IrSignatureVerifierPort { _, _ -> throw IrSigningException("Transit verify failed: HTTP 503") }
        assertThatThrownBy { loader(signed(irJson, pin), down).load(pin) }.hasMessageContaining("verifier_unavailable")
        assertThat(count(RevisionLoadOutcome.VERIFIER_UNAVAILABLE)).isEqualTo(1.0)
        assertThat(count(RevisionLoadOutcome.SIGNATURE_INVALID)).isZero()
        assertThat(count(RevisionLoadOutcome.LOADED)).isZero()
    }

    @Test
    fun `the canonical form of the loaded revision is the signed hash`() {
        assertThat(ProductIrCanonical.revisionHash(loader(signed(irJson, pin)).load(pin))).isEqualTo(hash)
    }

    @Test
    fun `works without a registry`() {
        val loader = PinnedRevisionLoader({ signed(irJson, pin) }, signer, registry = null)
        assertThat(loader.load(pin).productCode).isEqualTo("SAV-FIXED")
    }

    private companion object {
        val REVISION_A: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val REVISION_B: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    }
}
