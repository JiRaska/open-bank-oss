// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.infrastructure.security.InMemoryNonceStore
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Optional

/**
 * ADR-0031 D3b: the SVID verifier is the security core — a PoP-signed pki-agent cert becomes the
 * per-run agent identity. The test PKI (CA + leaf) is built at RUNTIME with BouncyCastle so no
 * private key is ever committed (gitleaks-clean); the verifier itself uses only the JDK.
 */
class AgentSvidVerifierTest {

    private val now: Instant = Instant.parse("2026-06-28T12:00:00Z")
    private val skew = 60L

    private data class Pki(val caPem: String, val leafPem: String, val leafKey: PrivateKey)

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun cert(
        subjectCn: String,
        issuerCn: String,
        subjectKey: PublicKey,
        signerKey: PrivateKey,
        notBefore: Instant,
        notAfter: Instant,
    ): X509Certificate {
        val holder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuerCn"),
            BigInteger.valueOf(1),
            Date.from(notBefore),
            Date.from(notAfter),
            X500Name("CN=$subjectCn"),
            subjectKey,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(signerKey))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun toPem(cert: X509Certificate): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    /** A self-signed CA + a leaf (CN = [cn]) signed by it, valid [now-1s, now+ttl]. */
    private fun pki(cn: String, ttl: Duration = Duration.ofMinutes(5)): Pki {
        val ca = ecKeyPair()
        val caCert = cert(
            subjectCn = "Test Agent CA",
            issuerCn = "Test Agent CA",
            subjectKey = ca.public,
            signerKey = ca.private,
            notBefore = now.minusSeconds(60),
            notAfter = now.plus(Duration.ofDays(3650)),
        )
        val leaf = ecKeyPair()
        val leafCert = cert(cn, "Test Agent CA", leaf.public, ca.private, now.minusSeconds(1), now.plus(ttl))
        return Pki(toPem(caCert), toPem(leafCert), leaf.private)
    }

    private fun pop(key: PrivateKey, ts: String, nonce: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update("$ts.$nonce".toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(sign())
    }

    private fun ts(at: Instant = now) = at.toEpochMilli().toString()

    @Test
    fun `a valid cert and PoP yield the CN as the agent id`() {
        val p = pki("ui-assistant")
        val v = AgentSvidVerifier(Optional.of(p.caPem), skew, InMemoryNonceStore())
        val t = ts()
        assertThat(v.verify(p.leafPem, pop(p.leafKey, t, "n1"), t, "n1", now))
            .isEqualTo(SvidResult.Verified("ui-assistant"))
    }

    @Test
    fun `no CA configured disables SVID`() {
        val result = AgentSvidVerifier(Optional.empty(), skew, InMemoryNonceStore()).verify("c", "s", ts(), "n", now)
        assertThat(result).isEqualTo(SvidResult.Disabled)
    }

    @Test
    fun `CA configured but no cert presented is Disabled, not Rejected (staged rollout fallback)`() {
        val p = pki("ui-assistant")
        val v = AgentSvidVerifier(Optional.of(p.caPem), skew, InMemoryNonceStore())
        // No SVID headers at all → caller falls back to the D3a binding (svid.enforced decides), so a
        // CA being configured must not hard-reject callers that don't yet present certs (PR5b-2).
        assertThat(v.verify(null, null, null, null, now)).isEqualTo(SvidResult.Disabled)
    }

    @Test
    fun `a present cert with missing PoP headers is rejected`() {
        val p = pki("ui-assistant")
        val v = AgentSvidVerifier(Optional.of(p.caPem), skew, InMemoryNonceStore())
        assertThat(v.verify(p.leafPem, null, ts(), "n", now)).isInstanceOf(SvidResult.Rejected::class.java)
    }

    @Test
    fun `a tampered PoP signature is rejected`() {
        val p = pki("ui-assistant")
        val v = AgentSvidVerifier(Optional.of(p.caPem), skew, InMemoryNonceStore())
        val t = ts()
        val tampered = pop(p.leafKey, t, "n1").dropLast(4) + "AAAA"
        assertThat(v.verify(p.leafPem, tampered, t, "n1", now))
            .isEqualTo(SvidResult.Rejected("PoP signature does not verify"))
    }

    @Test
    fun `a PoP signed over a different nonce is rejected`() {
        val p = pki("ui-assistant")
        val v = AgentSvidVerifier(Optional.of(p.caPem), skew, InMemoryNonceStore())
        val t = ts()
        val sigForN1 = pop(p.leafKey, t, "n1")
        assertThat(v.verify(p.leafPem, sigForN1, t, "n2", now))
            .isEqualTo(SvidResult.Rejected("PoP signature does not verify"))
    }

    @Test
    fun `a cert from an untrusted CA is rejected`() {
        val trusted = pki("ui-assistant")
        val other = pki("ui-assistant")
        val v = AgentSvidVerifier(Optional.of(trusted.caPem), skew, InMemoryNonceStore())
        val t = ts()
        assertThat(v.verify(other.leafPem, pop(other.leafKey, t, "n"), t, "n", now))
            .isEqualTo(SvidResult.Rejected("certificate not issued by the trusted agent CA"))
    }

    @Test
    fun `an expired cert is rejected`() {
        val p = pki("ui-assistant", ttl = Duration.ofMinutes(5))
        val v = AgentSvidVerifier(Optional.of(p.caPem), skew, InMemoryNonceStore())
        val later = now.plus(Duration.ofMinutes(10))
        val t = ts(later)
        assertThat(v.verify(p.leafPem, pop(p.leafKey, t, "n"), t, "n", later))
            .isEqualTo(SvidResult.Rejected("certificate expired or not yet valid"))
    }

    @Test
    fun `a stale timestamp is rejected`() {
        val p = pki("ui-assistant")
        val v = AgentSvidVerifier(Optional.of(p.caPem), skew, InMemoryNonceStore())
        val staleTs = ts(now.minusSeconds(120))
        assertThat(v.verify(p.leafPem, pop(p.leafKey, staleTs, "n"), staleTs, "n", now))
            .isEqualTo(SvidResult.Rejected("stale or future timestamp"))
    }

    @Test
    fun `a replayed nonce is rejected on second use`() {
        val p = pki("ui-assistant")
        val v = AgentSvidVerifier(Optional.of(p.caPem), skew, InMemoryNonceStore())
        val t = ts()
        val sig = pop(p.leafKey, t, "n1")
        assertThat(v.verify(p.leafPem, sig, t, "n1", now)).isEqualTo(SvidResult.Verified("ui-assistant"))
        assertThat(v.verify(p.leafPem, sig, t, "n1", now)).isEqualTo(SvidResult.Rejected("replayed nonce"))
    }

    @Test
    fun `a base64-encoded cert and CA verify the same as raw PEM (on-the-wire transport)`() {
        val p = pki("ui-assistant")
        val b64 = { s: String -> Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) }
        val v = AgentSvidVerifier(Optional.of(b64(p.caPem)), skew, InMemoryNonceStore())
        val t = ts()
        assertThat(v.verify(b64(p.leafPem), pop(p.leafKey, t, "n1"), t, "n1", now))
            .isEqualTo(SvidResult.Verified("ui-assistant"))
    }
}
