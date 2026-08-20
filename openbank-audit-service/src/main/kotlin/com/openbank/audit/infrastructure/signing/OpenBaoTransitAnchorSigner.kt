// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.signing

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.application.port.out.AnchorPublicKeyResolver
import com.openbank.audit.application.port.out.AnchorSigner
import com.openbank.audit.application.port.out.AnchorSigningException
import io.quarkus.runtime.Startup
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * [AnchorSigner] over an OpenBao `transit` **ECDSA P-256** key, authenticated by workload identity
 * (ADR-0031 D5, ADR-0034).
 *
 * Mirrors the established fleet mechanism rather than inventing key handling: the projected
 * ServiceAccount token is exchanged at `auth/kubernetes/login` for a short-lived OpenBao token,
 * exactly as `openbank-document-service`'s `OpenBaoClientSignatureAdapter` and admin-ui's
 * `svidMint.ts` already do — same OpenBao, same auth method, a dedicated `transit` key instead of
 * a PKI mount. The private key never leaves OpenBao; this process only ever receives a signature.
 *
 * The signature is ASN.1/DER `SHA256withECDSA`, base64-encoded — the same shape `cosign` produces
 * and consumes for a `hashivault://` transit key, so an anchor can be checked either by
 * [com.openbank.audit.domain.crypto.AnchorSignatureVerifier] or by `cosign verify-blob` against
 * the exported public key.
 *
 * **Fail closed, deliberately and with no flag to turn it off.** There is no dev fallback, no
 * ephemeral key, no "unsigned but stored" path: an unavailable or invalid key makes signing throw,
 * and the caller abandons the capture. Two prior defects in this repo are the reason — a disabled
 * push adapter that returned `success = true`, and a signing adapter whose evidence-worthlessness
 * warning could only be turned into a refusal by an opt-in flag. Local development and tests do
 * not need a key because they run with `openbank.audit.anchor.enabled=false`; a deployment that
 * wants anchors must provision the key.
 *
 * [Startup] is not decoration: `@ApplicationScoped` beans are lazy, so a boot-time gate written in
 * an initializer of a bean nothing has touched yet never runs. With it, a pod configured to
 * capture anchors and unable to reach its workload identity fails at boot rather than at the first
 * scheduled capture an hour later.
 */
