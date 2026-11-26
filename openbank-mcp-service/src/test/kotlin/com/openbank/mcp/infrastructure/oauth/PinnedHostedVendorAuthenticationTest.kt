// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.InMemoryDpopReplayStore
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.TokenAuthentication
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jwk.RsaJwkGenerator
import org.jose4j.jws.JsonWebSignature
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat

class PinnedHostedVendorAuthenticationTest {
    @Test
    fun `ChatGPT requires the pinned signature, expected audience and one-use assertion`(): Unit = runBlocking {
        val key = RsaJwkGenerator.generateJwk(2048).apply {
            keyId = "fixture-key"
            algorithm = "RS256"
            use = "sig"
        }
        val jwks = JsonWebKeySet(key).toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY).encodeToByteArray()
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jwks))
        val now = Instant.parse("2026-11-26T16:00:00Z")
        val mapper = ObjectMapper()
        val authentication = PinnedHostedVendorAuthentication(
            jwks,
            digest,
            mapper,
            InMemoryDpopReplayStore(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        fun credential(audience: String): HostedVendorAuthentication.Credentials {
            val payload = mapper.createObjectNode()
                .put("iss", Vendor.CHATGPT.clientId)
                .put("sub", Vendor.CHATGPT.clientId)
                .put("aud", audience)
                .put("iat", now.epochSecond)
                .put("exp", now.plusSeconds(30).epochSecond)
                .put("jti", "fixture-jti")
            val signed = JsonWebSignature().apply {
                keyIdHeaderValue = key.keyId
                algorithmHeaderValue = "RS256"
                this.key = key.privateKey
                this.payload = payload.toString()
            }.compactSerialization
            return HostedVendorAuthentication.Credentials(
                Vendor.CHATGPT.clientId,
                TokenAuthentication.PRIVATE_KEY_JWT,
                signed,
            )
        }
        assertThat(authentication.authenticate(credential("https://unrelated.example"))).isNull()
        val verified = credential("${HostedOAuthComplete.ISSUER}/token")
        assertThat(authentication.authenticate(verified)).isEqualTo(Vendor.CHATGPT)
        assertThat(authentication.authenticate(verified)).isNull()
    }
}
