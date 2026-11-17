// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Every negative case below is the [valid] fixture with exactly ONE field changed, and [valid] itself
 * must return VALID. So each check is load-bearing: delete it and its test returns VALID (or a
 * different result) instead of the one asserted.
 */
class DpopProofVerifierTest {
    private val now = Instant.parse("2026-11-17T10:00:00Z")
    private val token = "Kz~8mXK1EalYznwH-LC-1fBAo.4Ljp~zsPE_NeO.gxU"
    private val jkt = "0ZcOCORZNYy-DWpqq30jZyJGHTN0d2HglBV3uiguA4I"

    private val valid = DpopProof(
        typ = "dpop+jwt",
        alg = "ES256",
        jwkThumbprint = jkt,
        jwkHasPrivateMaterial = false,
        signatureValid = true,
        htm = "GET",
        htu = "https://resource.example.org/protectedresource",
        iat = now,
        jti = "e1j3V_bKic8-LAEB",
        ath = "fUHyO2r2Z3DZ53EsNrWBb0xWXoaNy59IiKCAqksmQEo",
    )

    private val request = DpopRequest(
        dpopHeaderValues = listOf("proof"),
        method = "GET",
        requestUri = "https://resource.example.org/protectedresource?x=1#f",
        accessToken = token,
        boundJkt = jkt,
    )

    private class RecordingStore : DpopReplayStore {
        val seen = mutableSetOf<String>()
        var calls = 0

        override suspend fun firstUse(jkt: String, jti: String, ttl: Duration, now: Instant): ReplayClaim {
            calls++
            return if (seen.add("$jkt:$jti")) ReplayClaim.FIRST_USE else ReplayClaim.REPLAYED
        }
    }

    private fun run(
        proof: DpopProof? = valid,
        req: DpopRequest = request,
        at: Instant = now,
        store: DpopReplayStore = RecordingStore(),
    ): DpopResult = runBlocking { DpopProofVerifier({ proof }, store).verify(req, at) }

    @Test
    fun `known-positive proof is VALID`() {
        assertThat(run()).isEqualTo(DpopResult.VALID)
    }

    @Test
    fun `RFC 9449 section 7_1 ath vector`() {
        assertThat(DpopProofVerifier.accessTokenHash(token)).isEqualTo("fUHyO2r2Z3DZ53EsNrWBb0xWXoaNy59IiKCAqksmQEo")
    }

    @Test
    fun `MISSING_PROOF when no DPoP header`() {
        assertThat(run(req = request.copy(dpopHeaderValues = emptyList()))).isEqualTo(DpopResult.MISSING_PROOF)
        assertThat(run(req = request.copy(dpopHeaderValues = listOf(" ")))).isEqualTo(DpopResult.MISSING_PROOF)
    }

    @Test
    fun `MALFORMED when unparsable, duplicated, wrong typ, no thumbprint or no jti`() {
        assertThat(run(proof = null)).isEqualTo(DpopResult.MALFORMED)
        assertThat(run(req = request.copy(dpopHeaderValues = listOf("a", "b")))).isEqualTo(DpopResult.MALFORMED)
        assertThat(run(proof = valid.copy(typ = "JWT"))).isEqualTo(DpopResult.MALFORMED)
        assertThat(run(proof = valid.copy(typ = null))).isEqualTo(DpopResult.MALFORMED)
        assertThat(run(proof = valid.copy(jwkThumbprint = null))).isEqualTo(DpopResult.MALFORMED)
        assertThat(run(proof = valid.copy(jti = null))).isEqualTo(DpopResult.MALFORMED)
        assertThat(run(proof = valid.copy(jti = "x".repeat(257)))).isEqualTo(DpopResult.MALFORMED)
    }

