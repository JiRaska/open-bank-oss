// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.render

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.out.ClientSignatureIssuerPort
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Duration

/**
 * [ClientSignatureIssuerPort] adapter (ADR-0162 D4 continued): issues a fresh, single-use
 * certificate from OpenBao's `pki-document-signing` PKI secrets engine for each signing act,
 * signs with it, and lets the private key go out of scope immediately after — never written to
 * disk, a keystore, or OpenBao itself (the engine's issuing role is configured `no_store=true`).
 * The CA root that issues these leaves stays in OpenBao (runbook 0008); only the audited,
 * short-TTL leaf issuance ever touches this process. Mirrors the exact Kubernetes-auth
 * login-then-issue flow `openbank-admin-ui`'s `svidMint.ts` already uses for `pki-agent`
 * (ADR-0031 D3b) — same OpenBao, same auth method, a different dedicated PKI mount/role.
 *
 * Falls back to a local ephemeral self-signed identity (same DEV-ONLY fallback
 * [PdfBoxPadesSealAdapter] uses) when the projected ServiceAccount token isn't present (i.e. not
 * running in a real pod bound to the signing role) or the OpenBao call itself fails — so local
 * dev/tests never need a live OpenBao, at the cost of an ephemeral leaf being worthless as
 * evidence, exactly like the seal's own dev fallback.
 */
