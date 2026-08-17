// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.crypto

import com.openbank.cardissuance.application.port.out.CardSecretCipher
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Base64
import java.util.Optional

/**
 * [CardSecretCipher] envelope-encryption adapter (ADR-0262). The synthetic PAN/CVV
 * data-encrypting key (DEK) is wrapped by an OpenBao Transit key-encryption key (KEK) instead of
 * living as a flat 32-byte secret in a Kubernetes Secret, the way [AesGcmCardSecretCipher] reads
 * it today. All actual PAN/CVV encrypt/decrypt work stays local and unchanged — delegated to an
 * [AesGcmCardSecretCipher] instance constructed with the unwrapped DEK — so this class only ever
 * talks to OpenBao once, at boot, to unwrap the DEK (via [OpenBaoTransitDekUnwrapper]). No
 * per-value network call, no change to the encrypt/decrypt hot path.
 *
 * **Rotation** (the gap this ADR closes): `vault write -f transit/keys/<kek-name>/rotate` bumps
 * the KEK version. OpenBao Transit retains prior key versions, so a wrapped DEK produced under an
 * older version still unwraps after rotation — nothing here needs to change to keep decrypting
 * existing rows. Minting a *new* DEK under the new KEK version and re-encrypting existing PAN/CVV
 * rows from the old DEK to the new one is `CardPanKeyReencrypt` — a separate, follow-up batch job
 * (mirrors [com.openbank.cardissuance.application.usecase.CardPanVaultBackfill]'s idempotent,
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
 * **Test seam is a constructor-defaulted lambda, not `open`/override.** [delegate] is loaded
 * eagerly (see below) as part of THIS class's own constructor, and in Kotlin a superclass's eager
 * property initializers run before a subclass's constructor parameters are assigned — so an
 * `open fun` overridden by a test subclass would be invoked while the subclass's own stub fields
 * are still null. A defaulted constructor parameter doesn't have that ordering problem: [unwrapFn]
 * is bound before [delegate]'s initializer runs, in normal top-to-bottom order within one
 * constructor. CDI never sees it (no matching injectable type for a `(String) -> ByteArray`), so
 * `@Inject`ion is unaffected in production.
 */
@IfBuildProperty(name = "openbank.card.key-source", stringValue = "openbao-transit")
@Startup
@ApplicationScoped
class OpenBaoEnvelopeCardSecretCipher(
    private val unwrapper: OpenBaoTransitDekUnwrapper,

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

    // Test-only seam (see class doc). CDI never satisfies a bare function-type constructor
    // parameter via @Inject, so this always takes its real-unwrapper default in every deployed
    // build; only a directly-constructed test instance ever passes a non-default value.
    private val unwrapFn: ((String) -> ByteArray)? = null,
) : CardSecretCipher {

    private val log = Logger.getLogger(OpenBaoEnvelopeCardSecretCipher::class.java)

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
        val dek = (unwrapFn ?: unwrapper::unwrap)(wrapped)
        require(dek.size == DEK_LENGTH_BYTES) {
            "OpenBao envelope key unwrapped a DEK of ${dek.size} bytes, expected $DEK_LENGTH_BYTES (AES-256)"
        }
        return AesGcmCardSecretCipher(Optional.of(Base64.getEncoder().encodeToString(dek)), false)
    }

    private companion object {
        const val DEK_LENGTH_BYTES = 32
    }
}