    @Test
    fun `ALG_NOT_ALLOWED for none, HMAC, RS256 and absent alg`() {
        listOf("none", "HS256", "RS256", "ES384", null).forEach {
            assertThat(
                run(proof = valid.copy(alg = it)),
            ).describedAs("alg=%s", it).isEqualTo(DpopResult.ALG_NOT_ALLOWED)
        }
        listOf("EdDSA", "PS256").forEach {
            assertThat(run(proof = valid.copy(alg = it))).describedAs("alg=%s", it).isEqualTo(DpopResult.VALID)
        }
    }

    @Test
    fun `PRIVATE_KEY_IN_JWK`() {
        assertThat(run(proof = valid.copy(jwkHasPrivateMaterial = true))).isEqualTo(DpopResult.PRIVATE_KEY_IN_JWK)
    }

    @Test
    fun `BAD_SIGNATURE`() {
        assertThat(run(proof = valid.copy(signatureValid = false))).isEqualTo(DpopResult.BAD_SIGNATURE)
    }

    @Test
    fun `HTM_MISMATCH including case and absence`() {
        assertThat(run(proof = valid.copy(htm = "POST"))).isEqualTo(DpopResult.HTM_MISMATCH)
        assertThat(run(proof = valid.copy(htm = "get"))).isEqualTo(DpopResult.HTM_MISMATCH)
        assertThat(run(proof = valid.copy(htm = null))).isEqualTo(DpopResult.HTM_MISMATCH)
    }

    @Test
    fun `HTU_MISMATCH on path, host, scheme, absence and a non-http request URI`() {
        assertThat(
            run(proof = valid.copy(htu = "https://resource.example.org/other")),
        ).isEqualTo(DpopResult.HTU_MISMATCH)
        assertThat(
            run(proof = valid.copy(htu = "https://evil.example.org/protectedresource")),
        ).isEqualTo(DpopResult.HTU_MISMATCH)
        assertThat(
            run(proof = valid.copy(htu = "http://resource.example.org/protectedresource")),
        ).isEqualTo(DpopResult.HTU_MISMATCH)
        assertThat(run(proof = valid.copy(htu = null))).isEqualTo(DpopResult.HTU_MISMATCH)
        assertThat(run(proof = valid.copy(htu = "not a uri"))).isEqualTo(DpopResult.HTU_MISMATCH)
        assertThat(run(req = request.copy(requestUri = "/protectedresource"))).isEqualTo(DpopResult.HTU_MISMATCH)
    }

    @Test
    fun `htu normalisation ignores query, fragment, default port and host case`() {
        val variants = listOf(
            "https://RESOURCE.example.org:443/protectedresource",
            "HTTPS://resource.example.org/protectedresource?a=b",
            "https://resource.example.org/./protectedresource#frag",
        )
        variants.forEach { assertThat(run(proof = valid.copy(htu = it))).describedAs(it).isEqualTo(DpopResult.VALID) }
        assertThat(DpopProofVerifier.normaliseHtu("https://h.example:8443")).isEqualTo("https://h.example:8443/")
        assertThat(DpopProofVerifier.normaliseHtu("https://user@h.example/")).isNull()
    }

    @Test
    fun `IAT_OUT_OF_WINDOW at both edges, inclusive inside`() {
        assertThat(run(proof = valid.copy(iat = now.plusSeconds(61)))).isEqualTo(DpopResult.IAT_OUT_OF_WINDOW)
        assertThat(run(proof = valid.copy(iat = now.minusSeconds(301)))).isEqualTo(DpopResult.IAT_OUT_OF_WINDOW)
        assertThat(run(proof = valid.copy(iat = null))).isEqualTo(DpopResult.IAT_OUT_OF_WINDOW)
        assertThat(run(proof = valid.copy(iat = now.plusSeconds(60)))).isEqualTo(DpopResult.VALID)
        assertThat(run(proof = valid.copy(iat = now.minusSeconds(300)))).isEqualTo(DpopResult.VALID)
    }

