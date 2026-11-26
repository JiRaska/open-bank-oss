// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.oauth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.mcp.infrastructure.oauth.HostedOAuthAdmission.Vendor
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/** Site configuration is projected by the deployment and read once when hosted OAuth is enabled. */
internal class HostedOAuthSiteConfiguration private constructor(private val settings: JsonNode) {
    val baoAddress: URI get() = URI.create(required("openBaoAddress"))
    val baoCa: Path get() = path("openBaoCaFile")
    val serviceAccountToken: Path get() = path("serviceAccountTokenFile")
    val bankTlsPeer: String get() = required("bankTlsPeer")
    val bankTlsCa: Path get() = path("bankTlsCaFile")
    val bankClientCertificate: Path get() = path("bankClientCertificateFile")
    val bankClientKey: Path get() = path("bankClientKeyFile")
    val transitKeys get() = HostedOAuthKeyPins.transit(settings.path("transitKeys"))
    val bankKeys get() = HostedOAuthKeyPins.bank(settings.path("bankSigningKeys"))
    val chatGptJwks: ByteArray get() = Files.readAllBytes(path("chatGptJwksFile"))
    val chatGptJwksSha256: String get() = required("chatGptJwksSha256")

    fun custodyPepper(): ByteArray = Base64.getDecoder().decode(Files.readString(path("custodyPepperFile")).trim())
        .also { require(it.size >= MIN_PEPPER_BYTES) { "Hosted OAuth custody pepper is too short" } }

    fun claudeCredential(): PinnedHostedVendorAuthentication.ClaudeCredential =
        PinnedHostedVendorAuthentication.ClaudeCredential(
            Files.readString(path("claudeClientSecretFile")).trimEnd('\n', '\r'),
            Instant.parse(required("claudeClientSecretExpiresAt")),
        )

    private fun path(name: String): Path = Path.of(required(name)).also {
        require(it.isAbsolute && Files.isRegularFile(it)) { "Hosted OAuth requires a projected file for $name" }
    }

    private fun required(name: String): String = settings.path(name).textValue()?.takeIf(String::isNotBlank)
        ?: error("Hosted OAuth site setting $name is required")

    companion object {
        private const val MIN_PEPPER_BYTES = 32
        private const val MAX_SITE_FILE_BYTES = 65_536L

        fun load(path: Path, mapper: ObjectMapper): HostedOAuthSiteConfiguration {
            require(path.isAbsolute && Files.isRegularFile(path)) {
                "Hosted OAuth needs a projected site configuration"
            }
            require(Files.size(path) in 1..MAX_SITE_FILE_BYTES) { "Invalid hosted OAuth site configuration size" }
            val settings = Files.newInputStream(path).use { mapper.readTree(it) }
            require(settings.isObject) { "Invalid hosted OAuth site configuration" }
            return HostedOAuthSiteConfiguration(settings)
        }

        fun vendors(descriptor: String): Set<Vendor> = when (descriptor) {
            "disabled" -> emptySet()
            "chatgpt" -> setOf(Vendor.CHATGPT)
            "claude" -> setOf(Vendor.CLAUDE)
            "chatgpt,claude" -> Vendor.entries.toSet()
            else -> error("Invalid hosted OAuth activation selection")
        }
    }
}
