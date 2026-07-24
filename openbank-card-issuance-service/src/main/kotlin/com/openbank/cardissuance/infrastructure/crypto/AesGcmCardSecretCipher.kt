// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.crypto

import com.openbank.cardissuance.application.port.out.CardSecretCipher
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.SecureRandom
import java.util.Base64
import java.util.Optional
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM at rest for the synthetic PAN/CVV (#3).
 *
 * Wire format: `base64( IV ‖ ciphertext ‖ tag )` with a fresh 12-byte IV per value, prepended.
 * A random IV per value is mandatory for GCM — reusing one under the same key is a catastrophic
 * break, not a weakness — and prepending it keeps the column self-describing (no side table, no
 * ordering assumption at decrypt time).
 *
 * The key is 32 raw bytes, base64-encoded, in `openbank.card.pan-encryption-key`. In-cluster it
 * arrives the same way every other service secret does: an OpenBao/ESO-projected Kubernetes secret
 * exposed as the `OPENBANK_CARD_PAN_ENCRYPTION_KEY` env var, never a value committed to the repo.
 *
 * **Fail fast, never degrade.** A missing or malformed key throws at *startup* (hence [Startup] —
 * an `@ApplicationScoped` bean is lazy, so a constructor check would otherwise not run until the
 * first request, which for a rarely-hit path can be far too late; see the repo CLAUDE.md). The two
 * silent alternatives are both worse than not booting: storing the PAN in plaintext, or skipping
 * generation and shipping cards with no credential at all.
 *
 * **Dev and test generate an ephemeral key instead.** There is deliberately no committed dev key:
 * this repository is public, and a base64 blob in `application.yaml` is indistinguishable from a
 * real one to a secret scanner and to a reader skimming the file — the repo's own Gitleaks gate
 * rejects it, correctly. A per-boot random key costs dev nothing (PANs simply do not survive a
 * restart, and no dev flow depends on that) and keeps the "no key material in the repo" rule
 * absolute rather than case-by-case.
 */
@Startup
@ApplicationScoped
class AesGcmCardSecretCipher(
    // Optional, not a String with defaultValue = "": SmallRye treats an EMPTY default as no
    // default at all and fails the whole deployment with "failed to load config value" the moment
    // the property is absent — which is the normal state in dev and test.
    @ConfigProperty(name = "openbank.card.pan-encryption-key")
    private val configuredKey: Optional<String>,
    // An explicit opt-in rather than a profile sniff. `quarkus.profile` is not reliably readable
    // as a config property from a bean (it resolves to "prod" inside @QuarkusTest), and a rule as
    // consequential as "boot without a key" should be stated by the configuration that wants it,
    // not inferred. Only %dev and %test set this; prod leaves it false and fails hard.
    //
    // No Kotlin default value here either: a defaulted parameter makes Arc reject the class with
    // "does not declare a valid bean constructor", which surfaces only as an
    // UnsatisfiedResolutionException at build time.
    @ConfigProperty(name = "openbank.card.allow-ephemeral-pan-key", defaultValue = "false")
    private val allowEphemeralKey: Boolean,
) : CardSecretCipher {

    private val random = SecureRandom()

    // Eager, NOT `by lazy`: combined with @Startup this makes a bad key a boot failure rather than
    // a surprise on the first card issued.
    private val encodedKey: String = configuredKey.orElse("")

    private val key: SecretKeySpec = loadKey()

    private fun loadKey(): SecretKeySpec {
        if (encodedKey.isBlank() && allowEphemeralKey) {
            LOG.warn(
                "openbank.card.pan-encryption-key is not set and allow-ephemeral-pan-key is on; " +
                    "generating an EPHEMERAL key for this boot. Synthetic PANs written now cannot " +
                    "be read back after a restart.",
            )
            return SecretKeySpec(ByteArray(KEY_LENGTH_BYTES).also(random::nextBytes), "AES")
        }
        require(encodedKey.isNotBlank()) {
            "openbank.card.pan-encryption-key is not set. card-issuance refuses to start rather than " +
                "store synthetic PANs unencrypted. Provide base64 of exactly $KEY_LENGTH_BYTES random " +
                "bytes (env OPENBANK_CARD_PAN_ENCRYPTION_KEY)."
        }
        val raw = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrElse {
            error("openbank.card.pan-encryption-key is not valid base64")
        }
        require(raw.size == KEY_LENGTH_BYTES) {
            "openbank.card.pan-encryption-key must decode to exactly $KEY_LENGTH_BYTES bytes " +
                "(AES-256), got ${raw.size}"
        }
        return SecretKeySpec(raw, "AES")
    }

    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + sealed)
    }

    override fun decrypt(ciphertext: String): String {
        val blob = Base64.getDecoder().decode(ciphertext)
        require(blob.size > IV_LENGTH_BYTES) { "Ciphertext too short to carry an IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_LENGTH_BITS, blob, 0, IV_LENGTH_BYTES),
        )
        return String(
            cipher.doFinal(blob, IV_LENGTH_BYTES, blob.size - IV_LENGTH_BYTES),
            Charsets.UTF_8,
        )
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(AesGcmCardSecretCipher::class.java)
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_LENGTH_BYTES = 32
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