    @Test
    fun `ATH_MISMATCH for another token, absent ath and blank token`() {
        assertThat(run(req = request.copy(accessToken = "other-token"))).isEqualTo(DpopResult.ATH_MISMATCH)
        assertThat(run(proof = valid.copy(ath = null))).isEqualTo(DpopResult.ATH_MISMATCH)
        assertThat(run(req = request.copy(accessToken = ""))).isEqualTo(DpopResult.ATH_MISMATCH)
    }

    @Test
    fun `JKT_MISMATCH for another key and for a token without cnf_jkt`() {
        assertThat(run(req = request.copy(boundJkt = "AAAA" + jkt.drop(4)))).isEqualTo(DpopResult.JKT_MISMATCH)
        assertThat(run(req = request.copy(boundJkt = null))).isEqualTo(DpopResult.JKT_MISMATCH)
        assertThat(run(req = request.copy(boundJkt = ""))).isEqualTo(DpopResult.JKT_MISMATCH)
    }

    @Test
    fun `REPLAYED on second use, and a rejected proof never consumes its jti`() {
        val store = RecordingStore()
        assertThat(run(proof = valid.copy(htm = "POST"), store = store)).isEqualTo(DpopResult.HTM_MISMATCH)
        assertThat(store.calls).isZero()
        assertThat(run(store = store)).isEqualTo(DpopResult.VALID)
        assertThat(run(store = store)).isEqualTo(DpopResult.REPLAYED)
    }

    @Test
    fun `replay TTL covers the whole acceptance window`() {
        var ttl: Duration? = null
        runBlocking {
            DpopProofVerifier({ valid }, { _, _, t, _ ->
                ttl = t
                ReplayClaim.FIRST_USE
            }).verify(request, now)
        }
        assertThat(ttl).isEqualTo(Duration.ofSeconds(365))
    }

    @Test
    fun `REPLAY_STORE_UNAVAILABLE is distinct from REPLAYED`() {
        val down = DpopReplayStore { _, _, _, _ -> ReplayClaim.UNAVAILABLE }
        assertThat(run(store = down)).isEqualTo(DpopResult.REPLAY_STORE_UNAVAILABLE)
    }

    @Test
    fun `a parser that throws reads as MALFORMED, cancellation still propagates`() {
        val store = RecordingStore()
        val throwing = DpopProofParser { error("parser defect") }
        assertThat(
            runBlocking {
                DpopProofVerifier(throwing, store).verify(request, now)
            },
        ).isEqualTo(DpopResult.MALFORMED)
        val cancelling = DpopProofParser { throw kotlin.coroutines.cancellation.CancellationException("stop") }
        assertThatThrownBy { runBlocking { DpopProofVerifier(cancelling, store).verify(request, now) } }
            .isInstanceOf(kotlin.coroutines.cancellation.CancellationException::class.java)
        assertThat(store.calls).isZero()
    }

    @Test
    fun `htu percent-encoding is normalised per RFC 3986 section 6_2_2`() {
        assertThat(DpopProofVerifier.normaliseHtu("https://h.example/a%7eb%2fc%2Fd%41"))
            .isEqualTo("https://h.example/a~b%2Fc%2FdA")
        assertThat(DpopProofVerifier.normaliseHtu("https://h.example/bad%zz")).isNull()
        assertThat(DpopProofVerifier.normaliseHtu("https://h.example/trunc%4")).isNull()
        assertThat(
            run(proof = valid.copy(htu = "https://resource.example.org/protected%72esource")),
        ).isEqualTo(DpopResult.VALID)
    }

    @Test
    fun `non-ASCII host is not comparable`() {
        assertThat(DpopProofVerifier.normaliseHtu("https://bänk.example/x")).isNull()
    }

    @Test
    fun `policy rejects nonsense windows`() {
        assertThatThrownBy { DpopPolicy(maxAge = Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DpopPolicy(futureSkew = Duration.ofSeconds(-1))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
