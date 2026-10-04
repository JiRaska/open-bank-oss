// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheme

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64
import java.util.Optional

/**
 * Produces the Mastercard signer, and only when a credential is actually present.
 *
 * ## Why the bean is absent rather than broken
 *
 * A signer built from a missing or unparseable key would fail at the first request, inside the
 * adapter, as an exception the caller has to classify. Producing NO bean instead lets the adapter
 * ask `Instance.isResolvable` and answer NOT_BOUND — a state, not a failure. The two are different
 * facts and a caller acts on them differently.
 *
 * ## The key never appears in configuration text
 *
 * `openbank.card-processing.scheme.mastercard.signing-key` is the base64 of a PKCS#8 private key,
 * supplied as an environment variable from OpenBao. It is deliberately absent from every
 * `application.yaml` in this repository: a committed key is exactly what the gitleaks gate exists
 * to stop, and an empty default here is the honest "no credential in this environment".
 */
@ApplicationScoped
class MastercardSignerProducer(
    // Optional on both: application.yaml DEFINES them as empty expansions, and SmallRye reads an
    // empty value as NO value — a non-Optional injection throws SRCFG00040 at startup, so the
    // service would fail to boot in every environment that has no Mastercard credential, which is
    // all of them today (#5844).
    @ConfigProperty(name = "openbank.card-processing.scheme.mastercard.consumer-key")
    private val consumerKey: Optional<String>,
    @ConfigProperty(name = "openbank.card-processing.scheme.mastercard.signing-key")
    private val signingKeyBase64: Optional<String>,
    private val clock: Clock,
) {

    private val log = Logger.getLogger(MastercardSignerProducer::class.java)

    @Produces
    @ApplicationScoped
    fun signer(): MastercardOAuthSigner? {
        val consumer = consumerKey.orElse("")
        val encodedKey = signingKeyBase64.orElse("")
        if (consumer.isBlank() || encodedKey.isBlank()) return null
        val key = parseKey(encodedKey) ?: return null
        return MastercardOAuthSigner(consumer, key, clock)
    }

    /**
     * A malformed key is logged and produces no bean.
     *
     * Loud, because the deployment intended to configure Mastercard and did not manage to — that is
     * worth an error line. Not fatal, because a broken vendor credential must not stop the service
     * booting: the card money path does not depend on a BIN lookup.
     */
    private fun parseKey(encodedKey: String): PrivateKey? = try {
        val der = Base64.getDecoder().decode(encodedKey.filterNot { it.isWhitespace() })
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        log.errorf(
            e,
            "mastercard signing key is present but unparseable — expected base64 PKCS#8; " +
                "the Mastercard binding will report NOT_BOUND",
        )
        null
    }
}