@Startup
@ApplicationScoped
@Suppress("LongParameterList")
class OpenBaoTransitAnchorSigner(
    @ConfigProperty(name = "openbank.audit.anchor.enabled", defaultValue = "true")
    private val anchoringEnabled: Boolean,

    @ConfigProperty(name = "openbank.audit.anchor.signing.bao-addr", defaultValue = "http://openbao.vault.svc:8200")
    private val baoAddr: String,

    @ConfigProperty(name = "openbank.audit.anchor.signing.role", defaultValue = "audit-service-anchor")
    private val role: String,

    @ConfigProperty(name = "openbank.audit.anchor.signing.transit-mount", defaultValue = "transit")
    private val transitMount: String,

    @ConfigProperty(name = "openbank.audit.anchor.signing.key-name", defaultValue = "audit-anchor")
    private val keyName: String,

    @ConfigProperty(
        name = "openbank.audit.anchor.signing.sa-token-path",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/token",
    )
    private val saTokenPath: String,

    private val objectMapper: ObjectMapper,
) : AnchorSigner,
    AnchorPublicKeyResolver {

    private val log = Logger.getLogger(OpenBaoTransitAnchorSigner::class.java)
    private val cachedPublicKey = AtomicReference<Pair<String, String>?>(null)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    override val keyId: String get() = "$KEY_ID_PREFIX$transitMount/$keyName"

    /**
     * Boot-time gate. When anchoring is enabled, a missing projected ServiceAccount token means
     * this pod has no workload identity and can therefore never sign a real anchor — refuse to
     * start rather than run and quietly capture nothing verifiable.
     */
    fun assertKeyMaterialReachable(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (!anchoringEnabled) {
            log.info("audit anchoring disabled — anchor signer will not be used")
            return
        }
        check(Files.exists(Path.of(saTokenPath))) {
            "Audit anchoring is enabled but no projected ServiceAccount token is present at " +
                "$saTokenPath, so no OpenBao transit key can be reached and no anchor could ever be " +
                "signed. Refusing to start (ADR-0031 D5, fail-closed). Set " +
                "openbank.audit.anchor.enabled=false for an environment that does not anchor."
        }
        log.infof("audit anchor signer ready: keyId=%s", keyId)
    }

    override suspend fun sign(digest: ByteArray): String = withContext(Dispatchers.IO) {
        val token = login()
        val body = objectMapper.writeValueAsString(
            mapOf(
                "input" to Base64.getEncoder().encodeToString(digest),
                "hash_algorithm" to "sha2-256",
                "marshaling_algorithm" to "asn1",
            ),
        )
        val response = post("$baoAddr/v1/$transitMount/sign/$keyName", body, token)
        if (response.statusCode() != HTTP_OK) {
            // Never the response body: it can echo request material into a log line.
            throw AnchorSigningException("OpenBao transit sign failed: HTTP ${response.statusCode()}")
        }
        val signature = objectMapper.readTree(response.body())
            .path("data").path("signature").asText(null)
            ?: throw AnchorSigningException("OpenBao transit sign returned no signature")
        // `vault:v<n>:<base64>` — strip the versioned prefix so what is stored is a plain
        // signature any third-party verifier (or cosign) can consume unaided.
        signature.substringAfterLast(':').takeIf { it.isNotBlank() }
            ?: throw AnchorSigningException("OpenBao transit sign returned an empty signature")
    }

    override suspend fun publicKeyPem(keyId: String): String? {
        if (keyId != this.keyId) return null
        cachedPublicKey.get()?.let { (id, pem) -> if (id == keyId) return pem }
        return withContext(Dispatchers.IO) {
            runCatching {
                val token = login()
                val response = get("$baoAddr/v1/$transitMount/keys/$keyName", token)
                check(response.statusCode() == HTTP_OK) {
                    "OpenBao transit key read failed: HTTP ${response.statusCode()}"
                }
                objectMapper.readTree(response.body())
                    .path("data").path("keys")
                    .let { keys ->
                        keys.fieldNames().asSequence().maxByOrNull { it.toIntOrNull() ?: 0 }?.let(keys::get)
                    }
                    ?.path("public_key")?.asText(null)
                    ?.takeIf { it.isNotBlank() }
            }.onFailure {
                log.warnf("could not read anchor public key material (%s)", it.javaClass.simpleName)
            }.getOrNull()?.also { cachedPublicKey.set(keyId to it) }
        }
    }

    private fun login(): String {
        val response = runCatching {
            val jwt = Files.readString(Path.of(saTokenPath)).trim()
            post(
                "$baoAddr/v1/auth/kubernetes/login",
                objectMapper.writeValueAsString(mapOf("role" to role, "jwt" to jwt)),
                token = null,
            )
        }.getOrElse { throw AnchorSigningException(loginFailureMessage(it), it) }
        val token = response.body()
            .takeIf { response.statusCode() == HTTP_OK }
            ?.let { objectMapper.readTree(it).path("auth").path("client_token").asText(null) }
        return token ?: throw AnchorSigningException(
            "OpenBao kubernetes-auth login did not yield a token: HTTP ${response.statusCode()}",
        )
    }

    /**
     * Names the failure without quoting the cause's message: an OpenBao client exception can embed
     * response-body fragments, which must never reach a log line (same reasoning as
     * document-service's client-PKI adapter).
     */
    private fun loginFailureMessage(cause: Throwable): String = when (cause) {
        is java.io.IOException ->
            if (Files.exists(Path.of(saTokenPath))) {
                "OpenBao login transport failure: ${cause.javaClass.simpleName}"
            } else {
                "no projected ServiceAccount token at $saTokenPath"
            }
        else -> "OpenBao login failure: ${cause.javaClass.simpleName}"
    }

    private fun post(url: String, body: String, token: String?) = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .also { b -> token?.let { b.header("X-Vault-Token", it) } }
            .POST(BodyPublishers.ofString(body))
            .build(),
        BodyHandlers.ofString(),
    )

    private fun get(url: String, token: String) = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("X-Vault-Token", token)
            .GET()
            .build(),
        BodyHandlers.ofString(),
    )

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val REQUEST_TIMEOUT_SECONDS = 10L
        const val HTTP_OK = 200
        const val KEY_ID_PREFIX = "openbao-transit:"
    }
}
