// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.out.NonceStore
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Optional

/**
 * ADR-0031 D3b (verify side): verifies a proof-of-possession over a short-TTL OpenBao-issued client
 * certificate, so a per-run cryptographic identity can replace the trusted `X-Agent-Id` header. The
 * caller (PR5b-2: the admin-ui BFF) presents:
 *  - `X-Agent-Cert`      — the PEM leaf cert (CN = agent id), issued by the `pki-agent` CA (#2405)
 *  - `X-Agent-PoP`       — base64 `SHA256withECDSA` signature over `"<ts>.<nonce>"` by the cert key
 *  - `X-Agent-PoP-Ts`    — epoch-millis timestamp
 *  - `X-Agent-PoP-Nonce` — a per-request random nonce
 *
 * Verification denies on any failure: chain to the configured CA, cert validity window (the ≤5-min
 * cert TTL bounds replay), PoP signature by the cert's public key, timestamp freshness (±skew), and
 * nonce single-use (in-memory TTL cache). On success the agent id is the cert CN.
 *
 * Returns [SvidResult.Disabled] when no CA is configured (the `%dev/%test` profile, and prod until
 * PR5b-2 supplies the CA + the BFF starts presenting certs) — the caller then falls back to the D3a
 * header binding. The request body is NOT bound into the PoP in this first increment: the BFF→/mcp
 * hop is in-cluster TLS and the PoP's job here is anti-forgery + anti-replay of the identity
 * assertion; body-integrity binding is a documented hardening follow-up.
 */
