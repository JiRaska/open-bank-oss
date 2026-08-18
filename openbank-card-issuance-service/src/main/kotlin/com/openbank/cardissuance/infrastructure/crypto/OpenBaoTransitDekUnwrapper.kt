// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
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

/**
 * Unwraps an OpenBao Transit ciphertext (`vault:v<N>:...`) into raw key bytes (ADR-0262). Shared by
 * two callers with different lifecycles:
 *
 * - [OpenBaoEnvelopeCardSecretCipher] unwraps the CURRENT DEK once, eagerly, at `@Startup`.
 * - `CardPanKeyReencrypt` unwraps a PREVIOUS DEK on demand, only when an operator has configured
 *   `openbank.card.envelope.previous-wrapped-dek` after rotating the KEK — most boots never call
 *   [unwrap] at all.
 *
 * Deliberately NOT `@Startup` and does no eager work itself: unlike the cipher, this bean's own
 * construction must never touch OpenBao, since it exists in every build (there is no
 * `@IfBuildProperty` gate here — both callers already gate their OWN OpenBao usage).
 *
 * Retries the whole (login, decrypt) pair together, restarting from a fresh login each attempt —
 * a token that logged in fine but then failed decrypt (e.g. it expired between the two calls)
 * should not be reused on retry.
 *
 * **`open`, with `protected open` [login]/[unwrapDek], instead of a constructor-defaulted lambda
 * test seam.** Unlike [OpenBaoEnvelopeCardSecretCipher] (injected everywhere via the
 * `CardSecretCipher` interface), this class is injected by CALLERS using its own concrete type —
 * and empirically, Quarkus ArC fails `@ApplicationScoped` bean discovery for a Kotlin class with a
 * defaulted function-typed constructor parameter the moment something else injects it by concrete
 * type (`UnsatisfiedResolutionException: ... does not declare a valid bean constructor`), even
 * though the identical pattern is fine for an interface-injected bean. Subclassing carries none of
 * the Kotlin-init-ordering risk documented elsewhere in this package (that trap is specifically
 * about an EAGER property initializer invoking an open member during construction —
 * [OpenBaoEnvelopeCardSecretCipher]'s own `delegate`); [login] and [unwrapDek] are only ever
 * called on demand, from [unwrap], never from this class's own init.
 */
@ApplicationScoped
@Suppress("LongParameterList")
open class OpenBaoTransitDekUnwrapper(
    @ConfigProperty(name = "openbank.card.envelope.bao-addr", defaultValue = "http://openbao.vault.svc:8200")
    private val baoAddr: String,

    @ConfigProperty(name = "openbank.card.envelope.role", defaultValue = "card-issuance-pan-dek")
    private val role: String,

    @ConfigProperty(name = "openbank.card.envelope.transit-mount", defaultValue = "transit")
    private val transitMount: String,

    @ConfigProperty(name = "openbank.card.envelope.kek-name", defaultValue = "card-pan")
    private val kekName: String,

    @ConfigProperty(
        name = "openbank.card.envelope.sa-token-path",
        defaultValue = "/var/run/secrets/kubernetes.io/serviceaccount/token",
    )
    private val saTokenPath: String,

    private val objectMapper: ObjectMapper,
) {

    private val log = Logger.getLogger(OpenBaoTransitDekUnwrapper::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    /** Unwraps [wrapped] into raw key bytes. Retries transient failures with exponential backoff. */
    fun unwrap(wrapped: String): ByteArray = withRetry("OpenBao transit decrypt for key '$kekName'") {
        val token = login()
        unwrapDek(token, wrapped)
    }

    /**
     * OpenBao Kubernetes-auth login — same flow as
     * `OpenBaoClientSignatureAdapter` (document-service) uses for its own PKI issuance, against a
     * dedicated role scoped to this Transit key rather than a PKI mount.
     */
    protected open fun login(): String {
        val jwt = Files.readString(Path.of(saTokenPath)).trim()
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

    /** Unwraps [wrapped] (an OpenBao Transit ciphertext, `vault:v<N>:...`) via Transit `decrypt`. */
    protected open fun unwrapDek(token: String, wrapped: String): ByteArray {
        val body = objectMapper.writeValueAsString(mapOf("ciphertext" to wrapped))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baoAddr/v1/$transitMount/decrypt/$kekName"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("X-Vault-Token", token)
            .POST(BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) {
            "OpenBao transit decrypt failed for key '$kekName': HTTP ${response.statusCode()}"
        }
        val plaintextBase64 = objectMapper.readTree(response.body())["data"]["plaintext"].asText()
        return Base64.getDecoder().decode(plaintextBase64)
    }

    /**
     * Retries [block] on any exception (connection refused/timeout, or the `check()`-thrown
     * `IllegalStateException` for a non-200 response) with exponential backoff, up to
     * [MAX_ATTEMPTS] total tries. Blocking `Thread.sleep` is fine here: both callers already run
     * this synchronously (one at `@Startup`, the other inside a batch job that is not on any
     * request hot path).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> withRetry(description: String, block: () -> T): T {
        var delay = INITIAL_BACKOFF
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    log.warn(
                        "$description failed (attempt ${attempt + 1}/$MAX_ATTEMPTS: " +
                            "${e.javaClass.simpleName}), retrying in ${delay.toMillis()}ms",
                    )
                    Thread.sleep(delay.toMillis())
                    delay = delay.multipliedBy(BACKOFF_MULTIPLIER)
                }
            }
        }
        throw lastError ?: error("$description failed with no recorded exception")
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val REQUEST_TIMEOUT_SECONDS = 10L
        const val HTTP_OK = 200
        const val MAX_ATTEMPTS = 4
        const val BACKOFF_MULTIPLIER = 2L
        val INITIAL_BACKOFF: Duration = Duration.ofMillis(500)
    }
}
