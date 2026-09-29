// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate

class PeerHostnameVerifierTest {
    private var counter = 0

    private fun cert(dir: Path, san: String): X509Certificate {
        val ks = dir.resolve("ks-${counter++}.p12").toString()
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val proc =
            ProcessBuilder(
                keytool, "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=cn-only.test", "-ext", "san=$san", "-validity", "1",
                "-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
            ).redirectErrorStream(true).start()
        check(proc.waitFor() == 0) { proc.inputStream.readAllBytes().decodeToString() }
        val store = KeyStore.getInstance("PKCS12").apply {
            java.io.File(ks).inputStream().use { load(it, "changeit".toCharArray()) }
        }
        return store.getCertificate("s") as X509Certificate
    }

    @Test
    fun `exact dNSName SAN matches case-insensitively and ignores a trailing dot`(@TempDir dir: Path) {
        val c = cert(dir, "dns:api.example.test")
        assertThat(PeerHostnameVerifier.matches("API.example.test.", c)).isTrue()
        assertThat(PeerHostnameVerifier.matches("other.example.test", c)).isFalse()
        assertThat(PeerHostnameVerifier.matches("example.test", c)).isFalse()
    }

    @Test
    fun `the subject CN is never consulted`(@TempDir dir: Path) {
        assertThat(PeerHostnameVerifier.matches("cn-only.test", cert(dir, "dns:else.test"))).isFalse()
    }

    @Test
    fun `a wildcard covers exactly one left-most label`(@TempDir dir: Path) {
        val c = cert(dir, "dns:*.example.test")
        assertThat(PeerHostnameVerifier.matches("a.example.test", c)).isTrue()
        assertThat(PeerHostnameVerifier.matches("a.b.example.test", c)).isFalse()
        assertThat(PeerHostnameVerifier.matches("example.test", c)).isFalse()
        assertThat(PeerHostnameVerifier.matches("aexample.test", c)).isFalse()
    }

    @Test
    fun `a two-label wildcard is refused`(@TempDir dir: Path) {
        assertThat(PeerHostnameVerifier.matches("example.test", cert(dir, "dns:*.test"))).isFalse()
    }

    @Test
    fun `an IP literal matches only an iPAddress SAN, never a dNSName`(@TempDir dir: Path) {
        assertThat(PeerHostnameVerifier.matches("192.0.2.7", cert(dir, "ip:192.0.2.7"))).isTrue()
        assertThat(PeerHostnameVerifier.matches("192.0.2.8", cert(dir, "ip:192.0.2.7"))).isFalse()
        assertThat(PeerHostnameVerifier.matches("192.0.2.7", cert(dir, "dns:192.0.2.7"))).isFalse()
    }
}
