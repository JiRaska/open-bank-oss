// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardissuance.application.port.out.CardSecretCipher
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.runtime.Startup
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
import java.util.Optional

/**
 * [CardSecretCipher] envelope-encryption adapter (ADR-0262). The synthetic PAN/CVV
 * data-encrypting key (DEK) is wrapped by an OpenBao Transit key-encryption key (KEK) instead of
 * living as a flat 32-byte secret in a Kubernetes Secret, the way [AesGcmCardSecretCipher] reads
 * it today. All actual PAN/CVV encrypt/decrypt work stays local and unchanged — delegated to an
 * [AesGcmCardSecretCipher] instance constructed with the unwrapped DEK — so this class only ever
 * talks to OpenBao once, at boot, to unwrap the DEK. No per-value network call, no change to the
 * encrypt/decrypt hot path.
 *
 * **Rotation** (the gap this ADR closes): `vault write -f transit/keys/<kek-name>/rotate` bumps
 * the KEK version. OpenBao Transit retains prior key versions, so a wrapped DEK produced under an
 * older version still unwraps after rotation — nothing here needs to change to keep decrypting
 * existing rows. Minting a *new* DEK under the new KEK version and re-encrypting existing PAN/CVV
 * rows from the old DEK to the new one is a separate, follow-up batch job (mirrors
 * [com.openbank.cardissuance.application.usecase.CardPanVaultBackfill]'s idempotent,
 * count-only-logging shape) — not part of this adapter, which only ever unwraps whatever DEK it is
 * configured with at boot.
 *
 * **Only the active key source is a CDI bean.** `openbank.card.key-source=openbao-transit` is a
 * BUILD-time property: when set, this bean is registered and [AesGcmCardSecretCipher]'s own
 * `@IfBuildProperty` excludes it, so exactly one `CardSecretCipher` implementation — and one
 * `@Startup` key load — exists per build. Left unset (the default), this class does not exist in
 * the bean archive and every deployment keeps today's flat-key behavior untouched.
 *
 * **Dev and test never talk to OpenBao.** Mirrors [AesGcmCardSecretCipher]'s own dev/test
 * ephemeral-key path exactly — same config flag, same class reused for the actual key generation —
 * because a local integration test has no Transit engine to unwrap against, and should not need
 * one to exercise PAN encryption.
 *
 * **Test seam is two constructor-defaulted lambdas, not `open`/override.** [delegate] is loaded
 * eagerly (see below) as part of THIS class's own constructor, and in Kotlin a superclass's eager
 * property initializers run before a subclass's constructor parameters are assigned — so an
 * `open fun` overridden by a test subclass would be invoked while the subclass's own stub fields
 * are still null. Defaulted constructor parameters don't have that ordering problem: [loginFn] and
 * [unwrapDekFn] are bound before [delegate]'s initializer runs, in normal top-to-bottom order
 * within one constructor. CDI never sees these parameters (no matching injectable type for a
 * `() -> String` / `(String, String) -> ByteArray`), so `@Inject`ion is unaffected in production.
 */
@IfBuildProperty(name = "openbank.card.key-source", stringValue = "openbao-transit")
@Startup
@ApplicationScoped
@Suppress("LongParameterList")
class OpenBaoEnvelopeCardSecretCipher(
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

    // The OpenBao Transit ciphertext for the DEK ("vault:v<N>:...", produced once by an operator
    // via `vault write transit/encrypt/<kek-name> plaintext=$(head -c32 /dev/urandom | base64)`,
    // then projected the same OpenBao/ESO way today's flat pan-encryption-key is). Optional<String>,
    // not String — the same SmallRye empty-default trap AesGcmCardSecretCipher's own key property
    // avoids: a missing optional typed as plain String throws SRCFG00040 at boot.
    @ConfigProperty(name = "openbank.card.envelope.wrapped-dek")
    private val wrappedDek: Optional<String>,

    // Same dev/test escape hatch AesGcmCardSecretCipher exposes, and deliberately reused rather
    // than re-implemented: when no wrapped DEK is configured and this is on, boot with a per-boot
    // random local key and never call OpenBao at all.
    @ConfigProperty(name = "openbank.card.allow-ephemeral-pan-key", defaultValue = "false")
    private val allowEphemeralKey: Boolean,

    private val objectMapper: ObjectMapper,

    // Test-only seams (see class doc). CDI never satisfies a bare function-type constructor
    // parameter via @Inject, so these always take their real-HTTP default in every deployed build;
    // only a directly-constructed test instance ever passes a non-default value.
    private val loginFn: (() -> String)? = null,
    private val unwrapDekFn: ((String, String) -> ByteArray)? = null,
) : CardSecretCipher {

    private val log = Logger.getLogger(OpenBaoEnvelopeCardSecretCipher::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    // Eager, NOT `by lazy`: combined with @Startup this makes a bad KEK/DEK a boot failure, never
    // a surprise on the first card issued — same reasoning as AesGcmCardSecretCipher's own `key`.
    private val delegate: AesGcmCardSecretCipher = loadDelegate()

    override fun encrypt(plaintext: String): String = delegate.encrypt(plaintext)

    override fun decrypt(ciphertext: String): String = delegate.decrypt(ciphertext)

    private fun loadDelegate(): AesGcmCardSecretCipher {
        val wrapped = wrappedDek.orElse("")
        if (wrapped.isBlank()) {
            require(allowEphemeralKey) {
                "openbank.card.envelope.wrapped-dek is not set. card-issuance refuses to start " +
                    "rather than run without an envelope-encrypted DEK when " +
                    "openbank.card.key-source=openbao-transit (ADR-0262)."
            }
            log.warn(
                "openbank.card.envelope.wrapped-dek is not set and allow-ephemeral-pan-key is on; " +
                    "generating an EPHEMERAL local key for this boot. OpenBao Transit is not " +
                    "consulted. Synthetic PANs written now cannot be read back after a restart.",
            )
            return AesGcmCardSecretCipher(Optional.empty(), true)
        }
        val token = (loginFn ?: ::login)()
        val dek = (unwrapDekFn ?: ::unwrapDek)(token, wrapped)
        require(dek.size == DEK_LENGTH_BYTES) {
            "OpenBao transit key '$kekName' unwrapped a DEK of ${dek.size} bytes, expected " +
                "$DEK_LENGTH_BYTES (AES-256)"
        }
        return AesGcmCardSecretCipher(Optional.of(Base64.getEncoder().encodeToString(dek)), false)
    }

    /**
     * OpenBao Kubernetes-auth login — same flow as
     * `OpenBaoClientSignatureAdapter` (document-service) uses for its own PKI issuance, against a
     * dedicated role scoped to this Transit key rather than a PKI mount.
     */
    private fun login(): String {
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
    private fun unwrapDek(token: String, wrapped: String): ByteArray {
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

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val REQUEST_TIMEOUT_SECONDS = 10L
        const val HTTP_OK = 200
        const val DEK_LENGTH_BYTES = 32
    }
}
