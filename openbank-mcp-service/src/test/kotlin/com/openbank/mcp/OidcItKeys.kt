// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * RSA key pairs for [McpCustomerTenantResolutionIT], generated once per build directory and shared
 * through the FILESYSTEM. A `QuarkusTestProfile` runs in a different classloader from the test
 * class, so a key generated in memory would differ between the two (CLAUDE.md); a file does not.
 * Nothing is committed: the keys live under `build/` and are regenerated on a clean build.
 */
object OidcItKeys {
    private val dir: Path = Path.of(System.getProperty("user.dir"), "build", "customer-tenant-it-keys")

    @Synchronized
    fun pair(name: String): KeyPair {
        Files.createDirectories(dir)
        val pub = dir.resolve("$name.pub")
        val priv = dir.resolve("$name.key")
        if (!Files.exists(pub) || !Files.exists(priv)) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
            Files.write(priv, kp.private.encoded)
            Files.write(pub, kp.public.encoded)
        }
        val kf = KeyFactory.getInstance("RSA")
        return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(Files.readAllBytes(pub))),
            kf.generatePrivate(PKCS8EncodedKeySpec(Files.readAllBytes(priv))),
        )
    }

    /** The form `quarkus.oidc.public-key` takes: base64 of the X.509 SubjectPublicKeyInfo. */
    fun publicKeyConfig(name: String): String = Base64.getEncoder().encodeToString(pair(name).public.encoded)

    private const val KEY_BITS = 2048
}