@ApplicationScoped
class AgentSvidVerifier(
    // Optional<String>, not String: an empty value is converted to "null" by SmallRye and would
    // throw SRCFG00040 at boot for a plain String. Empty/absent → SVID disabled (header binding).
    @ConfigProperty(name = "agent.identity.svid.ca-cert")
    caCertPem: Optional<String>,
    @ConfigProperty(name = "agent.identity.svid.max-skew-seconds", defaultValue = "60")
    private val maxSkewSeconds: Long,
    /**
     * Single-use nonce claim, injected so replay protection can be backed by a store the whole
     * fleet's replicas share (issue #4728) rather than this class's own per-pod map. See
     * [NonceStore]'s KDoc for the durability rationale and the two bindings.
     */
    private val nonceStore: NonceStore,
) {
    private val log = Logger.getLogger(AgentSvidVerifier::class.java)

    /** Trust anchor: the pki-agent CA. Blank/unparseable config → SVID disabled (fail to header binding). */
    private val caCert: X509Certificate? = parseCaOrNull(caCertPem.orElse(""))

    val enabled: Boolean get() = caCert != null

    fun verify(certPem: String?, popB64: String?, timestampMillis: String?, nonce: String?, now: Instant): SvidResult {
        val ca = caCert ?: return SvidResult.Disabled
        // No cert presented at all = "SVID not attempted" → Disabled, so the caller falls back to the
        // D3a binding (and the svid.enforced flag decides). Only a PRESENT cert with missing/broken PoP
        // headers is a real malformed attempt (INCOMPLETE/Rejected). This keeps a staged rollout safe:
        // setting the CA must not hard-reject callers that don't yet present certs (PR5b-2).
        val cert = certPem?.takeIf { it.isNotBlank() } ?: return SvidResult.Disabled
        val pop = popB64?.takeIf { it.isNotBlank() } ?: return INCOMPLETE
        val tsStr = timestampMillis?.takeIf { it.isNotBlank() } ?: return INCOMPLETE
        val nce = nonce?.takeIf { it.isNotBlank() } ?: return INCOMPLETE
        val ts = tsStr.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
            ?: return SvidResult.Rejected("malformed timestamp")
        if (Duration.between(ts, now).abs() > Duration.ofSeconds(maxSkewSeconds)) {
            return SvidResult.Rejected("stale or future timestamp")
        }
        return verifyCert(cert, pop, tsStr, nce, ca, now)
    }

    private fun verifyCert(
        certPem: String,
        popB64: String,
        ts: String,
        nonce: String,
        ca: X509Certificate,
        now: Instant,
    ): SvidResult {
        val leaf = parseCertOrNull(certPem) ?: return SvidResult.Rejected("malformed certificate")
        if (!chainsToCa(leaf, ca)) return SvidResult.Rejected("certificate not issued by the trusted agent CA")
        if (!validAt(leaf, now)) return SvidResult.Rejected("certificate expired or not yet valid")
        if (!popVerifies(leaf, popB64, "$ts.$nonce")) return SvidResult.Rejected("PoP signature does not verify")
        // Resolve the CN before consuming the nonce, so a no-CN cert doesn't burn a nonce.
        val agentId = cnOf(leaf) ?: return SvidResult.Rejected("certificate has no CN")
        if (!consumeNonce(nonce)) return SvidResult.Rejected("replayed nonce")
        return SvidResult.Verified(agentId)
    }

    private fun chainsToCa(leaf: X509Certificate, ca: X509Certificate): Boolean = runCatching {
        leaf.verify(ca.publicKey)
        true
    }.getOrDefault(false)

    private fun validAt(leaf: X509Certificate, now: Instant): Boolean = runCatching {
        leaf.checkValidity(Date.from(now))
        true
    }.getOrDefault(false)

    private fun popVerifies(leaf: X509Certificate, popB64: String, signed: String): Boolean {
        val sig = runCatching { Base64.getDecoder().decode(popB64) }.getOrNull() ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(leaf.publicKey)
                update(signed.toByteArray(Charsets.UTF_8))
            }.verify(sig)
        }.getOrDefault(false)
    }

    /**
     * Atomically claim [nonce]; false when already seen (replay). Bridges to the suspend
     * [NonceStore] port from this class's synchronous API — the same `runBlocking` bridge this
     * service already uses at its MCP entry point (`McpEndpoint.handle`) for the equally
     * synchronous JAX-RS/`@Blocking`-worker-thread call path.
     */
    private fun consumeNonce(nonce: String): Boolean =
        runBlocking { nonceStore.claim(nonce, maxSkewSeconds * NONCE_TTL_MULTIPLIER) }

    private fun cnOf(cert: X509Certificate): String? =
        CN_PATTERN.find(cert.subjectX500Principal.name)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseCaOrNull(pem: String): X509Certificate? = if (pem.isBlank()) {
        null
    } else {
        runCatching { parseCert(pem) }
            .onFailure { log.error("agent.identity.svid.ca-cert is set but unparseable — SVID DISABLED", it) }
            .getOrNull()
    }

    private fun parseCertOrNull(pem: String): X509Certificate? = runCatching { parseCert(pem) }.getOrNull()

    /**
     * Parse a cert from either a raw PEM (in-process callers, a file/secret mount) or a
     * base64-encoded cert (the on-the-wire transport): an HTTP header cannot carry the PEM's
     * newlines, and YAML `${}` env expansion mangles a multi-line value — so the `X-Agent-Cert`
     * header and the `ca-cert` config carry base64(PEM|DER), decoded here (detected by the PEM marker).
     */
    private fun parseCert(value: String): X509Certificate {
        val bytes = if (value.contains(PEM_MARKER)) {
            value.toByteArray(Charsets.UTF_8)
        } else {
            Base64.getMimeDecoder().decode(value.trim())
        }
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    private companion object {
        const val NONCE_TTL_MULTIPLIER = 2L
        const val PEM_MARKER = "BEGIN CERTIFICATE"
        val CN_PATTERN = Regex("CN=([^,]+)")
        val INCOMPLETE = SvidResult.Rejected("incomplete SVID headers")
    }
}

/** Outcome of [AgentSvidVerifier.verify]. */
sealed interface SvidResult {
    /** No CA configured — SVID off; the caller falls back to the D3a header binding. */
    data object Disabled : SvidResult
    data class Verified(val agentId: String) : SvidResult
    data class Rejected(val reason: String) : SvidResult
}
