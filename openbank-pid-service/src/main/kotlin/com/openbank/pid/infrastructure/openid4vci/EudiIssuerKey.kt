// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vci

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.PublicJsonWebKey
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import java.util.Optional

/**
 * The EUDI issuer's EC P-256 signing key (eIDAS 2.0, ADR-0094), shared by everything pid signs as the
 * issuer: OpenID4VCI credentials AND the Token Status List. Loaded once from
 * `openbank.pid.eudi.issuer.signing-key-jwk` (Vault in production). Absent/blank/unparseable ⇒
 * [enabled] is false and [sign] throws — issuance and revocation publication are fail-closed (a bank
 * must never emit an unsigned credential or status list).
 */
@ApplicationScoped
class EudiIssuerKey(
    @ConfigProperty(name = "openbank.pid.eudi.issuer.signing-key-jwk")
    signingKeyJwk: Optional<String>,
    @ConfigProperty(name = "openbank.pid.eudi.issuer.issuer-id", defaultValue = "https://pid.open-bank.tech")
    val issuerId: String,
) {
    private val key: PublicJsonWebKey? = signingKeyJwk
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "{}" }
        .map { runCatching { PublicJsonWebKey.Factory.newPublicJwk(it) }.getOrNull() }
        .orElse(null)
        .also {
            if (it == null) {
                Log.warn(
                    "EUDI issuer signing key not configured — credential issuance + revocation are DISABLED (fail-closed)",
                )
            }
        }

    val enabled: Boolean get() = key != null

    /** Sign [payloadJson] as a compact ES256 JWS with the issuer key (optional `typ` header). */
    fun sign(payloadJson: String, typ: String? = null): String {
        val signingKey = key ?: error("issuer signing key not configured")
        return JsonWebSignature().apply {
            payload = payloadJson
            this.key = signingKey.privateKey
            keyIdHeaderValue = signingKey.keyId
            typ?.let { setHeader("typ", it) }
            algorithmHeaderValue = AlgorithmIdentifiers.ECDSA_USING_P256_CURVE_AND_SHA256
        }.compactSerialization
    }

    /** The public half of the holder/issuer JWK as a JWKS document — empty when issuance is disabled. */
    fun publicJwksJson(): String =
        key?.let { """{"keys":[${it.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)}]}""" } ?: """{"keys":[]}"""
}