@ApplicationScoped
class OpenBaoClientSignatureAdapter(
    @ConfigProperty(name = "openbank.signature.client-pki.bao-addr", defaultValue = "http://openbao.vault.svc:8200")
    private val baoAddr: String,

    @ConfigProperty(name = "openbank.signature.client-pki.role", defaultValue = "document-service-signing")
    private val role: String,

    @ConfigProperty(
        name = "openbank.signature.client-pki.issue-path",
        defaultValue = "pki-document-signing/issue/client-signing",
    )
    private val issuePath: String,

    @ConfigProperty(
        name = "openbank.signature.client-pki.sa-token-path",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/token",
    )
    private val saTokenPath: String,

    @ConfigProperty(name = "openbank.signature.client-pki.ttl", defaultValue = "300s")
    private val ttl: String,

    // Go-live gate (ADR-0162 D4 continued): when set, the DEV-ONLY ephemeral fallback below is
    // DISABLED — a signing act that cannot obtain a real OpenBao-rooted certificate fails loud
    // instead of silently producing an evidence-worthless signature. Defaults off so the service
    // stays runnable before the pki-document-signing engine is provisioned; flip
    // OPENBANK_SIGNATURE_REQUIRE_TRUSTED_ISSUER=true in the deployment env once it is (runbook 0008).
    @ConfigProperty(name = "openbank.signature.require-trusted-issuer", defaultValue = "false")
    private val requireTrustedIssuer: Boolean,

    private val objectMapper: ObjectMapper,
) : ClientSignatureIssuerPort {

    private val logger = Logger.getLogger(OpenBaoClientSignatureAdapter::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    override suspend fun signAsClient(pdf: ByteArray, partyRef: String): ByteArray = withContext(Dispatchers.IO) {
        val identity = issueOneTimeIdentity(partyRef)
        PadesSigning.applySignature(pdf, identity, partyRef, SIGNATURE_REASON)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun issueOneTimeIdentity(partyRef: String): SigningIdentity {
        if (!Files.exists(Path.of(saTokenPath))) {
            return devFallbackOrFail(partyRef, "no projected ServiceAccount token")
        }
        return try {
            val token = login(Files.readString(Path.of(saTokenPath)).trim())
            issueFromOpenBao(token, partyRef)
        } catch (e: Exception) {
            // Deliberately pass only the exception TYPE, never e.message: a Vault/OpenBao HTTP
            // client exception's message can embed response-body fragments, which must never
            // reach a log line (CodeQL java/log-injection -- "insertion of sensitive information
            // into log files"). The class name is enough to diagnose which failure mode fired.
            devFallbackOrFail(partyRef, e.javaClass.simpleName)
        }
    }

    /**
     * Either takes the DEV-ONLY ephemeral fallback (logging a loud warning) or, when
     * [requireTrustedIssuer] is set, refuses outright so a SIGNED decision is never recorded against
     * an evidentially worthless signature (fail-closed — [signAsClient] propagates the failure up
     * into the ceremony, which does not persist the decision). The failure message carries only the
     * reason code, never [partyRef] (same CodeQL sensitive-data-in-logs reasoning as [logDevFallback]).
     */
    private fun devFallbackOrFail(partyRef: String, reason: String): SigningIdentity {
        check(!requireTrustedIssuer) {
            "Refusing to issue a client electronic signature without an OpenBao-rooted one-time " +
                "certificate ($reason). openbank.signature.require-trusted-issuer is set, so the " +
                "DEV-ONLY ephemeral fallback is disabled (ADR-0162 D4 continued, runbook 0008)."
        }
        logDevFallback(reason)
        return PadesSigning.generateEphemeralIdentity(partyRef, EPHEMERAL_VALIDITY_DAYS)
    }

    // No partyRef (or any other per-signer identifier) in this log line, deliberately: CodeQL's
    // sensitive-data-in-logs query flags a customer-identifying reference interpolated into a log
    // message, and this warning's whole purpose (an operational dev-fallback signal) doesn't need
    // per-signer granularity anyway -- the reason code alone is enough to diagnose it.
    private fun logDevFallback(reason: String) {
        logger.warn(
            "Could not issue a OpenBao-rooted one-time signing certificate ($reason) — falling back " +
                "to an EPHEMERAL, in-memory, non-CA-issued certificate. This is DEV-ONLY: the resulting " +
                "signature is worthless as evidence (no auditable issuing CA). Production must run with " +
                "a bound ServiceAccount + a reachable OpenBao pki-document-signing engine " +
                "(ADR-0162 D4 continued, runbook 0008).",
        )
    }

    private fun login(jwt: String): String {
        val body = objectMapper.writeValueAsString(mapOf("role" to role, "jwt" to jwt))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baoAddr/v1/auth/kubernetes/login"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) {
            "OpenBao kubernetes-auth login failed: HTTP ${response.statusCode()}"
        }
        return objectMapper.readTree(response.body())["auth"]["client_token"].asText()
    }

    private fun issueFromOpenBao(token: String, partyRef: String): SigningIdentity {
        val body = objectMapper.writeValueAsString(
            mapOf("common_name" to partyRef, "ttl" to ttl, "private_key_format" to "pkcs8"),
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baoAddr/v1/$issuePath"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("X-Vault-Token", token)
            .POST(BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) { "OpenBao pki issuance failed: HTTP ${response.statusCode()}" }
        val data = objectMapper.readTree(response.body())["data"]
        val caChain = data["ca_chain"]?.map { it.asText() }.orEmpty()
        return SigningIdentity(
            privateKey = parsePrivateKey(data["private_key"].asText()),
            certificate = parseCertificate(data["certificate"].asText()),
            certificateChain = listOf(parseCertificate(data["certificate"].asText())) + caChain.map(::parseCertificate),
        )
    }

    private fun parseCertificate(pem: String): X509Certificate = PEMParser(StringReader(pem)).use { parser ->
        val holder = parser.readObject() as X509CertificateHolder
        JcaX509CertificateConverter().setProvider(PadesSigning.BC_PROVIDER).getCertificate(holder)
    }

    private fun parsePrivateKey(pem: String): PrivateKey = PEMParser(StringReader(pem)).use { parser ->
        val keyInfo = parser.readObject() as PrivateKeyInfo
        JcaPEMKeyConverter().setProvider(PadesSigning.BC_PROVIDER).getPrivateKey(keyInfo)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val REQUEST_TIMEOUT_SECONDS = 10L
        const val HTTP_OK = 200
        const val EPHEMERAL_VALIDITY_DAYS = 1L
        const val SIGNATURE_REASON = "Client electronic signature (one-time certificate)"
    }
}
