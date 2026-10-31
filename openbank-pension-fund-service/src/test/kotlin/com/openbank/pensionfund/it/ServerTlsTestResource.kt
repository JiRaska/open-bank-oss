// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.it

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

/** Ephemeral key material: never persisted in the repository or shared with another test boot. */
class ServerTlsTestResource : QuarkusTestResourceLifecycleManager {
    private var directory: Path? = null

    override fun start(): Map<String, String> {
        val dir = Files.createTempDirectory("pension-fund-server-tls-")
        directory = dir
        val password = UUID.randomUUID().toString()
        val storePath = dir.resolve("server.p12")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-validity", "1", "-storetype", "PKCS12", "-keystore", storePath.toString(),
            "-storepass", password, "-noprompt",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Ephemeral TLS certificate generation failed: $output" }
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(storePath).use { store.load(it, password.toCharArray()) }
        val certificate = dir.resolve("tls.crt")
        val key = dir.resolve("tls.key")
        writePem(certificate, "CERTIFICATE", store.getCertificate("server").encoded)
        writePem(key, "PRIVATE KEY", store.getKey("server", password.toCharArray()).encoded)
        return mapOf(
            "quarkus.http.ssl.certificate.files" to certificate.toString(),
            "quarkus.http.ssl.certificate.key-files" to key.toString(),
            "quarkus.http.ssl.certificate.reload-period" to "1h",
            "quarkus.http.ssl.protocols" to "TLSv1.3",
            "quarkus.http.test-ssl-port" to "0",
            "quarkus.http.insecure-requests" to "enabled",
        )
    }

    override fun stop() {
        directory?.let { dir ->
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        directory = null
    }

    private fun writePem(path: Path, label: String, bytes: ByteArray) {
        val encoded = Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)
        Files.writeString(path, "-----BEGIN $label-----\n$encoded\n-----END $label-----\n")
    }
}
