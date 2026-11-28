// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.mcp.infrastructure.oauth.HostedBankTransitProof.Key
import com.openbank.mcp.infrastructure.oauth.HostedBankTransitProof.VendorKeys
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Reads deployment-pinned public keys. Network responses cannot add or replace a pin. */
internal object HostedOAuthKeyPins {
    private const val MAX_KEY_ID_LENGTH = 128
    private const val MIN_KEY_LENGTH = 32
    private const val MAX_KEY_LENGTH = 8192

    fun transit(document: JsonNode): Map<Vendor, VendorKeys> = Vendor.entries.associateWith { vendor ->
        val pins = document.path(vendor.bankClientId)
        VendorKeys(ecKey(pins.path("assertion")), ecKey(pins.path("dpop")))
    }

    fun bank(document: JsonNode): Map<String, RSAPublicKey> {
        require(document.isObject && !document.isEmpty) { "Bank signing pins are required" }
        val factory = KeyFactory.getInstance("RSA")
        return document.properties().associate { (id, pin) ->
            require(id.length in 1..MAX_KEY_ID_LENGTH && pin.isTextual) { "Invalid bank signing pin" }
            id to (factory.generatePublic(spec(pin)) as RSAPublicKey)
        }
    }

    private fun ecKey(pin: JsonNode): Key {
        val version = pin.path("version")
        require(version.isIntegralNumber && version.canConvertToInt()) { "A pinned Transit key version is required" }
        val key = KeyFactory.getInstance("EC").generatePublic(spec(pin.path("publicKeyDer"))) as ECPublicKey
        return Key(version.intValue(), key)
    }

    private fun spec(encoded: JsonNode): X509EncodedKeySpec {
        val value = encoded.textValue()?.takeIf { it.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH }
            ?: error("A DER-encoded public key is required")
        return X509EncodedKeySpec(Base64.getDecoder().decode(value))
    }
}
